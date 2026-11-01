package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.Award
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.PrizeReward
import io.github.slashncode.nothingeats.loyalty.domain.Rewards
import io.github.slashncode.nothingeats.loyalty.domain.Wheel
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

data class SpinResult(
    /** The segment the wheel stops at. */
    val prize: Prize,
    val coupon: Coupon?,
    /** Bonus XP or a badge; null for coupons. */
    val award: Award?,
    val spinsLeft: Int,
)

/**
 * One turn of the wheel, decided here and never in the browser. Prizes and odds come from the
 * backoffice (the old ones until it has published a wheel); an experiment's variant picks the
 * odds profile for people who agreed to analytics, everyone else gets the default. A badge the
 * member already has becomes bonus XP.
 */
class SpinWheel(
    private val members: Members,
    private val coupons: Coupons,
    private val badges: EarnedBadges,
    private val events: RewardEvents,
    private val transactions: Transactions,
    private val odds: SpinOdds,
    private val definitions: BadgeDefinitions,
    private val wheels: Wheels,
    private val analytics: ProductAnalytics,
    private val codes: CouponCodes = CouponCodes(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    suspend operator fun invoke(userId: String): SpinResult {
        val consented = AnalyticsContext.consented()
        val wheel = wheels.current() ?: Wheel.DEFAULT
        val weights = wheel.weights(odds.profile(AnalyticsContext.current()?.distinctId))
        val known = definitions.all()
        val result =
            transactions.run {
                val member = members.lock(userId)
                if (member.spinsAvailable <=
                    0
                ) {
                    throw AppError.Conflict(ErrorCode("NO_SPINS_LEFT"), "There is no spin left; reach the next level")
                }
                val spent = member.copy(spinsAvailable = member.spinsAvailable - 1)
                val now = clock.instant()
                val earned = badges.ofUser(userId).map { it.key }.toSet()
                val drawn = Wheel.pick(weights, random.nextInt(weights.sumOf { it.second }))
                when (val reward = drawn.reward) {
                    is PrizeReward.Coupon -> {
                        val coupon = issue(reward.terms, userId, codes, now)
                        coupons.insert(coupon)
                        members.save(spent)
                        SpinResult(drawn, coupon, null, spent.spinsAvailable)
                    }
                    is PrizeReward.BonusXp -> bonusXp(userId, drawn, reward.xp, spent, known, earned, consented, now)
                    is PrizeReward.Badge -> {
                        val badge = known.firstOrNull { it.key == reward.badgeKey }
                        if (badge == null || badge.key in earned) {
                            // Already there (or unknown): bonus XP, and the wheel shows its bonus segment if it has one.
                            val bonus = wheel.bonusXp
                            val xp = (bonus?.reward as? PrizeReward.BonusXp)?.xp ?: Wheel.FALLBACK_XP
                            bonusXp(userId, bonus ?: drawn, xp, spent, known, earned, consented, now)
                        } else {
                            val award = Award(spent, spent, emptyList(), listOf(badge), null, false, emptyList())
                            members.save(spent)
                            badges.add(userId, listOf(badge.key), now)
                            events.earned(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), consented))
                            SpinResult(drawn, null, award, spent.spinsAvailable)
                        }
                    }
                }
            }
        // Without consent there is no distinct id in the context, and capture() drops the event.
        analytics.capture("wheel_spun", mapOf("prize" to result.prize.key.lowercase()))
        result.award?.let { award ->
            if (consented) analytics.captureAward(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), true))
        }
        return result
    }

    private suspend fun bonusXp(
        userId: String,
        prize: Prize,
        xp: Int,
        spent: Member,
        known: List<BadgeDefinition>,
        earned: Set<String>,
        consented: Boolean,
        now: Instant,
    ): SpinResult {
        val award = Rewards.forBonusXp(spent, xp, known, earned)
        members.save(award.member)
        badges.add(userId, award.badges.map { it.key }, now)
        events.earned(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), consented))
        return SpinResult(prize, null, award, award.member.spinsAvailable)
    }
}

/** The wheel's segments for the page, in their order: what the wheel shows before it spins. */
class ShowWheel(
    private val wheels: Wheels,
) {
    suspend operator fun invoke(): Wheel = wheels.current() ?: Wheel.DEFAULT
}

/** A badge as the backoffice published it (`backoffice.badge.v1`). */
class PublishBadge(
    private val definitions: BadgeDefinitions,
) {
    suspend operator fun invoke(definition: BadgeDefinition) = definitions.save(definition)
}

/** The wheel as the backoffice published it (`backoffice.wheel.v1`). */
class PublishWheel(
    private val wheels: Wheels,
) {
    suspend operator fun invoke(wheel: Wheel) = wheels.save(wheel)
}
