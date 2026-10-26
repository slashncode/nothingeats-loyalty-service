package io.github.slashncode.nothingeats.loyalty.adapters.messaging

import io.github.slashncode.nothingeats.loyalty.application.RewardEarned
import io.github.slashncode.nothingeats.loyalty.application.RewardEvents
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Levels
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import kotlinx.serialization.Serializable

/** Contract: docs/events.md. */
object LoyaltyTopics {
    const val REWARDS = "loyalty.reward.v1"
    const val EARNED = "nothingeats.loyalty.reward.earned.v1"
    const val SOURCE = "/nothingeats/loyalty"
}

@Serializable
data class TextV1(
    val de: String,
    val en: String,
)

@Serializable
data class XpV1(
    val gained: Int,
    val total: Long,
    val level: Int,
    val previousLevel: Int,
    val levelStartXp: Long,
    val nextLevelXp: Long,
)

@Serializable
data class BadgeV1(
    val key: String,
    val tier: String,
    val name: TextV1,
)

@Serializable
data class StampCardV1(
    val restaurantId: String,
    val restaurantSlug: String,
    val restaurantName: String,
    val stamps: Int,
    val perCard: Int,
    val completed: Boolean,
)

@Serializable
data class CouponV1(
    val code: String,
    val kind: String,
    val value: Long,
    val maxDiscountCents: Long?,
    val minimumOrderCents: Long,
    val restaurantSlug: String?,
    val restaurantName: String?,
    val expiresAt: String,
)

@Serializable
data class RewardEarnedV1(
    val userId: String,
    val cause: String,
    val orderId: String?,
    val xp: XpV1,
    val spinsGranted: Int,
    val spinsAvailable: Int,
    val streakDays: Int,
    val badges: List<BadgeV1>,
    val stampCard: StampCardV1?,
    val coupons: List<CouponV1>,
    val analytics: Boolean,
)

internal fun Coupon.v1() =
    CouponV1(
        code = code,
        kind = terms.kind.name,
        value = terms.value,
        maxDiscountCents = terms.maxDiscountCents,
        minimumOrderCents = terms.minimumOrderCents,
        restaurantSlug = terms.restaurant?.slug,
        restaurantName = terms.restaurant?.name,
        expiresAt = expiresAt.toString(),
    )

/** Keyed by user, so one customer's rewards arrive in the order they were earned. */
class OutboxRewardEvents(
    private val outbox: Outbox,
) : RewardEvents {
    override suspend fun earned(reward: RewardEarned) {
        val award = reward.award
        val member = award.member
        outbox.enqueue(
            LoyaltyTopics.REWARDS,
            reward.userId,
            LoyaltyTopics.EARNED,
            RewardEarnedV1(
                userId = reward.userId,
                cause = reward.cause.name,
                orderId = reward.orderId,
                xp =
                    XpV1(
                        gained = award.xpGained,
                        total = member.xp,
                        level = member.level,
                        previousLevel = award.before.level,
                        levelStartXp = Levels.startOf(member.level),
                        nextLevelXp = Levels.startOf(member.level + 1),
                    ),
                spinsGranted = award.spinsGranted,
                spinsAvailable = member.spinsAvailable,
                streakDays = member.streak.current,
                badges = award.badges.map { BadgeV1(it.key, it.tier.name, TextV1(it.name.de, it.name.en)) },
                stampCard =
                    award.card?.let {
                        StampCardV1(
                            restaurantId = it.restaurant.id,
                            restaurantSlug = it.restaurant.slug,
                            restaurantName = it.restaurant.name,
                            // A just completed card shows full, not empty.
                            stamps = if (award.cardCompleted) StampCard.PER_CARD else it.stamps,
                            perCard = StampCard.PER_CARD,
                            completed = award.cardCompleted,
                        )
                    },
                coupons = reward.coupons.map { it.v1() },
                analytics = reward.analytics,
            ),
            RewardEarnedV1.serializer(),
        )
    }
}
