package io.github.slashncode.nothingeats.loyalty.app

import io.github.slashncode.nothingeats.platform.grpc.GrpcServerConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.persistence.PostgresConfig
import io.github.slashncode.nothingeats.platform.posthog.PostHogSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the service reads from its environment, validated at startup. Required:
 * `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`. In the cluster also `JWT__JWKS_URL`
 * and `KAFKA__BOOTSTRAP_SERVERS`. With `POSTHOG__PERSONAL_API_KEY` the wheel's odds flag is
 * evaluated locally; without it, one request to PostHog per spin (with analytics consent only).
 */
data class ServiceConfig(
    val http: HttpServerConfig = HttpServerConfig(),
    val grpc: GrpcServerConfig = GrpcServerConfig(),
    val database: PostgresConfig,
    val kafka: KafkaConfig = KafkaConfig(),
    val jwt: JwtConfig = JwtConfig(),
    val posthog: PostHogSettings = PostHogSettings(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

data class LifecycleConfig(
    /** Time between readiness going down and the shutdown, so load balancers stop sending traffic first. */
    val drainDelay: Duration = 5.seconds,
)
