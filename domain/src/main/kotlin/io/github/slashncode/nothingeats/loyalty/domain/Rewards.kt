package io.github.slashncode.nothingeats.loyalty.domain

import java.time.LocalDate

enum class XpReason {
    /** Every delivered order. */
    ORDER,

    /** One XP per euro not spent, up to 50. */
    MONEY_NOT_SPENT,

    /** Five per day the streak runs beyond the first, up to 30. */
    STREAK,

    /** The first order from a restaurant. */
    NEW_RESTAURANT,

    /** A wheel prize. */
    BONUS,
}

data class XpLine(
    val reason: XpReason,
    val xp: Int,
)

/** Everything one delivered order (or one wheel prize) changed. */
data class Award(
    val before: Member,
    val member: Member,
    val xp: List<XpLine>,
    val badges: List<BadgeDefinition>,
    /** The restaurant's card after the stamp; null for wheel prizes. */
    val card: StampCard?,
    val cardCompleted: Boolean,
    /** Coupons to issue (a full stamp card). */
    val coupons: List<CouponTerms>,
) {
    val xpGained: Int get() = xp.sumOf { it.xp }
    val levelUp: Boolean get() = member.level > before.level

    /** One spin of the wheel per level reached. */
    val spinsGranted: Int get() = member.level - before.level
}

object Rewards {
    const val ORDER_XP = 25
    const val MAX_MONEY_XP = 50
    const val STREAK_XP_PER_DAY = 5
    const val MAX_STREAK_XP = 30
    const val NEW_RESTAURANT_XP = 15
    private const val CENTS_PER_EURO = 100

    /**
     * A delivered order: XP, the streak for [day] (the day it was placed, shop time), a stamp on
     * the restaurant's [card], a spin per level reached, badges, and a coupon for a full card.
     */
    fun forDelivery(
        member: Member,
        card: StampCard,
        order: OrderFacts,
        day: LocalDate,
        definitions: List<BadgeDefinition>,
        earned: Set<String>,
    ): Award {
        val newRestaurant = card.isNew
        val stamped = card.stamp()
        val cardCompleted = stamped.completed > card.completed
        val streak = member.streak.record(day)
        val xp =
            buildList {
                add(XpLine(XpReason.ORDER, ORDER_XP))
                val money = minOf(order.totalCents / CENTS_PER_EURO, MAX_MONEY_XP.toLong()).toInt()
                if (money > 0) add(XpLine(XpReason.MONEY_NOT_SPENT, money))
                val streakXp = minOf((streak.current - 1) * STREAK_XP_PER_DAY, MAX_STREAK_XP)
                if (streakXp > 0) add(XpLine(XpReason.STREAK, streakXp))
                if (newRestaurant) add(XpLine(XpReason.NEW_RESTAURANT, NEW_RESTAURANT_XP))
            }
        val counted =
            member.copy(
                xp = member.xp + xp.sumOf { it.xp },
                orders = member.orders + 1,
                moneyNotSpentCents = member.moneyNotSpentCents + order.totalCents,
                caloriesAvoided = member.caloriesAvoided + order.calories,
                restaurants = member.restaurants + if (newRestaurant) 1 else 0,
                dealOrders = member.dealOrders + if (order.withDeal) 1 else 0,
                couponOrders = member.couponOrders + if (order.couponCode != null) 1 else 0,
                stampCardsCompleted = member.stampCardsCompleted + if (cardCompleted) 1 else 0,
                streak = streak,
            )
        val after = counted.copy(spinsAvailable = counted.spinsAvailable + (counted.level - member.level))
        return Award(
            before = member,
            member = after,
            xp = xp,
            badges = definitions.newlyEarned(earned, BadgeFacts(after, order)),
            card = stamped,
            cardCompleted = cardCompleted,
            coupons = if (cardCompleted) listOf(stampCardCoupon(card.restaurant)) else emptyList(),
        )
    }

    /** Bonus XP from the wheel; it can reach a level, and so another spin. */
    fun forBonusXp(
        member: Member,
        xp: Int,
        definitions: List<BadgeDefinition>,
        earned: Set<String>,
    ): Award {
        val counted = member.copy(xp = member.xp + xp)
        val after = counted.copy(spinsAvailable = counted.spinsAvailable + (counted.level - member.level))
        return Award(
            before = member,
            member = after,
            xp = listOf(XpLine(XpReason.BONUS, xp)),
            badges = definitions.newlyEarned(earned, BadgeFacts(after, null)),
            card = null,
            cardCompleted = false,
            coupons = emptyList(),
        )
    }
}
