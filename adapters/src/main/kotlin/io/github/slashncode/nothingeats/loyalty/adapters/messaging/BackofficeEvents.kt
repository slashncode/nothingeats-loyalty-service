package io.github.slashncode.nothingeats.loyalty.adapters.messaging

import io.github.slashncode.nothingeats.loyalty.adapters.content.BadgePublished
import io.github.slashncode.nothingeats.loyalty.adapters.content.ContentJson
import io.github.slashncode.nothingeats.loyalty.adapters.content.WheelJson
import io.github.slashncode.nothingeats.loyalty.adapters.content.domain
import io.github.slashncode.nothingeats.loyalty.application.PublishBadge
import io.github.slashncode.nothingeats.loyalty.application.PublishWheel
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler

/** Badges as the backoffice publishes them (`backoffice.badge.v1`, nothingeats-backoffice docs/events.md). */
class BackofficeBadgesHandler(
    private val publish: PublishBadge,
) : EventHandler {
    override val topic = "backoffice.badge.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != "nothingeats.backoffice.badge.published.v1") return
        publish(ContentJson.decodeFromJsonElement(BadgePublished.serializer(), event.data).domain())
    }
}

/** The wheel's prizes and odds profiles (`backoffice.wheel.v1`). */
class BackofficeWheelHandler(
    private val publish: PublishWheel,
) : EventHandler {
    override val topic = "backoffice.wheel.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != "nothingeats.backoffice.wheel.published.v1") return
        publish(ContentJson.decodeFromJsonElement(WheelJson.serializer(), event.data).domain())
    }
}
