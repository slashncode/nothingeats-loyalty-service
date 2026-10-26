package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Levels
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import java.time.Clock
import java.time.Instant

data class BadgeProgress(
    val definition: BadgeDefinition,
    /** Null while the badge is still to be earned. */
    val earnedAt: Instant?,
)

/** Everything the rewards page shows. */
data class RewardsOverview(
    val member: Member,
    val levelStartXp: Long,
    val nextLevelXp: Long,
    /** The streak as it stands today: 0 once a whole day went by without an order. */
    val streakDays: Int,
    val orderedToday: Boolean,
    val badges: List<BadgeProgress>,
    val stampCards: List<StampCard>,
    /** Coupons that can still be used, soonest expiry first. */
    val coupons: List<Coupon>,
)

class ViewRewards(
    private val members: Members,
    private val cards: StampCards,
    private val badges: EarnedBadges,
    private val coupons: Coupons,
    private val definitions: List<BadgeDefinition>,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(userId: String): RewardsOverview {
        val member = members.find(userId) ?: Member.new(userId)
        val now = clock.instant()
        val today = now.atZone(SHOP_ZONE).toLocalDate()
        val earned = badges.ofUser(userId).associate { it.key to it.at }
        return RewardsOverview(
            member = member,
            levelStartXp = Levels.startOf(member.level),
            nextLevelXp = Levels.startOf(member.level + 1),
            streakDays = member.streak.currentOn(today),
            orderedToday = member.streak.lastDay == today,
            // Earned first (newest first), then the ones still to earn in definition order.
            badges =
                definitions
                    .map { BadgeProgress(it, earned[it.key]) }
                    .sortedWith(compareBy<BadgeProgress> { it.earnedAt == null }.thenByDescending { it.earnedAt }),
            stampCards = cards.ofUser(userId).sortedWith(compareByDescending<StampCard> { it.stamps }.thenBy { it.restaurant.name }),
            coupons = openCoupons(coupons, userId, now),
        )
    }
}

/** The customer's usable coupons, for the checkout's coupon picker. */
class ListCoupons(
    private val coupons: Coupons,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(userId: String): List<Coupon> = openCoupons(coupons, userId, clock.instant())
}

private suspend fun openCoupons(
    coupons: Coupons,
    userId: String,
    now: Instant,
) = coupons.ofUser(userId).filter { it.isOpenAt(now) }.sortedBy { it.expiresAt }

data class RewardsExport(
    val member: Member?,
    val badges: List<EarnedBadge>,
    val stampCards: List<StampCard>,
    val coupons: List<Coupon>,
)

/** GDPR art. 15/20: everything loyalty holds about the user. */
class ExportRewards(
    private val members: Members,
    private val cards: StampCards,
    private val badges: EarnedBadges,
    private val coupons: Coupons,
) {
    suspend operator fun invoke(userId: String) =
        RewardsExport(members.find(userId), badges.ofUser(userId), cards.ofUser(userId), coupons.ofUser(userId))
}

/** `user.deleted`: the account, its cards, badges, coupons and remembered orders go. */
class ForgetMember(
    private val members: Members,
    private val cards: StampCards,
    private val badges: EarnedBadges,
    private val coupons: Coupons,
    private val orders: Orders,
    private val transactions: Transactions,
) {
    suspend operator fun invoke(userId: String): Int =
        transactions.run {
            cards.deleteByUser(userId) + badges.deleteByUser(userId) + coupons.deleteByUser(userId) + orders.deleteByUser(userId) +
                members.delete(userId)
        }
}
