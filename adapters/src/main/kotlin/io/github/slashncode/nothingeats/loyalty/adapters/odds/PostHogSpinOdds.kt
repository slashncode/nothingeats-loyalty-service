package io.github.slashncode.nothingeats.loyalty.adapters.odds

import io.github.slashncode.nothingeats.loyalty.application.SpinOdds
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import org.slf4j.LoggerFactory

/**
 * The odds profile from the PostHog flag [flag]: its variant (an experiment's arm, e.g. `generous`)
 * names a profile of the wheel the backoffice published. The weights themselves live in the
 * backoffice now, not in the flag's payload. No distinct id (no analytics consent), a missing flag
 * or a failure mean the default profile.
 */
class PostHogSpinOdds(
    private val analytics: ProductAnalytics,
    private val flag: String = FLAG,
) : SpinOdds {
    private val log = LoggerFactory.getLogger(PostHogSpinOdds::class.java)

    override suspend fun profile(distinctId: String?): String? {
        if (distinctId == null) return null
        return try {
            analytics.featureFlag(flag, distinctId) as? String
        } catch (e: Exception) {
            log.warn("Could not read the wheel's profile from flag {}; using the default odds", flag, e)
            null
        }
    }

    companion object {
        const val FLAG = "spin-wheel-odds"
    }
}
