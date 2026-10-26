package io.github.slashncode.nothingeats.loyalty.app

import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.CouponState
import io.github.slashncode.nothingeats.loyalty.domain.Prize
import io.github.slashncode.nothingeats.loyalty.grpc.v1.LoyaltyServiceGrpcKt
import io.github.slashncode.nothingeats.loyalty.grpc.v1.couponCart
import io.github.slashncode.nothingeats.loyalty.grpc.v1.priceCouponRequest
import io.github.slashncode.nothingeats.loyalty.grpc.v1.redeemCouponRequest
import io.github.slashncode.nothingeats.platform.grpc.GrpcChannel
import io.github.slashncode.nothingeats.platform.grpc.GrpcClientConfig
import io.github.slashncode.nothingeats.platform.grpc.GrpcServerConfig
import io.github.slashncode.nothingeats.platform.grpc.errorCode
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.grpc.StatusException
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

private const val USER = "ghost-1"
private const val RESTAURANT = "r-pizza"

private fun event(
    type: String,
    subject: String,
    data: JsonObject,
) = CloudEvent(
    id = UUID.randomUUID().toString(),
    source = "/test",
    type = type,
    subject = subject,
    time = "2026-11-02T11:00:00Z",
    data = data,
)

private fun Producer<String, ByteArray>.publish(
    topic: String,
    event: CloudEvent,
) {
    send(ProducerRecord(topic, event.subject, EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray())).get()
}

private fun placed(
    orderId: String,
    totalCents: Long,
    coupon: String? = null,
) = event(
    "nothingeats.ordering.order.placed.v1",
    orderId,
    buildJsonObject {
        put("orderId", orderId)
        put("userId", USER)
        put("placedAt", "2026-11-02T11:00:00Z")
        putJsonObject("restaurant") {
            put("id", RESTAURANT)
            put("slug", "phantom-pizza")
            put("name", "Phantom Pizza")
        }
        put(
            "lines",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("quantity", 2)
                        put("unitPriceCents", 900)
                        put("regularUnitPriceCents", 1290)
                    },
                )
            },
        )
        put("totalCents", totalCents)
        put("calories", 1640)
        coupon?.let { putJsonObject("coupon") { put("code", it) } }
        put("analytics", true)
    },
)

private fun delivered(orderId: String) =
    event("nothingeats.ordering.order.delivered.v1", orderId, buildJsonObject { put("orderId", orderId) })

private fun rewardEvents(): List<CloudEvent> =
    KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KafkaTestCluster.bootstrapServers,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        ByteArrayDeserializer(),
    ).use { consumer ->
        val partitions = consumer.partitionsFor("loyalty.reward.v1").map { TopicPartition("loyalty.reward.v1", it.partition()) }
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val end = consumer.endOffsets(partitions)
        val events = mutableListOf<CloudEvent>()
        while (partitions.any { consumer.position(it) < end.getValue(it) }) {
            consumer.poll(Duration.ofMillis(200)).forEach {
                events +=
                    EventJson.decodeFromString(CloudEvent.serializer(), it.value().decodeToString())
            }
        }
        events
    }

/** Orders in, rewards out, the wheel and coupons over HTTP and gRPC — through the real composition root. */
class LoyaltyFlowTest :
    FunSpec({
        test("delivered orders earn XP, stamps, a spin; coupons are priced, reserved and redeemed; deletion erases all") {
            KafkaTestCluster.createTopics("ordering.order.v1", "identity.user.v1", "loyalty.reward.v1", "loyalty-service.dlq")
            val tokens = TestTokens()
            val port = freePort()
            val grpcPort = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    grpc = GrpcServerConfig(port = grpcPort),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config, tokens.jwtAuth)
            service.migrations.migrate()
            service.lifecycle.start()
            val channel = GrpcChannel(GrpcClientConfig(target = "localhost:$grpcPort", deadline = 5.seconds, maxAttempts = 1))
            val http = HttpClient.newHttpClient()
            val token = tokens.accessToken(userId = USER, locale = "de")
            val base = "http://localhost:$port/api/loyalty/v1"

            fun call(
                method: String,
                path: String,
            ): HttpResponse<String> =
                http.send(
                    HttpRequest
                        .newBuilder(URI("$base$path"))
                        .header("Authorization", "Bearer $token")
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                    BodyHandlers.ofString(),
                )

            fun json(response: HttpResponse<String>) = Json.parseToJsonElement(response.body()).jsonObject

            fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

            try {
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish("ordering.order.v1", placed("o-1", 9_900))
                    producer.publish("ordering.order.v1", delivered("o-1"))
                    producer.publish("ordering.order.v1", placed("o-2", 9_900))
                    producer.publish("ordering.order.v1", delivered("o-2"))
                    // A repeated delivery must not count twice.
                    producer.publish("ordering.order.v1", delivered("o-2"))
                }

                eventually(30.seconds) {
                    val me = json(call("GET", "/me"))
                    me.getValue("level").jsonObject.text("xp") shouldBe "165"
                    me.getValue("level").jsonObject.text("level") shouldBe "2"
                    me.text("spinsAvailable") shouldBe "1"
                    me
                        .getValue("stampCards")
                        .jsonArray
                        .single()
                        .jsonObject
                        .text("stamps") shouldBe "2"
                }
                val me = call("GET", "/me?lang=en").body()
                me shouldContain "Deal hunter"
                eventually(20.seconds) {
                    val rewards = rewardEvents().filter { it.subject == USER }
                    rewards.size shouldBe 2
                    rewards
                        .last()
                        .data.jsonObject
                        .text("spinsGranted") shouldBe "1"
                }

                val spin = call("POST", "/spins")
                spin.statusCode() shouldBe 200
                Prize.entries.map { it.name } shouldContain json(spin).text("prize")

                // A coupon of the customer's, priced and reserved over gRPC as ordering does it.
                val now = Instant.now()
                val coupon =
                    Coupon(
                        "8a3f6c2e-0000-4000-8000-000000000001",
                        "NE-TEST-2345",
                        USER,
                        Prize.AMOUNT_5.coupon!!,
                        now,
                        now + Coupon.VALIDITY,
                        CouponState.Active,
                    )
                service.coupons.insert(coupon)
                val stub = LoyaltyServiceGrpcKt.LoyaltyServiceCoroutineStub(channel.channel)
                val cart =
                    couponCart {
                        restaurantId = RESTAURANT
                        subtotalCents = 2_500
                        deliveryFeeCents = 199
                        unitPricesCents += listOf(1_290L, 1_210L)
                    }
                channel
                    .prepare(stub, token)
                    .priceCoupon(
                        priceCouponRequest {
                            code = "ne-test-2345"
                            this.cart = cart
                        },
                    ).discountCents shouldBe 500
                val stranger = tokens.accessToken(userId = "ghost-2")
                shouldThrow<StatusException> {
                    channel.prepare(stub, stranger).priceCoupon(
                        priceCouponRequest {
                            code = "NE-TEST-2345"
                            this.cart = cart
                        },
                    )
                }.errorCode() shouldBe "COUPON_NOT_FOUND"
                channel
                    .prepare(stub, token)
                    .redeemCoupon(
                        redeemCouponRequest {
                            code = "NE-TEST-2345"
                            reservationId = "checkout-1"
                            this.cart = cart
                        },
                    ).discountCents shouldBe 500

                KafkaClients
                    .producer(
                        config.kafka,
                        "test",
                    ).use { it.publish("ordering.order.v1", placed("o-3", 2_399, coupon = "NE-TEST-2345")) }
                eventually(20.seconds) {
                    (service.coupons.findByCode("NE-TEST-2345")!!.state as? CouponState.Redeemed)?.orderId shouldBe "o-3"
                }

                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(
                        "identity.user.v1",
                        event("nothingeats.identity.user.deleted.v1", USER, buildJsonObject { put("userId", USER) }),
                    )
                }
                eventually(20.seconds) { service.members.find(USER) shouldBe null }
            } finally {
                channel.stop()
                service.lifecycle.stop()
            }
        }

        test("refuses to serve before migrations ran") {
            val config =
                ServiceConfig(
                    database = PostgresTestDatabases.create(),
                    lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config, TestTokens().jwtAuth)

            shouldThrow<IllegalStateException> { service.lifecycle.start() }.message shouldContain "'migrate' mode"
        }
    })
