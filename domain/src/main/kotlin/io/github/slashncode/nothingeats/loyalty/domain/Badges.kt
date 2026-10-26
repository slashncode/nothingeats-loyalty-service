package io.github.slashncode.nothingeats.loyalty.domain

import java.time.Instant

enum class BadgeTier { BRONZE, SILVER, GOLD }

/** A badge and the rule that earns it. The definitions are data (`badges.json`), not code. */
data class BadgeDefinition(
    val key: String,
    val tier: BadgeTier,
    val name: Text,
    val description: Text,
    val rule: BadgeRule,
) {
    init {
        require(KEY.matches(key)) { "badge key '$key' must be lowercase words joined by dashes" }
    }

    private companion object {
        val KEY = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}

/** What happened in one delivered order, as far as rewards are concerned. */
data class OrderFacts(
    val orderId: String,
    val userId: String,
    val restaurant: RestaurantRef,
    /** What the customer did not spend: the order's total after any coupon. */
    val totalCents: Long,
    val calories: Int,
    val items: Int,
    val placedAt: Instant,
    /** The local hour (0–23, the shop's time zone) the order was placed in. */
    val placedHour: Int,
    val withDeal: Boolean,
    val couponCode: String?,
    /** Whether the customer had agreed to analytics when ordering (server events only then). */
    val analytics: Boolean,
)

/** What a rule looks at: the member's totals after the award, and the order behind it (if any). */
data class BadgeFacts(
    val member: Member,
    val order: OrderFacts?,
)

sealed interface BadgeRule {
    fun isMetBy(facts: BadgeFacts): Boolean

    data class Orders(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.orders >= min
    }

    data class Restaurants(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.restaurants >= min
    }

    data class StreakDays(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.streak.current >= min
    }

    data class MoneyNotSpent(
        val minCents: Long,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.moneyNotSpentCents >= minCents
    }

    data class CaloriesAvoided(
        val min: Long,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.caloriesAvoided >= min
    }

    data class Level(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.level >= min
    }

    data class StampCards(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.stampCardsCompleted >= min
    }

    data class DealOrders(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.dealOrders >= min
    }

    data class CouponOrders(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = facts.member.couponOrders >= min
    }

    /** One order of at least [minCents]. */
    data class OrderTotal(
        val minCents: Long,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = (facts.order?.totalCents ?: 0) >= minCents
    }

    /** One order of at least [min] items. */
    data class OrderItems(
        val min: Int,
    ) : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = (facts.order?.items ?: 0) >= min
    }

    /** An order placed from [fromHour] up to (not including) [toHour]; may wrap past midnight (22–5). */
    data class PlacedBetween(
        val fromHour: Int,
        val toHour: Int,
    ) : BadgeRule {
        init {
            require(fromHour in 0..23 && toHour in 0..24 && fromHour != toHour) { "hours must be 0–24 and differ" }
        }

        override fun isMetBy(facts: BadgeFacts): Boolean {
            val hour = facts.order?.placedHour ?: return false
            return if (fromHour < toHour) hour in fromHour until toHour else hour >= fromHour || hour < toHour
        }
    }

    /** Only the wheel hands it out. */
    data object WheelPrize : BadgeRule {
        override fun isMetBy(facts: BadgeFacts) = false
    }
}

/** The badges [facts] earn that the member does not have yet. */
fun List<BadgeDefinition>.newlyEarned(
    earned: Set<String>,
    facts: BadgeFacts,
): List<BadgeDefinition> = filter { it.key !in earned && it.rule.isMetBy(facts) }
