package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.CouponCart
import io.github.slashncode.nothingeats.loyalty.domain.CouponKind
import io.github.slashncode.nothingeats.loyalty.domain.CouponState
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

private class Loyalty {
    val members = InMemoryMembers()
    val cards = InMemoryStampCards()
    val badges = InMemoryEarnedBadges()
    val coupons = InMemoryCoupons()
    val orders = InMemoryOrders()
    val events = RecordingRewardEvents()
    val analytics = RecordingAnalytics()
    val odds = FixedSpinOdds()
    var clock: Clock = Clock.fixed(Instant.parse("2026-11-02T11:05:00Z"), ZoneOffset.UTC)
    val transactions = DirectTransactions()

    val record get() = RecordPlacedOrder(orders, coupons, transactions, clock)
    val award get() = AwardDelivery(orders, members, cards, badges, coupons, events, transactions, testBadges, analytics, clock = clock)

    fun wheel(roll: Int = 0) =
        SpinWheel(members, coupons, badges, events, transactions, odds, testBadges, analytics, clock = clock, random = FixedRandom(roll))
}

/** A Random whose nextInt(until) always answers [roll]. */
private class FixedRandom(
    private val roll: Int,
) : Random() {
    override fun nextBits(bitCount: Int) = 0

    override fun nextInt(until: Int) = roll
}

private val cart = CouponCart("r-pizza", 2_000, 199, listOf(1_290, 710))

class LoyaltyUseCasesTest :
    FunSpec({
        test("a delivered order earns XP, a stamp and badges once, published with the analytics consent") {
            val loyalty = Loyalty()
            loyalty.record(orderFacts())

            val reward = loyalty.award("o-1").shouldNotBeNull()

            reward.award.xpGained shouldBe 63
            reward.award.badges.map { it.key } shouldContainExactly listOf("first-nothing")
            loyalty.cards.find("u-1", "r-pizza")!!.stamps shouldBe 1
            loyalty.events.events shouldHaveSize 1
            loyalty.analytics.events.map { it.name } shouldContainExactly listOf("xp_earned", "badge_earned")
            loyalty.analytics.events
                .map { it.distinctId }
                .toSet() shouldBe setOf("u-1")

            loyalty.award("o-1").shouldBeNull()
            loyalty.members.find("u-1")!!.xp shouldBe 63
        }

        test("no server events for customers without analytics consent; unknown orders are skipped") {
            val loyalty = Loyalty()
            loyalty.record(orderFacts(analytics = false))

            loyalty.award("o-1").shouldNotBeNull()
            loyalty.award("o-unknown").shouldBeNull()

            loyalty.analytics.events shouldHaveSize 0
            loyalty.events.events
                .single()
                .analytics shouldBe false
        }

        test("the fifth delivery from one restaurant fills the card and issues a free dish there") {
            val loyalty = Loyalty()
            loyalty.cards.save(StampCard("u-1", phantomPizza, 4, 0))
            loyalty.record(orderFacts())

            val reward = loyalty.award("o-1")!!

            reward.award.cardCompleted shouldBe true
            val coupon = reward.coupons.single()
            coupon.terms.kind shouldBe CouponKind.FREE_DISH
            coupon.terms.restaurant shouldBe phantomPizza
            coupon.code.matches(Regex("NE-[2-9A-Z]{4}-[2-9A-Z]{4}")) shouldBe true
            loyalty.coupons.ofUser("u-1") shouldContainExactly listOf(coupon)
        }

        test("a level brings a spin; the wheel's coupon goes to the account, a second spin is refused") {
            val loyalty = Loyalty()
            loyalty.record(orderFacts(totalCents = 9_900))
            loyalty.record(orderFacts(orderId = "o-2", totalCents = 9_900))
            loyalty.award("o-1")
            loyalty.award("o-2")!!.award.spinsGranted shouldBe 1

            val spin = loyalty.wheel(roll = 0)("u-1")

            spin.prize shouldBe Prize.PERCENT_10
            spin.coupon!!.terms shouldBe Prize.PERCENT_10.coupon
            spin.spinsLeft shouldBe 0
            loyalty.coupons.ofUser("u-1") shouldHaveSize 1
            shouldThrow<AppError.Conflict> { loyalty.wheel()("u-1") }.code.value shouldBe "NO_SPINS_LEFT"
        }

        test("the odds come from the experiment only with consent; a second golden fork becomes bonus XP") {
            val loyalty = Loyalty()
            loyalty.members.save(loyalty.members.lock("u-1").copy(spinsAvailable = 3))
            loyalty.odds.weights = mapOf(Prize.GOLDEN_FORK to 1)

            loyalty.wheel()("u-1").prize shouldBe Prize.GOLDEN_FORK
            withContext(AnalyticsContext("u-1", "s-1")) { loyalty.wheel()("u-1") }.prize shouldBe Prize.BONUS_XP

            loyalty.odds.askedFor shouldContainExactly listOf(null, "u-1")
            loyalty.badges.ofUser("u-1").map { it.key } shouldContainExactly listOf("golden-fork", "level-2")
            loyalty.members.find("u-1")!!.xp shouldBe 100
            loyalty.events.events.map { it.analytics } shouldContainExactly listOf(false, true)
            loyalty.analytics.events.map { it.name } shouldContainExactly listOf("wheel_spun", "xp_earned", "level_reached", "badge_earned")
        }

        test("a coupon is priced, reserved for one checkout, redeemed by the placed order") {
            val loyalty = Loyalty()
            loyalty.members.save(loyalty.members.lock("u-1").copy(spinsAvailable = 1))
            // Default weights: rolls 55–74 are free delivery.
            val code = loyalty.wheel(roll = 60)("u-1").coupon!!.code
            val price = PriceCoupon(loyalty.coupons, loyalty.clock)
            val redeem = RedeemCoupon(loyalty.coupons, loyalty.transactions, Duration.ofMinutes(15), loyalty.clock)

            price("u-1", code.lowercase(), cart).discountCents shouldBe 199
            shouldThrow<AppError.NotFound> { price("u-2", code, cart) }
            redeem("u-1", code, "key-1", cart).discountCents shouldBe 199
            redeem("u-1", code, "key-1", cart).discountCents shouldBe 199
            shouldThrow<AppError.Conflict> { redeem("u-1", code, "key-2", cart) }.code.value shouldBe "COUPON_IN_USE"

            loyalty.record(orderFacts(couponCode = code))

            (loyalty.coupons.findByCode(code)!!.state as CouponState.Redeemed).orderId shouldBe "o-1"
            shouldThrow<AppError.Conflict> { price("u-1", code, cart) }.code.value shouldBe "COUPON_USED"
            ListCoupons(loyalty.coupons, loyalty.clock)("u-1") shouldHaveSize 0
        }

        test("the rewards page shows level progress, today's streak and earned badges first") {
            val loyalty = Loyalty()
            loyalty.record(orderFacts())
            loyalty.award("o-1")

            val overview = ViewRewards(loyalty.members, loyalty.cards, loyalty.badges, loyalty.coupons, testBadges, loyalty.clock)("u-1")

            overview.member.level shouldBe 1
            overview.levelStartXp shouldBe 0
            overview.nextLevelXp shouldBe 100
            overview.streakDays shouldBe 1
            overview.orderedToday shouldBe true
            overview.badges.map { it.definition.key to (it.earnedAt != null) } shouldContainExactly
                listOf("first-nothing" to true, "level-2" to false, "golden-fork" to false)

            ForgetMember(
                loyalty.members,
                loyalty.cards,
                loyalty.badges,
                loyalty.coupons,
                loyalty.orders,
                loyalty.transactions,
            )("u-1") shouldBe
                4
            loyalty.members.find("u-1").shouldBeNull()
        }
    })
