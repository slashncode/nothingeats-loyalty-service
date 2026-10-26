package io.github.slashncode.nothingeats.loyalty.app

import io.github.slashncode.nothingeats.loyalty.adapters.badges.BadgeCatalog
import io.github.slashncode.nothingeats.loyalty.adapters.grpc.LoyaltyGrpcService
import io.github.slashncode.nothingeats.loyalty.adapters.http.LoyaltyRoutes
import io.github.slashncode.nothingeats.loyalty.adapters.messaging.IdentityEventsHandler
import io.github.slashncode.nothingeats.loyalty.adapters.messaging.LoyaltyTopics
import io.github.slashncode.nothingeats.loyalty.adapters.messaging.OrderEventsHandler
import io.github.slashncode.nothingeats.loyalty.adapters.messaging.OutboxRewardEvents
import io.github.slashncode.nothingeats.loyalty.adapters.odds.PostHogSpinOdds
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedCoupons
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedEarnedBadges
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedMembers
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedOrders
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedStampCards
import io.github.slashncode.nothingeats.loyalty.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.loyalty.application.AwardDelivery
import io.github.slashncode.nothingeats.loyalty.application.Coupons
import io.github.slashncode.nothingeats.loyalty.application.EarnedBadges
import io.github.slashncode.nothingeats.loyalty.application.ExportRewards
import io.github.slashncode.nothingeats.loyalty.application.ForgetMember
import io.github.slashncode.nothingeats.loyalty.application.ListCoupons
import io.github.slashncode.nothingeats.loyalty.application.Members
import io.github.slashncode.nothingeats.loyalty.application.Orders
import io.github.slashncode.nothingeats.loyalty.application.PriceCoupon
import io.github.slashncode.nothingeats.loyalty.application.RecordPlacedOrder
import io.github.slashncode.nothingeats.loyalty.application.RedeemCoupon
import io.github.slashncode.nothingeats.loyalty.application.RewardEvents
import io.github.slashncode.nothingeats.loyalty.application.SpinOdds
import io.github.slashncode.nothingeats.loyalty.application.SpinWheel
import io.github.slashncode.nothingeats.loyalty.application.StampCards
import io.github.slashncode.nothingeats.loyalty.application.Transactions
import io.github.slashncode.nothingeats.loyalty.application.ViewRewards
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.grpc.AuthInterceptor
import io.github.slashncode.nothingeats.platform.grpc.ErrorInterceptor
import io.github.slashncode.nothingeats.platform.grpc.GrpcServer
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.kafka.OutboxRelay
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.grpc.BindableService
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes, gRPC services, event handlers and cron tasks are
 * collected with `@IntoSet`. [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val members: Members
    abstract val coupons: Coupons

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun grpcServer(
        services: Set<BindableService>,
        auth: JwtAuth,
    ): GrpcServer = GrpcServer(config.grpc, services, AuthInterceptor(auth), ErrorInterceptor())

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @IntoSet
    @Provides
    fun grpcLifecycle(server: GrpcServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Messaging

    @ServiceScope
    @Provides
    fun outbox(clock: Clock): Outbox = Outbox(source = LoyaltyTopics.SOURCE, clock = clock)

    @ServiceScope
    @Provides
    fun outboxRelay(postgres: PostgresDatabase): OutboxRelay = OutboxRelay(postgres, KafkaClients.producer(config.kafka, "loyalty-service"))

    @IntoSet
    @Provides
    fun outboxRelayLifecycle(relay: OutboxRelay): LifecycleComponent = relay

    @ServiceScope
    @Provides
    fun eventConsumer(
        handlers: Set<EventHandler>,
        postgres: PostgresDatabase,
    ): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "loyalty-service"),
            handlers = handlers,
            db = postgres,
            consumerFactory = { KafkaClients.consumer(config.kafka, "loyalty-service") },
            deadLetters = KafkaClients.producer(config.kafka, "loyalty-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Loyalty

    @ServiceScope
    @Provides
    fun badgeDefinitions(): List<BadgeDefinition> = BadgeCatalog.load()

    @Provides
    fun members(postgres: PostgresDatabase): Members = ExposedMembers(postgres)

    @Provides
    fun stampCards(postgres: PostgresDatabase): StampCards = ExposedStampCards(postgres)

    @Provides
    fun earnedBadges(postgres: PostgresDatabase): EarnedBadges = ExposedEarnedBadges(postgres)

    @Provides
    fun coupons(postgres: PostgresDatabase): Coupons = ExposedCoupons(postgres)

    @Provides
    fun orders(postgres: PostgresDatabase): Orders = ExposedOrders(postgres)

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @Provides
    fun rewardEvents(outbox: Outbox): RewardEvents = OutboxRewardEvents(outbox)

    @Provides
    fun spinOdds(analytics: ProductAnalytics): SpinOdds = PostHogSpinOdds(analytics)

    @IntoSet
    @Provides
    fun loyaltyRoutes(
        members: Members,
        cards: StampCards,
        badges: EarnedBadges,
        coupons: Coupons,
        events: RewardEvents,
        transactions: Transactions,
        odds: SpinOdds,
        definitions: List<BadgeDefinition>,
        analytics: ProductAnalytics,
        clock: Clock,
    ): HttpRoutes =
        LoyaltyRoutes(
            viewRewards = ViewRewards(members, cards, badges, coupons, definitions, clock),
            listCoupons = ListCoupons(coupons, clock),
            spinWheel = SpinWheel(members, coupons, badges, events, transactions, odds, definitions, analytics, clock = clock),
            exportRewards = ExportRewards(members, cards, badges, coupons),
        )

    @IntoSet
    @Provides
    fun loyaltyGrpc(
        coupons: Coupons,
        transactions: Transactions,
        clock: Clock,
    ): BindableService = LoyaltyGrpcService(PriceCoupon(coupons, clock), RedeemCoupon(coupons, transactions, clock = clock))

    @IntoSet
    @Provides
    fun orderEvents(
        orders: Orders,
        members: Members,
        cards: StampCards,
        badges: EarnedBadges,
        coupons: Coupons,
        events: RewardEvents,
        transactions: Transactions,
        definitions: List<BadgeDefinition>,
        analytics: ProductAnalytics,
        clock: Clock,
    ): EventHandler =
        OrderEventsHandler(
            RecordPlacedOrder(orders, coupons, transactions, clock),
            AwardDelivery(orders, members, cards, badges, coupons, events, transactions, definitions, analytics, clock = clock),
        )

    @IntoSet
    @Provides
    fun identityEvents(
        members: Members,
        cards: StampCards,
        badges: EarnedBadges,
        coupons: Coupons,
        orders: Orders,
        transactions: Transactions,
    ): EventHandler = IdentityEventsHandler(ForgetMember(members, cards, badges, coupons, orders, transactions))
}
