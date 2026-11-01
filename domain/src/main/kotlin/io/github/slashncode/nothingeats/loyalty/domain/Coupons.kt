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

/** What a segment of the wheel gives. */
sealed interface PrizeReward {
    data class Coupon(
        val terms: CouponTerms,
    ) : PrizeReward {
        init {
            require(terms.source == CouponSource.WHEEL) { "a wheel coupon comes from the wheel" }
        }
    }

    data class BonusXp(
        val xp: Int,
    ) : PrizeReward {
        init {
            require(xp > 0) { "bonus XP must be positive" }
        }
    }

    /** A badge; who has it already gets bonus XP instead. */
    data class Badge(
        val badgeKey: String,
    ) : PrizeReward
}

/** A segment of the wheel: its key (stable, e.g. `PERCENT_10`), what it gives, its label. */
data class Prize(
    val key: String,
    val reward: PrizeReward,
    val label: Text,
) {
    init {
        require(KEY.matches(key)) { "prize key '$key' must be UPPER_SNAKE_CASE" }
    }

    val coupon: CouponTerms? get() = (reward as? PrizeReward.Coupon)?.terms

    private companion object {
        val KEY = Regex("^[A-Z0-9]+(_[A-Z0-9]+)*$")
    }
}

/**
 * The wheel of fortune as the backoffice publishes it (`backoffice.wheel.v1`): prizes in the order of
 * the segments and named odds profiles (weights by prize key). [DEFAULT_PROFILE] applies to
 * everyone; an experiment's variant picks another profile by its name.
 */
data class Wheel(
    val prizes: List<Prize>,
    val profiles: Map<String, Map<String, Int>>,
) {
    init {
        require(prizes.isNotEmpty()) { "a wheel needs prizes" }
        require(prizes.map { it.key }.toSet().size == prizes.size) { "prize keys must be unique" }
        require(DEFAULT_PROFILE in profiles) { "a wheel needs the '$DEFAULT_PROFILE' profile" }
        profiles.forEach { (name, weights) ->
            require(weights.values.all { it >= 0 } && weights.values.sum() > 0) { "profile '$name' needs a weight above 0" }
            require(weights.keys.all { key -> prizes.any { it.key == key } }) { "profile '$name' weighs a prize the wheel does not have" }
        }
    }

    fun prize(key: String): Prize = prizes.first { it.key == key }

    /** The odds of [profile], or the default ones when the wheel has no such profile; prizes without weight never come up. */
    fun weights(profile: String?): List<Pair<Prize, Int>> {
        val weights = profiles[profile] ?: profiles.getValue(DEFAULT_PROFILE)
        return prizes.map { it to (weights[it.key] ?: 0) }.filter { it.second > 0 }
    }

    /** The wheel's bonus-XP prize, if it has one: a badge prize someone already has turns into it (or into [FALLBACK_XP]). */
    val bonusXp: Prize? get() = prizes.firstOrNull { it.reward is PrizeReward.BonusXp }

    companion object {
        const val DEFAULT_PROFILE = "default"
        const val FALLBACK_XP = 100

        /** A prize by weight, in segment order; [roll] is uniform in `0 until` the sum of the weights. */
        fun pick(
            weights: List<Pair<Prize, Int>>,
            roll: Int,
        ): Prize {
            var rest = roll
            for ((prize, weight) in weights) {
                if (weight <= 0) continue
                if (rest < weight) return prize
                rest -= weight
            }
            throw IllegalArgumentException("roll $roll is outside the weights ${weights.sumOf { it.second }}")
        }

        private fun wheelCoupon(
            kind: CouponKind,
            value: Long,
            maxDiscountCents: Long?,
            minimumOrderCents: Long,
        ) = PrizeReward.Coupon(CouponTerms(kind, value, maxDiscountCents, minimumOrderCents, null, CouponSource.WHEEL))

        /** Until the backoffice has published a wheel: the prizes and odds from before phase 6. */
        val DEFAULT =
            Wheel(
                prizes =
                    listOf(
                        Prize("PERCENT_10", wheelCoupon(CouponKind.PERCENT, 10, null, 1500), Text("10 %", "10%")),
                        Prize("FREE_DELIVERY", wheelCoupon(CouponKind.FREE_DELIVERY, 0, null, 0), Text("Lieferung", "Delivery")),
                        Prize("AMOUNT_5", wheelCoupon(CouponKind.AMOUNT, 500, null, 2000), Text("5 €", "€5")),
                        Prize("BONUS_XP", PrizeReward.BonusXp(100), Text("+100 XP", "+100 XP")),
                        Prize("PERCENT_20", wheelCoupon(CouponKind.PERCENT, 20, 1000, 2500), Text("20 %", "20%")),
                        Prize("FREE_DISH", wheelCoupon(CouponKind.FREE_DISH, 0, 1200, 0), Text("Gericht", "Dish")),
                        Prize("GOLDEN_FORK", PrizeReward.Badge("golden-fork"), Text("Gabel", "Fork")),
                    ),
                profiles =
                    mapOf(
                        DEFAULT_PROFILE to
                            mapOf(
                                "PERCENT_10" to 25,
                                "PERCENT_20" to 10,
                                "AMOUNT_5" to 20,
                                "FREE_DELIVERY" to 20,
                                "FREE_DISH" to 10,
                                "BONUS_XP" to 12,
                                "GOLDEN_FORK" to 3,
                            ),
                    ),
            )
    }
}

/** A full stamp card: one dish free at that restaurant, up to 15 €. */
fun stampCardCoupon(restaurant: RestaurantRef) =
    CouponTerms(CouponKind.FREE_DISH, 0, STAMP_CARD_MAX_CENTS, 0, restaurant, CouponSource.STAMP_CARD)

private const val STAMP_CARD_MAX_CENTS = 1500L
