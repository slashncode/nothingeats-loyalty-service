package io.github.slashncode.nothingeats.loyalty.adapters.odds

import io.github.slashncode.nothingeats.loyalty.application.SpinOdds
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The wheel's odds from the PostHog flag [flag]: its payload (per variant, for an experiment) is
 * `{"weights": {"PERCENT_10": 25, …}}`. Prizes it leaves out never come up; a missing flag, a
 * broken payload or no distinct id (no analytics consent) means the default odds.
 */
class PostHogSpinOdds(
    private val analytics: ProductAnalytics,
    private val flag: String = FLAG,
) : SpinOdds {
    private val log = LoggerFactory.getLogger(PostHogSpinOdds::class.java)

    override suspend fun weights(distinctId: String?): Map<Prize, Int> {
        if (distinctId == null) return Prize.DEFAULT_WEIGHTS
        val payload =
            try {
                analytics.featureFlagPayload(flag, distinctId)
            } catch (e: Exception) {
                log.warn("Could not read the wheel's odds from flag {}; using the defaults", flag, e)
                null
            } ?: return Prize.DEFAULT_WEIGHTS
        return parse(payload) ?: Prize.DEFAULT_WEIGHTS.also { log.warn("Flag {} has an unusable payload; using the default odds", flag) }
    }

    internal fun parse(payload: String): Map<Prize, Int>? =
        runCatching {
            val weights =
                Json
                    .parseToJsonElement(payload)
                    .jsonObject
                    .getValue("weights")
                    .jsonObject
            weights.mapKeys { (key, _) -> Prize.valueOf(key) }.mapValues { (_, value) -> value.jsonPrimitive.int }
        }.getOrNull()?.takeIf { weights -> weights.values.all { it >= 0 } && weights.values.sum() > 0 }

    companion object {
        const val FLAG = "spin-wheel-odds"
    }
}
