package io.github.slashncode.nothingeats.loyalty.adapters.badges

import io.github.slashncode.nothingeats.loyalty.adapters.odds.PostHogSpinOdds
import io.github.slashncode.nothingeats.loyalty.domain.BadgeRule
import io.github.slashncode.nothingeats.loyalty.domain.PrizeReward
import io.github.slashncode.nothingeats.loyalty.domain.Wheel
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class BadgeCatalogTest :
    FunSpec({
        test("the badge file is valid: unique keys, both languages, the wheel's golden fork") {
            val badges = BadgeCatalog.load()

            badges.size shouldBe 20
            badges.map { it.key } shouldContain "night-owl"
            badges.single { it.key == "night-owl" }.rule shouldBe BadgeRule.PlacedBetween(22, 5)
            badges.forEach {
                it.name.de.isNotBlank() shouldBe true
                it.name.en.isNotBlank() shouldBe true
                it.description.de.isNotBlank() shouldBe true
                it.description.en.isNotBlank() shouldBe true
            }
        }

        test("the flag only names the odds profile: its variant, nothing without consent or flag") {
            val analytics = RecordingAnalytics()
            val odds = PostHogSpinOdds(analytics)

            odds.profile("u-1") shouldBe null
            analytics.flags[PostHogSpinOdds.FLAG] = "generous"
            odds.profile(null) shouldBe null
            odds.profile("u-1") shouldBe "generous"
            analytics.flags[PostHogSpinOdds.FLAG] = true
            odds.profile("u-1") shouldBe null
        }

        test("the bundled badges know every badge the default wheel hands out") {
            val badges = BadgeCatalog.load()
            Wheel.DEFAULT.prizes
                .mapNotNull { (it.reward as? PrizeReward.Badge)?.badgeKey }
                .forEach { key -> badges.single { it.key == key }.rule shouldBe BadgeRule.WheelPrize }
        }
    })
