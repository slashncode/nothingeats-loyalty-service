package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.BadgeRule
import io.github.slashncode.nothingeats.loyalty.domain.BadgeTier
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.RestaurantRef
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.loyalty.domain.Text
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryMembers : Members {
    val all = ConcurrentHashMap<String, Member>()

    override suspend fun find(userId: String) = all[userId]

    override suspend fun lock(userId: String) = all.getOrPut(userId) { Member.new(userId) }

    override suspend fun save(member: Member) {
        all[member.userId] = member
    }

    override suspend fun delete(userId: String) = if (all.remove(userId) != null) 1 else 0
}

class InMemoryStampCards : StampCards {
    val all = ConcurrentHashMap<Pair<String, String>, StampCard>()

    override suspend fun find(
        userId: String,
        restaurantId: String,
    ) = all[userId to restaurantId]

    override suspend fun save(card: StampCard) {
        all[card.userId to card.restaurant.id] = card
    }

    override suspend fun ofUser(userId: String) = all.values.filter { it.userId == userId }

    override suspend fun deleteByUser(userId: String): Int {
        val keys = all.keys.filter { it.first == userId }
        keys.forEach { all.remove(it) }
        return keys.size
    }
}

class InMemoryEarnedBadges : EarnedBadges {
    val all = ConcurrentHashMap<String, MutableList<EarnedBadge>>()

    override suspend fun ofUser(userId: String) = all[userId].orEmpty().toList()

    override suspend fun add(
        userId: String,
        keys: List<String>,
        at: Instant,
    ) {
        val list = all.getOrPut(userId) { CopyOnWriteArrayList() }
        keys.filter { key -> list.none { it.key == key } }.forEach { list += EarnedBadge(it, at) }
    }

    override suspend fun deleteByUser(userId: String) = all.remove(userId)?.size ?: 0
}

class InMemoryCoupons : Coupons {
    val all = ConcurrentHashMap<String, Coupon>()

    override suspend fun insert(coupon: Coupon) {
        all[coupon.code] = coupon
    }

    override suspend fun findByCode(code: String) = all[code]

    override suspend fun lockByCode(code: String) = all[code]

    override suspend fun update(coupon: Coupon) {
        all[coupon.code] = coupon
    }

    override suspend fun ofUser(userId: String) = all.values.filter { it.userId == userId }

    override suspend fun deleteByUser(userId: String): Int {
        val codes = all.values.filter { it.userId == userId }.map { it.code }
        codes.forEach { all.remove(it) }
        return codes.size
    }
}

class InMemoryOrders : Orders {
    val all = ConcurrentHashMap<String, KnownOrder>()

    override suspend fun insert(order: OrderFacts) = all.putIfAbsent(order.orderId, KnownOrder(order, null)) == null

    override suspend fun find(orderId: String) = all[orderId]

    override suspend fun markAwarded(
        orderId: String,
        at: Instant,
    ) {
        all.computeIfPresent(orderId) { _, known -> known.copy(awardedAt = at) }
    }

    override suspend fun deleteByUser(userId: String): Int {
        val ids = all.values.filter { it.facts.userId == userId }.map { it.facts.orderId }
        ids.forEach { all.remove(it) }
        return ids.size
    }
}

class RecordingRewardEvents : RewardEvents {
    val events = CopyOnWriteArrayList<RewardEarned>()

    override suspend fun earned(reward: RewardEarned) {
        events += reward
    }
}

class FixedSpinOdds(
    var weights: Map<Prize, Int> = Prize.DEFAULT_WEIGHTS,
) : SpinOdds {
    val askedFor = CopyOnWriteArrayList<String?>()

    override suspend fun weights(distinctId: String?): Map<Prize, Int> {
        askedFor += distinctId
        return weights
    }
}

class DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

val phantomPizza = RestaurantRef("r-pizza", "phantom-pizza", "Phantom Pizza")

fun badge(
    key: String,
    rule: BadgeRule,
) = BadgeDefinition(key, BadgeTier.BRONZE, Text(key, key), Text(key, key), rule)

val testBadges =
    listOf(
        badge("first-nothing", BadgeRule.Orders(1)),
        badge("level-2", BadgeRule.Level(2)),
        badge("golden-fork", BadgeRule.WheelPrize),
    )

fun orderFacts(
    orderId: String = "o-1",
    userId: String = "u-1",
    totalCents: Long = 2_380,
    placedAt: Instant = Instant.parse("2026-11-02T11:00:00Z"),
    couponCode: String? = null,
    analytics: Boolean = true,
    restaurant: RestaurantRef = phantomPizza,
) = OrderFacts(orderId, userId, restaurant, totalCents, 1_640, 2, placedAt, 12, false, couponCode, analytics)
