package io.github.slashncode.nothingeats.loyalty.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private val pizza = RestaurantRef("r-pizza", "phantom-pizza", "Phantom Pizza")
private val noon = Instant.parse("2026-11-02T11:00:00Z")

private fun order(
    totalCents: Long = 2_380,
    hour: Int = 12,
    items: Int = 2,
) = OrderFacts("o-1", "u-1", pizza, totalCents, 1_640, items, noon, hour, withDeal = false, couponCode = null, analytics = true)

private val badges =
    listOf(
        BadgeDefinition("first-nothing", BadgeTier.BRONZE, Text("Erstes Nichts", "First nothing"), Text("", ""), BadgeRule.Orders(1)),
        BadgeDefinition("night-owl", BadgeTier.SILVER, Text("Nachteule", "Night owl"), Text("", ""), BadgeRule.PlacedBetween(22, 5)),
        BadgeDefinition("level-2", BadgeTier.BRONZE, Text("Level 2", "Level 2"), Text("", ""), BadgeRule.Level(2)),
    )

private fun cart(
    restaurantId: String = "r-pizza",
    subtotal: Long = 2_000,
    fee: Long = 199,
    prices: List<Long> = listOf(1_290, 710),
) = CouponCart(restaurantId, subtotal, fee, prices)

private fun terms(prize: Prize) = checkNotNull(prize.coupon)

private fun coupon(terms: CouponTerms) = Coupon("c-1", "NE-ABCD-EFGH", "u-1", terms, noon, noon + Coupon.VALIDITY, CouponState.Active)

class LoyaltyDomainTest :
    FunSpec({
        test("levels start at 0, 100, 250, 450 XP") {
            (1..4).map { Levels.startOf(it) } shouldContainExactly listOf(0L, 100L, 250L, 450L)
            Levels.of(0) shouldBe 1
            Levels.of(99) shouldBe 1
            Levels.of(100) shouldBe 2
            Levels.of(449) shouldBe 3
        }

        test("a streak counts days in a row and breaks after a day without an order") {
            val monday = LocalDate.of(2026, 11, 2)
            val streak =
                Streak.NONE
                    .record(monday)
                    .record(monday)
                    .record(monday.plusDays(1))
                    .record(monday.plusDays(2))

            streak shouldBe Streak(3, 3, monday.plusDays(2))
            streak.currentOn(monday.plusDays(3)) shouldBe 3
            streak.currentOn(monday.plusDays(4)) shouldBe 0
            streak.record(monday) shouldBe streak
            streak.record(monday.plusDays(5)) shouldBe Streak(1, 3, monday.plusDays(5))
        }

        test("the fifth stamp fills the card and starts a new one") {
            val card = (1..5).fold(StampCard.empty("u-1", pizza)) { card, _ -> card.stamp() }

            card.stamps shouldBe 0
            card.completed shouldBe 1
        }

        test("a first order: base XP, XP for the money not spent, a new restaurant, a level and a spin") {
            val award =
                Rewards.forDelivery(
                    Member.new("u-1"),
                    StampCard.empty("u-1", pizza),
                    order(),
                    LocalDate.of(2026, 11, 2),
                    badges,
                    emptySet(),
                )

            award.xp shouldContainExactly
                listOf(XpLine(XpReason.ORDER, 25), XpLine(XpReason.MONEY_NOT_SPENT, 23), XpLine(XpReason.NEW_RESTAURANT, 15))
            award.member.xp shouldBe 63
            award.levelUp shouldBe false
            award.badges.map { it.key } shouldContainExactly listOf("first-nothing")
            award.member.restaurants shouldBe 1

            val second =
                Rewards.forDelivery(
                    award.member,
                    award.card!!,
                    order(totalCents = 9_900),
                    LocalDate.of(2026, 11, 3),
                    badges,
                    setOf("first-nothing"),
                )
            second.xp shouldContainExactly
                listOf(XpLine(XpReason.ORDER, 25), XpLine(XpReason.MONEY_NOT_SPENT, 50), XpLine(XpReason.STREAK, 5))
            second.levelUp shouldBe true
            second.spinsGranted shouldBe 1
            second.member.spinsAvailable shouldBe 1
            second.badges.map { it.key } shouldContainExactly listOf("level-2")
        }

        test("night owls are counted across midnight") {
            val award = { hour: Int ->
                Rewards.forDelivery(
                    Member.new("u-1"),
                    StampCard.empty("u-1", pizza),
                    order(hour = hour),
                    LocalDate.of(2026, 11, 2),
                    badges,
                    setOf("first-nothing"),
                )
            }
            award(23).badges.map { it.key } shouldContainExactly listOf("night-owl")
            award(4).badges.map { it.key } shouldContainExactly listOf("night-owl")
            award(5).badges.map { it.key } shouldContainExactly emptyList()
        }

        test("a full stamp card brings a free dish at that restaurant") {
            val almost = StampCard("u-1", pizza, 4, 0)
            val award = Rewards.forDelivery(Member.new("u-1"), almost, order(), LocalDate.of(2026, 11, 2), badges, emptySet())

            award.cardCompleted shouldBe true
            award.coupons shouldContainExactly listOf(stampCardCoupon(pizza))
            award.member.stampCardsCompleted shouldBe 1
        }

        test("coupons take off what they promise, never more than the cart costs") {
            coupon(terms(Prize.PERCENT_10)).price(cart(), noon) shouldBe 200
            coupon(terms(Prize.PERCENT_20)).price(cart(subtotal = 9_000), noon) shouldBe 1_000
            coupon(terms(Prize.AMOUNT_5)).price(cart(), noon) shouldBe 500
            coupon(terms(Prize.FREE_DELIVERY)).price(cart(), noon) shouldBe 199
            coupon(terms(Prize.FREE_DISH)).price(cart(), noon) shouldBe 1_200
            coupon(stampCardCoupon(pizza)).price(cart(), noon) shouldBe 1_290
            coupon(terms(Prize.AMOUNT_5).copy(minimumOrderCents = 0)).price(cart(subtotal = 300, prices = listOf(300)), noon) shouldBe 300
        }

        test("coupons refuse other restaurants, small carts, expiry, reuse and someone else's checkout") {
            val reason = { coupon: Coupon, cart: CouponCart, at: Instant ->
                shouldThrow<CouponRefused> { coupon.price(cart, at, "checkout-2") }.reason
            }
            reason(coupon(stampCardCoupon(pizza)), cart(restaurantId = "r-burger"), noon) shouldBe CouponRefusal.OTHER_RESTAURANT
            reason(coupon(terms(Prize.PERCENT_10)), cart(subtotal = 1_000), noon) shouldBe CouponRefusal.MINIMUM_NOT_REACHED
            reason(coupon(terms(Prize.PERCENT_10)), cart(), noon + Coupon.VALIDITY) shouldBe CouponRefusal.EXPIRED

            val (reserved, discount) = coupon(terms(Prize.AMOUNT_5)).reserve(cart(), "checkout-1", noon, Duration.ofMinutes(15))
            discount shouldBe 500
            reason(reserved, cart(), noon.plusSeconds(60)) shouldBe CouponRefusal.IN_USE
            reserved.price(cart(), noon.plusSeconds(60), "checkout-1") shouldBe 500
            reserved.price(cart(), noon.plusSeconds(16 * 60), "checkout-2") shouldBe 500

            val redeemed = reserved.redeem("o-1", noon.plusSeconds(5))!!
            reason(redeemed, cart(), noon.plusSeconds(10)) shouldBe CouponRefusal.USED
            redeemed.redeem("o-1", noon.plusSeconds(20)) shouldBe null
        }

        test("the wheel picks by weight") {
            val weights = mapOf(Prize.PERCENT_10 to 2, Prize.GOLDEN_FORK to 1)
            Prize.pick(weights, 0) shouldBe Prize.PERCENT_10
            Prize.pick(weights, 1) shouldBe Prize.PERCENT_10
            Prize.pick(weights, 2) shouldBe Prize.GOLDEN_FORK
            shouldThrow<IllegalArgumentException> { Prize.pick(weights, 3) }
        }
    })
