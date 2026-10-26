package io.github.slashncode.nothingeats.loyalty.adapters.badges

import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.BadgeRule
import io.github.slashncode.nothingeats.loyalty.domain.BadgeTier
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.Text
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TextJson(
    val de: String,
    val en: String,
)

@Serializable
private sealed interface RuleJson {
    @Serializable
    @SerialName("orders")
    data class Orders(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("restaurants")
    data class Restaurants(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("streak")
    data class Streak(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("moneyNotSpent")
    data class MoneyNotSpent(
        val minCents: Long,
    ) : RuleJson

    @Serializable
    @SerialName("caloriesAvoided")
    data class CaloriesAvoided(
        val min: Long,
    ) : RuleJson

    @Serializable
    @SerialName("level")
    data class Level(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("stampCards")
    data class StampCards(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("dealOrders")
    data class DealOrders(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("couponOrders")
    data class CouponOrders(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("orderTotal")
    data class OrderTotal(
        val minCents: Long,
    ) : RuleJson

    @Serializable
    @SerialName("orderItems")
    data class OrderItems(
        val min: Int,
    ) : RuleJson

    @Serializable
    @SerialName("placedBetween")
    data class PlacedBetween(
        val fromHour: Int,
        val toHour: Int,
    ) : RuleJson

    @Serializable
    @SerialName("wheelPrize")
    data object WheelPrize : RuleJson
}

@Serializable
private data class BadgeJson(
    val key: String,
    val tier: BadgeTier,
    val name: TextJson,
    val description: TextJson,
    val rule: RuleJson,
)

private fun RuleJson.domain(): BadgeRule =
    when (this) {
        is RuleJson.Orders -> BadgeRule.Orders(min)
        is RuleJson.Restaurants -> BadgeRule.Restaurants(min)
        is RuleJson.Streak -> BadgeRule.StreakDays(min)
        is RuleJson.MoneyNotSpent -> BadgeRule.MoneyNotSpent(minCents)
        is RuleJson.CaloriesAvoided -> BadgeRule.CaloriesAvoided(min)
        is RuleJson.Level -> BadgeRule.Level(min)
        is RuleJson.StampCards -> BadgeRule.StampCards(min)
        is RuleJson.DealOrders -> BadgeRule.DealOrders(min)
        is RuleJson.CouponOrders -> BadgeRule.CouponOrders(min)
        is RuleJson.OrderTotal -> BadgeRule.OrderTotal(minCents)
        is RuleJson.OrderItems -> BadgeRule.OrderItems(min)
        is RuleJson.PlacedBetween -> BadgeRule.PlacedBetween(fromHour, toHour)
        RuleJson.WheelPrize -> BadgeRule.WheelPrize
    }

/**
 * The badges, read from `badges.json` on the classpath: adding a badge is a data change, and a
 * broken file stops the service at startup instead of failing quietly at the first award.
 */
object BadgeCatalog {
    private val json = Json { classDiscriminator = "type" }

    fun load(resource: String = "/badges.json"): List<BadgeDefinition> {
        val text = BadgeCatalog::class.java.getResource(resource)?.readText() ?: error("$resource is missing")
        val badges =
            json.decodeFromString<List<BadgeJson>>(text).map {
                BadgeDefinition(it.key, it.tier, Text(it.name.de, it.name.en), Text(it.description.de, it.description.en), it.rule.domain())
            }
        val duplicates = badges.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "duplicate badge keys: $duplicates" }
        check(badges.any { it.key == Prize.GOLDEN_FORK_BADGE && it.rule == BadgeRule.WheelPrize }) {
            "the wheel's '${Prize.GOLDEN_FORK_BADGE}' badge must be defined with the wheelPrize rule"
        }
        return badges
    }
}
