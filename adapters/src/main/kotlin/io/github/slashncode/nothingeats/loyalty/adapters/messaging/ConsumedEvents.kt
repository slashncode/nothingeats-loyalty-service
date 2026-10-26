package io.github.slashncode.nothingeats.loyalty.adapters.messaging

import io.github.slashncode.nothingeats.loyalty.application.AwardDelivery
import io.github.slashncode.nothingeats.loyalty.application.ForgetMember
import io.github.slashncode.nothingeats.loyalty.application.RecordPlacedOrder
import io.github.slashncode.nothingeats.loyalty.application.SHOP_ZONE
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.RestaurantRef
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

// Tolerant readers of other services' contracts: only the fields loyalty needs, new ones defaulted.

@Serializable
internal data class PlacedRestaurant(
    val id: String,
    val slug: String,
    val name: String,
)

@Serializable
internal data class PlacedLine(
    val quantity: Int,
    val unitPriceCents: Long,
    /** Set when a flash deal made the dish cheaper than its menu price. */
    val regularUnitPriceCents: Long? = null,
)

@Serializable
internal data class PlacedCoupon(
    val code: String,
)

@Serializable
internal data class OrderPlaced(
    val orderId: String,
    val userId: String,
    val placedAt: String,
    val restaurant: PlacedRestaurant,
    val lines: List<PlacedLine>,
    val totalCents: Long,
    val calories: Int,
    val coupon: PlacedCoupon? = null,
    /** Older events lack it: no consent known means no server-side analytics. */
    val analytics: Boolean = false,
)

@Serializable
internal data class OrderDelivered(
    val orderId: String,
)

/**
 * `ordering.order.v1` (ordering-service docs/events.md). Both events of an order share its key,
 * so `delivered` always comes after `placed`.
 */
class OrderEventsHandler(
    private val record: RecordPlacedOrder,
    private val award: AwardDelivery,
) : EventHandler {
    override val topic = "ordering.order.v1"

    override suspend fun handle(event: CloudEvent) {
        when (event.type) {
            PLACED -> record(event.decode(OrderPlaced.serializer()).facts())
            DELIVERED -> award(event.decode(OrderDelivered.serializer()).orderId)
        }
    }

    private fun OrderPlaced.facts(): OrderFacts {
        val placed = Instant.parse(placedAt)
        return OrderFacts(
            orderId = orderId,
            userId = userId,
            restaurant = RestaurantRef(restaurant.id, restaurant.slug, restaurant.name),
            totalCents = totalCents,
            calories = calories,
            items = lines.sumOf { it.quantity },
            placedAt = placed,
            placedHour = placed.atZone(SHOP_ZONE).hour,
            withDeal = lines.any { (it.regularUnitPriceCents ?: 0) > it.unitPriceCents },
            couponCode = coupon?.code,
            analytics = analytics,
        )
    }

    private companion object {
        const val PLACED = "nothingeats.ordering.order.placed.v1"
        const val DELIVERED = "nothingeats.ordering.order.delivered.v1"
    }
}

@Serializable
internal data class UserDeleted(
    val userId: String,
)

/** `user.deleted` (record 030): the loyalty account and everything attached to it go. */
class IdentityEventsHandler(
    private val forget: ForgetMember,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        if (event.type != DELETED) return
        log.info("Account deleted: {} loyalty rows erased", forget(event.decode(UserDeleted.serializer()).userId))
    }

    private companion object {
        const val DELETED = "nothingeats.identity.user.deleted.v1"
    }
}
