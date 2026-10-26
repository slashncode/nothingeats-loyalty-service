package io.github.slashncode.nothingeats.loyalty.application

import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.CouponCart
import io.github.slashncode.nothingeats.loyalty.domain.CouponRefusal
import io.github.slashncode.nothingeats.loyalty.domain.CouponRefused
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.time.Clock
import java.time.Duration

data class PricedCoupon(
    val coupon: Coupon,
    val discountCents: Long,
)

/** Codes are typed by people: case and surrounding spaces do not matter. */
internal fun normalize(code: String) = code.trim().uppercase()

private fun notFound() = AppError.NotFound(ErrorCode("COUPON_NOT_FOUND"), "There is no such coupon")

private fun CouponRefused.toAppError(): AppError =
    when (reason) {
        CouponRefusal.USED -> AppError.Conflict(ErrorCode("COUPON_USED"), "This coupon was used already")
        CouponRefusal.EXPIRED -> AppError.Conflict(ErrorCode("COUPON_EXPIRED"), "This coupon has expired")
        CouponRefusal.IN_USE -> AppError.Conflict(ErrorCode("COUPON_IN_USE"), "This coupon is held by another checkout right now")
        CouponRefusal.OTHER_RESTAURANT ->
            AppError.Invalid(
                ErrorCode("COUPON_OTHER_RESTAURANT"),
                "This coupon is for another restaurant",
                listOf(Violation("code", "for another restaurant")),
            )
        CouponRefusal.MINIMUM_NOT_REACHED ->
            AppError.Invalid(
                ErrorCode("COUPON_MINIMUM_NOT_REACHED"),
                "The cart is below this coupon's minimum order",
                listOf(Violation("code", "minimum order not reached")),
            )
    }

/** What a coupon takes off a cart (gRPC, for ordering's cart). Someone else's coupon does not exist. */
class PriceCoupon(
    private val coupons: Coupons,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        userId: String,
        code: String,
        cart: CouponCart,
    ): PricedCoupon {
        val coupon = coupons.findByCode(normalize(code))?.takeIf { it.userId == userId } ?: throw notFound()
        return try {
            PricedCoupon(coupon, coupon.price(cart, clock.instant()))
        } catch (e: CouponRefused) {
            throw e.toAppError()
        }
    }
}

/**
 * Holds a coupon for one checkout (gRPC, ordering's `POST /orders`). The reservation id is the
 * checkout's idempotency key, so a retried checkout gets the same answer. `order placed` redeems
 * it for good; if the order never comes, the hold lapses after [hold].
 */
class RedeemCoupon(
    private val coupons: Coupons,
    private val transactions: Transactions,
    private val hold: Duration = Duration.ofMinutes(15),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        userId: String,
        code: String,
        reservationId: String,
        cart: CouponCart,
    ): PricedCoupon =
        transactions.run {
            val coupon = coupons.lockByCode(normalize(code))?.takeIf { it.userId == userId } ?: throw notFound()
            val (reserved, discount) =
                try {
                    coupon.reserve(cart, reservationId, clock.instant(), hold)
                } catch (e: CouponRefused) {
                    throw e.toAppError()
                }
            coupons.update(reserved)
            PricedCoupon(reserved, discount)
        }
}
