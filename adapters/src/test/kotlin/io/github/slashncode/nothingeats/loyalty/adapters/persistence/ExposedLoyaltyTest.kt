package io.github.slashncode.nothingeats.loyalty.adapters.persistence

import io.github.slashncode.nothingeats.loyalty.application.orderFacts
import io.github.slashncode.nothingeats.loyalty.application.phantomPizza
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.CouponCart
import io.github.slashncode.nothingeats.loyalty.domain.CouponState
import io.github.slashncode.nothingeats.loyalty.domain.Streak
import io.github.slashncode.nothingeats.loyalty.domain.stampCardCoupon
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class ExposedLoyaltyTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE)).migrate()
            database = PostgresDatabase(config).also { it.start() }
        }

        afterTest { database.stop() }

        test("members are created on first lock and round-trip with their streak") {
            val members = ExposedMembers(database)
            val transactions = ExposedTransactions(database)

            val fresh = transactions.run { members.lock("u-1") }
            fresh.xp shouldBe 0
            members.save(fresh.copy(xp = 165, spinsAvailable = 1, streak = Streak(2, 3, LocalDate.of(2026, 11, 2))))

            members.find("u-1")!!.level shouldBe 2
            members.find("u-1")!!.streak shouldBe Streak(2, 3, LocalDate.of(2026, 11, 2))
        }

        test("a locked member makes the second transaction wait") {
            val members = ExposedMembers(database)
            val transactions = ExposedTransactions(database)
            transactions.run { members.lock("u-1") }
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            coroutineScope {
                val first =
                    async {
                        transactions.run {
                            members.lock("u-1")
                            held.complete(Unit)
                            release.await()
                        }
                    }
                held.await()
                val second = async { transactions.run { members.lock("u-1") } }
                withTimeoutOrNull(500) { second.await() } shouldBe null
                release.complete(Unit)
                first.await()
                second.await().userId shouldBe "u-1"
            }
        }

        test("coupons keep their state through reservation and redemption") {
            val coupons = ExposedCoupons(database)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val coupon =
                Coupon(
                    UUID.randomUUID().toString(),
                    "NE-ABCD-EFGH",
                    "u-1",
                    stampCardCoupon(phantomPizza),
                    now,
                    now + Coupon.VALIDITY,
                    CouponState.Active,
                )
            coupons.insert(coupon)
            coupons.findByCode("NE-ABCD-EFGH") shouldBe coupon

            val cart = CouponCart(phantomPizza.id, 2_000, 199, listOf(1_290))
            val (reserved, discount) = coupon.reserve(cart, "key-1", now, Duration.ofMinutes(15))
            discount shouldBe 1_290
            coupons.update(reserved)
            coupons.findByCode("NE-ABCD-EFGH") shouldBe reserved

            val redeemed = reserved.redeem("o-1", now)!!
            coupons.update(redeemed)
            coupons.ofUser("u-1") shouldContainExactly listOf(redeemed)
            coupons.deleteByUser("u-1") shouldBe 1
        }

        test("orders are remembered once and marked when rewarded; badges are earned once") {
            val orders = ExposedOrders(database)
            val badges = ExposedEarnedBadges(database)
            val order = orderFacts(placedAt = Instant.parse("2026-11-02T11:00:00Z"), couponCode = "NE-ABCD-EFGH")

            orders.insert(order) shouldBe true
            orders.insert(order) shouldBe false
            orders.find("o-1")!!.facts shouldBe order
            orders.markAwarded("o-1", Instant.parse("2026-11-02T11:03:00Z"))
            orders.find("o-1")!!.awardedAt shouldBe Instant.parse("2026-11-02T11:03:00Z")

            badges.add("u-1", listOf("first-nothing"), Instant.parse("2026-11-02T11:03:00Z"))
            badges.add("u-1", listOf("first-nothing", "night-owl"), Instant.parse("2026-11-03T11:03:00Z"))
            badges.ofUser("u-1").associate { it.key to it.at } shouldBe
                mapOf("first-nothing" to Instant.parse("2026-11-02T11:03:00Z"), "night-owl" to Instant.parse("2026-11-03T11:03:00Z"))
        }
    })
