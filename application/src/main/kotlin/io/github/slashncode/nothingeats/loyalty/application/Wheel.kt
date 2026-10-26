package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.Award
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.Rewards
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import kotlin.random.Random

data class SpinResult(
    val prize: Prize,
    val coupon: Coupon?,
    /** Bonus XP or the golden fork; null for coupons. */
    val award: Award?,
    val spinsLeft: Int,
)

/**
 * One turn of the wheel, decided here and never in the browser. The odds may come from an
 * experiment (PostHog flag payload) for people who agreed to analytics; everyone else gets the
 * defaults. A golden fork the member has already becomes bonus XP.
 */
class SpinWheel(
    private val members: Members,
    private val coupons: Coupons,
    private val badges: EarnedBadges,
    private val events: RewardEvents,
    private val transactions: Transactions,
    private val odds: SpinOdds,
    private val definitions: List<BadgeDefinition>,
    private val analytics: ProductAnalytics,
    private val codes: CouponCodes = CouponCodes(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    suspend operator fun invoke(userId: String): SpinResult {
        val consented = AnalyticsContext.consented()
        val weights = odds.weights(AnalyticsContext.current()?.distinctId).filterValues { it > 0 }.ifEmpty { Prize.DEFAULT_WEIGHTS }
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
                val drawn = Prize.pick(weights, random.nextInt(weights.values.sum()))
                val prize = if (drawn == Prize.GOLDEN_FORK && Prize.GOLDEN_FORK_BADGE in earned) Prize.BONUS_XP else drawn
                val terms = prize.coupon
                when {
                    terms != null -> {
                        val coupon = issue(terms, userId, codes, now)
                        coupons.insert(coupon)
                        members.save(spent)
                        SpinResult(prize, coupon, null, spent.spinsAvailable)
                    }
                    prize == Prize.BONUS_XP -> {
                        val award = Rewards.forBonusXp(spent, Prize.BONUS_XP_AMOUNT, definitions, earned)
                        members.save(award.member)
                        badges.add(userId, award.badges.map { it.key }, now)
                        events.earned(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), consented))
                        SpinResult(prize, null, award, award.member.spinsAvailable)
                    }
                    else -> {
                        val fork = definitions.first { it.key == Prize.GOLDEN_FORK_BADGE }
                        val award = Award(spent, spent, emptyList(), listOf(fork), null, false, emptyList())
                        members.save(spent)
                        badges.add(userId, listOf(fork.key), now)
                        events.earned(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), consented))
                        SpinResult(prize, null, award, spent.spinsAvailable)
                    }
                }
            }
        // Without consent there is no distinct id in the context, and capture() drops the event.
        analytics.capture("wheel_spun", mapOf("prize" to result.prize.name.lowercase()))
        result.award?.let { award ->
            if (consented) analytics.captureAward(RewardEarned(userId, RewardCause.WHEEL, null, award, emptyList(), true))
        }
        return result
    }
}
