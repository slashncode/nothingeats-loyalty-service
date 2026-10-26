package io.github.slashncode.nothingeats.loyalty.adapters.grpc

import io.github.slashncode.nothingeats.loyalty.application.PriceCoupon
import io.github.slashncode.nothingeats.loyalty.application.PricedCoupon
import io.github.slashncode.nothingeats.loyalty.application.RedeemCoupon
import io.github.slashncode.nothingeats.loyalty.grpc.v1.LoyaltyServiceGrpcKt
import io.github.slashncode.nothingeats.loyalty.grpc.v1.PriceCouponRequest
import io.github.slashncode.nothingeats.loyalty.grpc.v1.PriceCouponResponse
import io.github.slashncode.nothingeats.loyalty.grpc.v1.RedeemCouponRequest
import io.github.slashncode.nothingeats.loyalty.grpc.v1.RedeemCouponResponse
import io.github.slashncode.nothingeats.loyalty.grpc.v1.coupon
import io.github.slashncode.nothingeats.loyalty.grpc.v1.priceCouponResponse
import io.github.slashncode.nothingeats.loyalty.grpc.v1.redeemCouponResponse
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.grpc.GrpcCaller
import io.github.slashncode.nothingeats.loyalty.domain.CouponCart as DomainCart
import io.github.slashncode.nothingeats.loyalty.grpc.v1.CouponCart as CartMessage

/**
 * `nothingeats.loyalty.v1.LoyaltyService`, called by ordering on the customer's behalf (their
 * token travels along, so a coupon is only ever priced for its owner). Auth and error mapping
 * come from platform-grpc.
 */
class LoyaltyGrpcService(
    private val price: PriceCoupon,
    private val redeem: RedeemCoupon,
) : LoyaltyServiceGrpcKt.LoyaltyServiceCoroutineImplBase() {
    override suspend fun priceCoupon(request: PriceCouponRequest): PriceCouponResponse {
        val priced = price(GrpcCaller.current().id, request.code, request.cart.domain())
        return priceCouponResponse {
            discountCents = priced.discountCents
            coupon = priced.message()
        }
    }

    override suspend fun redeemCoupon(request: RedeemCouponRequest): RedeemCouponResponse {
        if (request.reservationId.isBlank()) {
            throw AppError.Invalid(
                ErrorCode("LOYALTY_INVALID"),
                "A reservation id is required",
                listOf(Violation("reservation_id", "is blank")),
            )
        }
        val priced = redeem(GrpcCaller.current().id, request.code, request.reservationId, request.cart.domain())
        return redeemCouponResponse {
            discountCents = priced.discountCents
            coupon = priced.message()
        }
    }

    private fun CartMessage.domain() = DomainCart(restaurantId, subtotalCents, deliveryFeeCents, unitPricesCentsList)

    private fun PricedCoupon.message() =
        coupon {
            code = this@message.coupon.code
            kind =
                this@message
                    .coupon.terms.kind.name
            value = this@message.coupon.terms.value
            maxDiscountCents = this@message.coupon.terms.maxDiscountCents ?: 0
        }
}
