package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.Award
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import java.time.Instant

interface Members {
    suspend fun find(userId: String): Member?

    /**
     * The member, created if missing, locked until the transaction ends: two orders of the same
     * customer, handled on two pods at once, must not overwrite each other's XP. Call inside
     * [Transactions.run].
     */
    suspend fun lock(userId: String): Member

    suspend fun save(member: Member)

    suspend fun delete(userId: String): Int
}

interface StampCards {
    suspend fun find(
        userId: String,
        restaurantId: String,
    ): StampCard?

    suspend fun save(card: StampCard)

    suspend fun ofUser(userId: String): List<StampCard>

    suspend fun deleteByUser(userId: String): Int
}

data class EarnedBadge(
    val key: String,
    val at: Instant,
)

interface EarnedBadges {
    suspend fun ofUser(userId: String): List<EarnedBadge>

    suspend fun add(
        userId: String,
        keys: List<String>,
        at: Instant,
    )

    suspend fun deleteByUser(userId: String): Int
}

interface Coupons {
    suspend fun insert(coupon: Coupon)

    suspend fun findByCode(code: String): Coupon?

    /** Locked until the transaction ends, for a reservation. Call inside [Transactions.run]. */
    suspend fun lockByCode(code: String): Coupon?

    suspend fun update(coupon: Coupon)

    suspend fun ofUser(userId: String): List<Coupon>

    suspend fun deleteByUser(userId: String): Int
}

data class KnownOrder(
    val facts: OrderFacts,
    val awardedAt: Instant?,
)

/** Placed orders, remembered until their delivery is rewarded. */
interface Orders {
    /** False when the order is known already (a repeated event). */
    suspend fun insert(order: OrderFacts): Boolean

    suspend fun find(orderId: String): KnownOrder?

    suspend fun markAwarded(
        orderId: String,
        at: Instant,
    )

    suspend fun deleteByUser(userId: String): Int
}

enum class RewardCause { ORDER_DELIVERED, WHEEL }

/** Published on `loyalty.reward.v1` for the live stream and the leaderboard. */
data class RewardEarned(
    val userId: String,
    val cause: RewardCause,
    val orderId: String?,
    val award: Award,
    val coupons: List<Coupon>,
    val analytics: Boolean,
)

/** `loyalty.reward.v1` through the outbox: call inside the transaction that stores the award. */
interface RewardEvents {
    suspend fun earned(reward: RewardEarned)
}

/** How often each prize comes up for this person: an experiment's payload, or the default. */
interface SpinOdds {
    suspend fun weights(distinctId: String?): Map<Prize, Int>
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
