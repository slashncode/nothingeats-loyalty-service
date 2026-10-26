package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.Award
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.CouponState
import io.github.slashncode.nothingeats.loyalty.domain.CouponTerms
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.Rewards
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The shop's time zone: streak days and "night owl" hours are Hamburg's, like the restaurants'. */
val SHOP_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** Coupon codes like `NE-7KQ3-M9XD`: no 0/O, 1/I/L or U, so they survive being read out loud. */
class CouponCodes(
    private val random: SecureRandom = SecureRandom(),
) {
    fun next(): String {
        val chars = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "NE-${chars.take(GROUP)}-${chars.drop(GROUP)}"
    }

    private companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val CODE_LENGTH = 8
        const val GROUP = 4
    }
}

internal fun issue(
    terms: CouponTerms,
    userId: String,
    codes: CouponCodes,
    now: Instant,
) = Coupon(UUID.randomUUID().toString(), codes.next(), userId, terms, now, now + Coupon.VALIDITY, CouponState.Active)

/**
 * `order placed`: remember what was ordered until the delivery is rewarded, and redeem the coupon
 * the checkout reserved. Both in one transaction, so a repeated event changes nothing.
 */
class RecordPlacedOrder(
    private val orders: Orders,
    private val coupons: Coupons,
    private val transactions: Transactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RecordPlacedOrder::class.java)

    suspend operator fun invoke(order: OrderFacts) {
        transactions.run {
            if (!orders.insert(order)) return@run
            val code = order.couponCode ?: return@run
            val coupon = coupons.lockByCode(code)?.takeIf { it.userId == order.userId }
            if (coupon == null) {
                log.warn("Order {} used coupon {}, which loyalty does not know for that customer", order.orderId, code)
                return@run
            }
            coupon.redeem(order.orderId, clock.instant())?.let { coupons.update(it) }
        }
    }
}

/**
 * `order delivered`: XP, streak, a stamp, badges, a spin per level, a free dish for a full card.
 * Stored and published (`loyalty.reward.v1`) in one transaction; the member row is locked, so two
 * deliveries of the same customer on two pods add up instead of overwriting each other.
 */
class AwardDelivery(
    private val orders: Orders,
    private val members: Members,
    private val cards: StampCards,
    private val badges: EarnedBadges,
    private val coupons: Coupons,
    private val events: RewardEvents,
    private val transactions: Transactions,
    private val definitions: List<BadgeDefinition>,
    private val analytics: ProductAnalytics,
    private val codes: CouponCodes = CouponCodes(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AwardDelivery::class.java)

    suspend operator fun invoke(orderId: String): RewardEarned? {
        val reward =
            transactions.run {
                val known = orders.find(orderId)
                if (known == null) {
                    // Placed before loyalty existed, or the customer is gone: nothing to reward.
                    log.info("Delivered order {} is unknown; no reward", orderId)
                    return@run null
                }
                if (known.awardedAt != null) return@run null
                val order = known.facts
                val now = clock.instant()
                val member = members.lock(order.userId)
                val card = cards.find(order.userId, order.restaurant.id) ?: StampCard.empty(order.userId, order.restaurant)
                val earned = badges.ofUser(order.userId).map { it.key }.toSet()
                val day = order.placedAt.atZone(SHOP_ZONE).toLocalDate()
                val award = Rewards.forDelivery(member, card, order, day, definitions, earned)
                val issued = award.coupons.map { issue(it, order.userId, codes, now) }

                members.save(award.member)
                award.card?.let { cards.save(it) }
                badges.add(order.userId, award.badges.map { it.key }, now)
                issued.forEach { coupons.insert(it) }
                orders.markAwarded(orderId, now)
                RewardEarned(order.userId, RewardCause.ORDER_DELIVERED, orderId, award, issued, order.analytics).also { events.earned(it) }
            } ?: return null
        if (reward.analytics) analytics.captureAward(reward)
        return reward
    }
}

/** Server-side product events for an award; only for customers who agreed to analytics. */
internal suspend fun ProductAnalytics.captureAward(reward: RewardEarned) {
    val award: Award = reward.award
    val user = reward.userId
    capture(
        "xp_earned",
        mapOf(
            "xp" to award.xpGained,
            "cause" to reward.cause.name.lowercase(),
            "level" to award.member.level,
            "streak_days" to award.member.streak.current,
            "\$set" to
                mapOf(
                    "loyalty_level" to award.member.level,
                    "loyalty_xp" to award.member.xp,
                    "loyalty_streak_days" to award.member.streak.current,
                ),
        ),
        distinctId = user,
    )
    if (award.levelUp) capture("level_reached", mapOf("level" to award.member.level), distinctId = user)
    award.badges.forEach { capture("badge_earned", mapOf("badge" to it.key, "tier" to it.tier.name.lowercase()), distinctId = user) }
    if (award.cardCompleted) capture("stamp_card_completed", mapOf("restaurant_slug" to award.card?.restaurant?.slug), distinctId = user)
}
