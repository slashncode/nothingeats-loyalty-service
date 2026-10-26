package io.github.slashncode.nothingeats.loyalty.adapters.badges

import io.github.slashncode.nothingeats.loyalty.adapters.odds.PostHogSpinOdds
import io.github.slashncode.nothingeats.loyalty.domain.BadgeRule
import io.github.slashncode.nothingeats.loyalty.domain.Prize
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

        test("the wheel's odds come from the flag payload, with the defaults for anything unusable") {
            val analytics = RecordingAnalytics()
            val odds = PostHogSpinOdds(analytics)

            odds.weights(null) shouldBe Prize.DEFAULT_WEIGHTS
            odds.weights("u-1") shouldBe Prize.DEFAULT_WEIGHTS

            analytics.payloads[PostHogSpinOdds.FLAG] = """{"weights":{"FREE_DISH":5,"GOLDEN_FORK":1}}"""
            odds.weights("u-1") shouldBe mapOf(Prize.FREE_DISH to 5, Prize.GOLDEN_FORK to 1)

            analytics.payloads[PostHogSpinOdds.FLAG] = """{"weights":{"FREE_PIZZA":5}}"""
            odds.weights("u-1") shouldBe Prize.DEFAULT_WEIGHTS
            analytics.payloads[PostHogSpinOdds.FLAG] = """{"weights":{"FREE_DISH":0}}"""
            odds.weights("u-1") shouldBe Prize.DEFAULT_WEIGHTS
        }
    })
