package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.BadgeRule
import io.github.slashncode.nothingeats.loyalty.domain.BadgeTier
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.RestaurantRef
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.loyalty.domain.Text
import io.github.slashncode.nothingeats.loyalty.domain.Wheel
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

/** The experiment's variant this person gets; null: the default profile. */
class FixedSpinOdds(
    var profile: String? = null,
) : SpinOdds {
    val askedFor = CopyOnWriteArrayList<String?>()

    override suspend fun profile(distinctId: String?): String? {
        askedFor += distinctId
        return profile.takeIf { distinctId != null }
    }
}

class InMemoryBadgeDefinitions(
    initial: List<BadgeDefinition> = testBadges,
) : BadgeDefinitions {
    private val all = ConcurrentHashMap(initial.associateBy { it.key })

    override suspend fun all() = all.values.sortedBy { it.position }

    override suspend fun save(definition: BadgeDefinition) {
        all[definition.key] = definition
    }
}

class InMemoryWheels(
    var wheel: Wheel? = null,
) : Wheels {
    override suspend fun current() = wheel

    override suspend fun save(wheel: Wheel) {
        this.wheel = wheel
    }
}

class DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

val phantomPizza = RestaurantRef("r-pizza", "phantom-pizza", "Phantom Pizza")

fun badge(
    key: String,
    rule: BadgeRule,
    position: Int = 0,
) = BadgeDefinition(key, BadgeTier.BRONZE, Text(key, key), Text(key, key), rule, position)

val testBadges =
    listOf(
        badge("first-nothing", BadgeRule.Orders(1), 0),
        badge("level-2", BadgeRule.Level(2), 1),
        badge("golden-fork", BadgeRule.WheelPrize, 2),
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
