package io.github.slashncode.nothingeats.loyalty.adapters.persistence

import io.github.slashncode.nothingeats.loyalty.application.Coupons
import io.github.slashncode.nothingeats.loyalty.application.EarnedBadge
import io.github.slashncode.nothingeats.loyalty.application.EarnedBadges
import io.github.slashncode.nothingeats.loyalty.application.KnownOrder
import io.github.slashncode.nothingeats.loyalty.application.Members
import io.github.slashncode.nothingeats.loyalty.application.Orders
import io.github.slashncode.nothingeats.loyalty.application.StampCards
import io.github.slashncode.nothingeats.loyalty.application.Transactions
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.CouponKind
import io.github.slashncode.nothingeats.loyalty.domain.CouponSource
import io.github.slashncode.nothingeats.loyalty.domain.CouponState
import io.github.slashncode.nothingeats.loyalty.domain.CouponTerms
import io.github.slashncode.nothingeats.loyalty.domain.Member
import io.github.slashncode.nothingeats.loyalty.domain.OrderFacts
import io.github.slashncode.nothingeats.loyalty.domain.RestaurantRef
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.loyalty.domain.Streak
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import kotlin.uuid.Uuid

internal object MembersTable : Table("members") {
    val userId = text("user_id")
    val xp = long("xp")
    val spinsAvailable = integer("spins_available")
    val orders = integer("orders")
    val moneyNotSpentCents = long("money_not_spent_cents")
    val caloriesAvoided = long("calories_avoided")
    val restaurants = integer("restaurants")
    val dealOrders = integer("deal_orders")
    val couponOrders = integer("coupon_orders")
    val stampCardsCompleted = integer("stamp_cards_completed")
    val streakCurrent = integer("streak_current")
    val streakLongest = integer("streak_longest")
    val streakLastDay = date("streak_last_day").nullable()

    override val primaryKey = PrimaryKey(userId)
}

internal object StampCardsTable : Table("stamp_cards") {
    val userId = text("user_id")
    val restaurantId = text("restaurant_id")
    val restaurantSlug = text("restaurant_slug")
    val restaurantName = text("restaurant_name")
    val stamps = integer("stamps")
    val completed = integer("completed")

    override val primaryKey = PrimaryKey(userId, restaurantId)
}

internal object EarnedBadgesTable : Table("earned_badges") {
    val userId = text("user_id")
    val badge = text("badge")
    val earnedAt = timestamp("earned_at")

    override val primaryKey = PrimaryKey(userId, badge)
}

internal object CouponsTable : Table("coupons") {
    val id = uuid("id")
    val code = text("code")
    val userId = text("user_id")
    val kind = text("kind")
    val value = long("value")
    val maxDiscountCents = long("max_discount_cents").nullable()
    val minimumOrderCents = long("minimum_order_cents")
    val restaurantId = text("restaurant_id").nullable()
    val restaurantSlug = text("restaurant_slug").nullable()
    val restaurantName = text("restaurant_name").nullable()
    val couponSource = text("source")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val state = text("state")
    val reservationId = text("reservation_id").nullable()
    val reservedUntil = timestamp("reserved_until").nullable()
    val orderId = text("order_id").nullable()
    val redeemedAt = timestamp("redeemed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object OrdersTable : Table("orders") {
    val orderId = text("order_id")
    val userId = text("user_id")
    val restaurantId = text("restaurant_id")
    val restaurantSlug = text("restaurant_slug")
    val restaurantName = text("restaurant_name")
    val totalCents = long("total_cents")
    val calories = integer("calories")
    val items = integer("items")
    val placedAt = timestamp("placed_at")
    val placedHour = integer("placed_hour")
    val withDeal = bool("with_deal")
    val couponCode = text("coupon_code").nullable()
    val analytics = bool("analytics")
    val awardedAt = timestamp("awarded_at").nullable()

    override val primaryKey = PrimaryKey(orderId)
}

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedMembers(
    private val db: PostgresDatabase,
) : Members {
    override suspend fun find(userId: String): Member? =
        db.transaction(readOnly = true) {
            MembersTable
                .selectAll()
                .where { MembersTable.userId eq userId }
                .singleOrNull()
                ?.toMember()
        }

    override suspend fun lock(userId: String): Member =
        db.transaction {
            MembersTable.insertIgnore { fill(it, Member.new(userId)) }
            MembersTable
                .selectAll()
                .where { MembersTable.userId eq userId }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
                .single()
                .toMember()
        }

    override suspend fun save(member: Member) {
        db.transaction { MembersTable.upsert(MembersTable.userId) { fill(it, member) } }
    }

    private fun fill(
        row: UpdateBuilder<*>,
        member: Member,
    ) {
        row[MembersTable.userId] = member.userId
        row[MembersTable.xp] = member.xp
        row[MembersTable.spinsAvailable] = member.spinsAvailable
        row[MembersTable.orders] = member.orders
        row[MembersTable.moneyNotSpentCents] = member.moneyNotSpentCents
        row[MembersTable.caloriesAvoided] = member.caloriesAvoided
        row[MembersTable.restaurants] = member.restaurants
        row[MembersTable.dealOrders] = member.dealOrders
        row[MembersTable.couponOrders] = member.couponOrders
        row[MembersTable.stampCardsCompleted] = member.stampCardsCompleted
        row[MembersTable.streakCurrent] = member.streak.current
        row[MembersTable.streakLongest] = member.streak.longest
        row[MembersTable.streakLastDay] = member.streak.lastDay
    }

    override suspend fun delete(userId: String): Int = db.transaction { MembersTable.deleteWhere { MembersTable.userId eq userId } }

    private fun ResultRow.toMember() =
        Member(
            userId = this[MembersTable.userId],
            xp = this[MembersTable.xp],
            spinsAvailable = this[MembersTable.spinsAvailable],
            orders = this[MembersTable.orders],
            moneyNotSpentCents = this[MembersTable.moneyNotSpentCents],
            caloriesAvoided = this[MembersTable.caloriesAvoided],
            restaurants = this[MembersTable.restaurants],
            dealOrders = this[MembersTable.dealOrders],
            couponOrders = this[MembersTable.couponOrders],
            stampCardsCompleted = this[MembersTable.stampCardsCompleted],
            streak = Streak(this[MembersTable.streakCurrent], this[MembersTable.streakLongest], this[MembersTable.streakLastDay]),
        )
}

class ExposedStampCards(
    private val db: PostgresDatabase,
) : StampCards {
    override suspend fun find(
        userId: String,
        restaurantId: String,
    ): StampCard? =
        db.transaction(readOnly = true) {
            StampCardsTable
                .selectAll()
                .where { (StampCardsTable.userId eq userId) and (StampCardsTable.restaurantId eq restaurantId) }
                .singleOrNull()
                ?.toCard()
        }

    override suspend fun save(card: StampCard) {
        db.transaction {
            StampCardsTable.upsert(StampCardsTable.userId, StampCardsTable.restaurantId) {
                it[userId] = card.userId
                it[restaurantId] = card.restaurant.id
                it[restaurantSlug] = card.restaurant.slug
                it[restaurantName] = card.restaurant.name
                it[stamps] = card.stamps
                it[completed] = card.completed
            }
        }
    }

    override suspend fun ofUser(userId: String): List<StampCard> =
        db.transaction(readOnly = true) {
            StampCardsTable.selectAll().where { StampCardsTable.userId eq userId }.map { it.toCard() }
        }

    override suspend fun deleteByUser(userId: String): Int =
        db.transaction { StampCardsTable.deleteWhere { StampCardsTable.userId eq userId } }

    private fun ResultRow.toCard() =
        StampCard(
            userId = this[StampCardsTable.userId],
            restaurant =
                RestaurantRef(
                    this[StampCardsTable.restaurantId],
                    this[StampCardsTable.restaurantSlug],
                    this[StampCardsTable.restaurantName],
                ),
            stamps = this[StampCardsTable.stamps],
            completed = this[StampCardsTable.completed],
        )
}

class ExposedEarnedBadges(
    private val db: PostgresDatabase,
) : EarnedBadges {
    override suspend fun ofUser(userId: String): List<EarnedBadge> =
        db.transaction(readOnly = true) {
            EarnedBadgesTable
                .selectAll()
                .where { EarnedBadgesTable.userId eq userId }
                .map { EarnedBadge(it[EarnedBadgesTable.badge], it[EarnedBadgesTable.earnedAt]) }
        }

    // A badge is earned once; a repeated award keeps the first date.
    override suspend fun add(
        userId: String,
        keys: List<String>,
        at: Instant,
    ) {
        if (keys.isEmpty()) return
        db.transaction {
            EarnedBadgesTable.batchInsert(keys, ignore = true, shouldReturnGeneratedValues = false) { key ->
                this[EarnedBadgesTable.userId] = userId
                this[EarnedBadgesTable.badge] = key
                this[EarnedBadgesTable.earnedAt] = at
            }
        }
    }

    override suspend fun deleteByUser(userId: String): Int =
        db.transaction {
            EarnedBadgesTable.deleteWhere {
                EarnedBadgesTable.userId eq
                    userId
            }
        }
}

class ExposedCoupons(
    private val db: PostgresDatabase,
) : Coupons {
    override suspend fun insert(coupon: Coupon) {
        db.transaction {
            CouponsTable.insert {
                it[id] = Uuid.parse(coupon.id)
                it[code] = coupon.code
                it[userId] = coupon.userId
                it[kind] = coupon.terms.kind.name
                it[value] = coupon.terms.value
                it[maxDiscountCents] = coupon.terms.maxDiscountCents
                it[minimumOrderCents] = coupon.terms.minimumOrderCents
                it[restaurantId] = coupon.terms.restaurant?.id
                it[restaurantSlug] = coupon.terms.restaurant?.slug
                it[restaurantName] = coupon.terms.restaurant?.name
                it[couponSource] = coupon.terms.source.name
                it[createdAt] = coupon.createdAt
                it[expiresAt] = coupon.expiresAt
                writeState(it, coupon.state)
            }
        }
    }

    override suspend fun findByCode(code: String): Coupon? =
        db.transaction(readOnly = true) {
            CouponsTable
                .selectAll()
                .where { CouponsTable.code eq code }
                .singleOrNull()
                ?.toCoupon()
        }

    override suspend fun lockByCode(code: String): Coupon? =
        db.transaction {
            CouponsTable
                .selectAll()
                .where { CouponsTable.code eq code }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
                .singleOrNull()
                ?.toCoupon()
        }

    override suspend fun update(coupon: Coupon) {
        db.transaction {
            CouponsTable.update({ CouponsTable.code eq coupon.code }) { writeState(it, coupon.state) }
        }
    }

    override suspend fun ofUser(userId: String): List<Coupon> =
        db.transaction(readOnly = true) {
            CouponsTable.selectAll().where { CouponsTable.userId eq userId }.map { it.toCoupon() }
        }

    override suspend fun deleteByUser(userId: String): Int = db.transaction { CouponsTable.deleteWhere { CouponsTable.userId eq userId } }

    private fun writeState(
        row: UpdateBuilder<*>,
        state: CouponState,
    ) {
        row[CouponsTable.state] =
            when (state) {
                CouponState.Active -> ACTIVE
                is CouponState.Reserved -> RESERVED
                is CouponState.Redeemed -> REDEEMED
            }
        row[CouponsTable.reservationId] = (state as? CouponState.Reserved)?.reservationId
        row[CouponsTable.reservedUntil] = (state as? CouponState.Reserved)?.until
        row[CouponsTable.orderId] = (state as? CouponState.Redeemed)?.orderId
        row[CouponsTable.redeemedAt] = (state as? CouponState.Redeemed)?.at
    }

    private fun ResultRow.toCoupon(): Coupon {
        val restaurantId = this[CouponsTable.restaurantId]
        val state =
            when (this[CouponsTable.state]) {
                RESERVED -> CouponState.Reserved(this[CouponsTable.reservationId]!!, this[CouponsTable.reservedUntil]!!)
                REDEEMED -> CouponState.Redeemed(this[CouponsTable.orderId]!!, this[CouponsTable.redeemedAt]!!)
                else -> CouponState.Active
            }
        return Coupon(
            id = this[CouponsTable.id].toString(),
            code = this[CouponsTable.code],
            userId = this[CouponsTable.userId],
            terms =
                CouponTerms(
                    kind = CouponKind.valueOf(this[CouponsTable.kind]),
                    value = this[CouponsTable.value],
                    maxDiscountCents = this[CouponsTable.maxDiscountCents],
                    minimumOrderCents = this[CouponsTable.minimumOrderCents],
                    restaurant =
                        restaurantId?.let {
                            RestaurantRef(it, this[CouponsTable.restaurantSlug].orEmpty(), this[CouponsTable.restaurantName].orEmpty())
                        },
                    source = CouponSource.valueOf(this[CouponsTable.couponSource]),
                ),
            createdAt = this[CouponsTable.createdAt],
            expiresAt = this[CouponsTable.expiresAt],
            state = state,
        )
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val RESERVED = "RESERVED"
        const val REDEEMED = "REDEEMED"
    }
}

class ExposedOrders(
    private val db: PostgresDatabase,
) : Orders {
    override suspend fun insert(order: OrderFacts): Boolean =
        db.transaction {
            OrdersTable
                .insertIgnore {
                    it[orderId] = order.orderId
                    it[userId] = order.userId
                    it[restaurantId] = order.restaurant.id
                    it[restaurantSlug] = order.restaurant.slug
                    it[restaurantName] = order.restaurant.name
                    it[totalCents] = order.totalCents
                    it[calories] = order.calories
                    it[items] = order.items
                    it[placedAt] = order.placedAt
                    it[placedHour] = order.placedHour
                    it[withDeal] = order.withDeal
                    it[couponCode] = order.couponCode
                    it[analytics] = order.analytics
                }.insertedCount > 0
        }

    override suspend fun find(orderId: String): KnownOrder? =
        db.transaction(readOnly = true) {
            OrdersTable
                .selectAll()
                .where { OrdersTable.orderId eq orderId }
                .singleOrNull()
                ?.let {
                    KnownOrder(
                        OrderFacts(
                            orderId = it[OrdersTable.orderId],
                            userId = it[OrdersTable.userId],
                            restaurant =
                                RestaurantRef(
                                    it[OrdersTable.restaurantId],
                                    it[OrdersTable.restaurantSlug],
                                    it[OrdersTable.restaurantName],
                                ),
                            totalCents = it[OrdersTable.totalCents],
                            calories = it[OrdersTable.calories],
                            items = it[OrdersTable.items],
                            placedAt = it[OrdersTable.placedAt],
                            placedHour = it[OrdersTable.placedHour],
                            withDeal = it[OrdersTable.withDeal],
                            couponCode = it[OrdersTable.couponCode],
                            analytics = it[OrdersTable.analytics],
                        ),
                        it[OrdersTable.awardedAt],
                    )
                }
        }

    override suspend fun markAwarded(
        orderId: String,
        at: Instant,
    ) {
        db.transaction { OrdersTable.update({ OrdersTable.orderId eq orderId }) { it[awardedAt] = at } }
    }

    override suspend fun deleteByUser(userId: String): Int = db.transaction { OrdersTable.deleteWhere { OrdersTable.userId eq userId } }
}
