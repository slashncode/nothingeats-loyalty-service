package io.github.slashncode.nothingeats.loyalty.domain

import java.time.Duration
import java.time.Instant

enum class CouponKind { PERCENT, AMOUNT, FREE_DELIVERY, FREE_DISH }

enum class CouponSource { STAMP_CARD, WHEEL }

/** What a coupon is worth and where it applies; the coupon itself adds code, owner and state. */
data class CouponTerms(
    val kind: CouponKind,
    /** Percent for [CouponKind.PERCENT], cents for [CouponKind.AMOUNT], 0 otherwise. */
    val value: Long,
    /** The most it takes off; null = no limit. */
    val maxDiscountCents: Long?,
    val minimumOrderCents: Long,
    /** Only at this restaurant; null = anywhere. */
    val restaurant: RestaurantRef?,
    val source: CouponSource,
) {
    init {
        when (kind) {
            CouponKind.PERCENT -> require(value in 1..100) { "a percentage must be 1–100" }
            CouponKind.AMOUNT -> require(value > 0) { "an amount must be positive" }
            CouponKind.FREE_DELIVERY, CouponKind.FREE_DISH -> require(value == 0L) { "$kind has no value" }
        }
    }

    /** What these terms take off [cart]; never more than the cart costs. */
    fun discountFor(cart: CouponCart): Long {
        val raw =
            when (kind) {
                CouponKind.PERCENT -> cart.subtotalCents * value / 100
                CouponKind.AMOUNT -> value
                CouponKind.FREE_DELIVERY -> cart.deliveryFeeCents
                CouponKind.FREE_DISH -> cart.unitPricesCents.maxOrNull() ?: 0
            }
        val capped = maxDiscountCents?.let { minOf(raw, it) } ?: raw
        val limit = if (kind == CouponKind.FREE_DELIVERY) cart.deliveryFeeCents else cart.subtotalCents
        return capped.coerceIn(0, limit)
    }
}

/** The part of a cart a coupon cares about. */
data class CouponCart(
    val restaurantId: String,
    val subtotalCents: Long,
    val deliveryFeeCents: Long,
    val unitPricesCents: List<Long>,
)

sealed interface CouponState {
    data object Active : CouponState

    /** Held for one checkout until [until]; after that it is free again unless the order confirmed it. */
    data class Reserved(
        val reservationId: String,
        val until: Instant,
    ) : CouponState

    data class Redeemed(
        val orderId: String,
        val at: Instant,
    ) : CouponState
}

enum class CouponRefusal { USED, EXPIRED, IN_USE, OTHER_RESTAURANT, MINIMUM_NOT_REACHED }

class CouponRefused(
    val reason: CouponRefusal,
) : Exception("Coupon refused: $reason")

data class Coupon(
    val id: String,
    val code: String,
    val userId: String,
    val terms: CouponTerms,
    val createdAt: Instant,
    val expiresAt: Instant,
    val state: CouponState,
) {
    /** Can be put in a cart at [now]: not used, not expired, not held by another checkout. */
    fun isOpenAt(now: Instant): Boolean = check(now, null) == null

    /**
     * The discount for [cart], or [CouponRefused] with the reason. A reservation under
     * [reservationId] is the caller's own and does not block.
     */
    fun price(
        cart: CouponCart,
        now: Instant,
        reservationId: String? = null,
    ): Long {
        check(now, reservationId)?.let { throw CouponRefused(it) }
        val restaurant = terms.restaurant
        if (restaurant != null && restaurant.id != cart.restaurantId) throw CouponRefused(CouponRefusal.OTHER_RESTAURANT)
        if (cart.subtotalCents < terms.minimumOrderCents) throw CouponRefused(CouponRefusal.MINIMUM_NOT_REACHED)
        return terms.discountFor(cart)
    }

    /** Holds the coupon for one checkout; the same reservation again just extends the hold. */
    fun reserve(
        cart: CouponCart,
        reservationId: String,
        now: Instant,
        hold: Duration,
    ): Pair<Coupon, Long> {
        val discount = price(cart, now, reservationId)
        return copy(state = CouponState.Reserved(reservationId, now + hold)) to discount
    }

    /** The order went through: used for good. Null when it already was (a repeated event). */
    fun redeem(
        orderId: String,
        now: Instant,
    ): Coupon? = if (state is CouponState.Redeemed) null else copy(state = CouponState.Redeemed(orderId, now))

    private fun check(
        now: Instant,
        reservationId: String?,
    ): CouponRefusal? {
        val state = state
        return when {
            state is CouponState.Redeemed -> CouponRefusal.USED
            !now.isBefore(expiresAt) -> CouponRefusal.EXPIRED
            state is CouponState.Reserved && state.reservationId != reservationId && now.isBefore(state.until) -> CouponRefusal.IN_USE
            else -> null
        }
    }

    companion object {
        val VALIDITY: Duration = Duration.ofDays(30)
    }
}

/** The wheel's segments; what each one gives is fixed here, how often each comes up is not. */
enum class Prize(
    val coupon: CouponTerms?,
) {
    PERCENT_10(CouponTerms(CouponKind.PERCENT, 10, null, 1500, null, CouponSource.WHEEL)),
    PERCENT_20(CouponTerms(CouponKind.PERCENT, 20, 1000, 2500, null, CouponSource.WHEEL)),
    AMOUNT_5(CouponTerms(CouponKind.AMOUNT, 500, null, 2000, null, CouponSource.WHEEL)),
    FREE_DELIVERY(CouponTerms(CouponKind.FREE_DELIVERY, 0, null, 0, null, CouponSource.WHEEL)),
    FREE_DISH(CouponTerms(CouponKind.FREE_DISH, 0, 1200, 0, null, CouponSource.WHEEL)),
    BONUS_XP(null),
    GOLDEN_FORK(null),
    ;

    companion object {
        const val BONUS_XP_AMOUNT = 100
        const val GOLDEN_FORK_BADGE = "golden-fork"

        /** The odds when no experiment says otherwise: coupons mostly, the rare badge rarely. */
        val DEFAULT_WEIGHTS: Map<Prize, Int> =
            mapOf(
                PERCENT_10 to 25,
                PERCENT_20 to 10,
                AMOUNT_5 to 20,
                FREE_DELIVERY to 20,
                FREE_DISH to 10,
                BONUS_XP to 12,
                GOLDEN_FORK to 3,
            )

        /** A prize by weight; [roll] is uniform in `0 until` the sum of the weights. */
        fun pick(
            weights: Map<Prize, Int>,
            roll: Int,
        ): Prize {
            var rest = roll
            for ((prize, weight) in weights.entries.sortedBy { it.key.ordinal }) {
                if (weight <= 0) continue
                if (rest < weight) return prize
                rest -= weight
            }
            throw IllegalArgumentException("roll $roll is outside the weights ${weights.values.sum()}")
        }
    }
}

/** A full stamp card: one dish free at that restaurant, up to 15 €. */
fun stampCardCoupon(restaurant: RestaurantRef) =
    CouponTerms(CouponKind.FREE_DISH, 0, STAMP_CARD_MAX_CENTS, 0, restaurant, CouponSource.STAMP_CARD)

private const val STAMP_CARD_MAX_CENTS = 1500L
