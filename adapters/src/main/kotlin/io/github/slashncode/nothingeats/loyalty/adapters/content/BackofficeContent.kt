package io.github.slashncode.nothingeats.loyalty.adapters.content

import io.github.slashncode.nothingeats.loyalty.adapters.badges.RuleJson
import io.github.slashncode.nothingeats.loyalty.adapters.badges.domain
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.BadgeTier
import io.github.slashncode.nothingeats.loyalty.domain.CouponKind
import io.github.slashncode.nothingeats.loyalty.domain.CouponSource
import io.github.slashncode.nothingeats.loyalty.domain.CouponTerms
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.PrizeReward
import io.github.slashncode.nothingeats.loyalty.domain.Text
import io.github.slashncode.nothingeats.loyalty.domain.Wheel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The backoffice's badge and wheel snapshots (nothingeats-backoffice docs/events.md). The wheel is
// stored in the same shape, so what loyalty keeps is exactly what was published.

@Serializable
internal data class ContentText(
    val de: String,
    val en: String,
)

@Serializable
internal data class BadgePublished(
    val key: String,
    val tier: BadgeTier,
    val name: ContentText,
    val description: ContentText,
    val rule: RuleJson,
    val position: Int = 0,
    val active: Boolean = true,
)

@Serializable
internal data class PrizeJson(
    val key: String,
    val kind: String,
    val label: ContentText,
    val value: Long = 0,
    val maxDiscountCents: Long? = null,
    val minimumOrderCents: Long = 0,
    val xp: Int? = null,
    val badgeKey: String? = null,
)

@Serializable
internal data class ProfileJson(
    val name: String,
    val weights: Map<String, Int>,
)

@Serializable
internal data class WheelJson(
    val prizes: List<PrizeJson>,
    val profiles: List<ProfileJson>,
)

internal fun BadgePublished.domain() =
    BadgeDefinition(key, tier, Text(name.de, name.en), Text(description.de, description.en), rule.domain(), position, active)

private fun PrizeJson.reward(): PrizeReward =
    when (kind) {
        "BONUS_XP" -> PrizeReward.BonusXp(requireNotNull(xp) { "prize $key needs xp" })
        "BADGE" -> PrizeReward.Badge(requireNotNull(badgeKey) { "prize $key needs a badgeKey" })
        else ->
            PrizeReward.Coupon(
                CouponTerms(CouponKind.valueOf(kind), value, maxDiscountCents, minimumOrderCents, null, CouponSource.WHEEL),
            )
    }

internal fun WheelJson.domain() =
    Wheel(
        prizes = prizes.map { Prize(it.key, it.reward(), Text(it.label.de, it.label.en)) },
        profiles = profiles.associate { it.name to it.weights },
    )

internal fun Wheel.json() =
    WheelJson(
        prizes =
            prizes.map { prize ->
                val text = ContentText(prize.label.de, prize.label.en)
                when (val reward = prize.reward) {
                    is PrizeReward.Coupon ->
                        PrizeJson(
                            prize.key,
                            reward.terms.kind.name,
                            text,
                            reward.terms.value,
                            reward.terms.maxDiscountCents,
                            reward.terms.minimumOrderCents,
                        )
                    is PrizeReward.BonusXp -> PrizeJson(prize.key, "BONUS_XP", text, xp = reward.xp)
                    is PrizeReward.Badge -> PrizeJson(prize.key, "BADGE", text, badgeKey = reward.badgeKey)
                }
            },
        profiles = profiles.map { (name, weights) -> ProfileJson(name, weights) },
    )

/** Tolerant reader: the events carry more than loyalty needs (e.g. `badgeId`). */
internal val ContentJson =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }
