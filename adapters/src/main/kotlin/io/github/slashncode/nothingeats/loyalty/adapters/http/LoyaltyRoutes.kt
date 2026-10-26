package io.github.slashncode.nothingeats.loyalty.adapters.http

import io.github.slashncode.nothingeats.loyalty.application.ExportRewards
import io.github.slashncode.nothingeats.loyalty.application.ListCoupons
import io.github.slashncode.nothingeats.loyalty.application.RewardsOverview
import io.github.slashncode.nothingeats.loyalty.application.SpinResult
import io.github.slashncode.nothingeats.loyalty.application.SpinWheel
import io.github.slashncode.nothingeats.loyalty.application.ViewRewards
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.Coupon
import io.github.slashncode.nothingeats.loyalty.domain.Language
import io.github.slashncode.nothingeats.loyalty.domain.Levels
import io.github.slashncode.nothingeats.loyalty.domain.StampCard
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class LevelResponse(
    val level: Int,
    val xp: Long,
    val levelStartXp: Long,
    val nextLevelXp: Long,
)

@Serializable
data class StreakResponse(
    val days: Int,
    val longest: Int,
    val orderedToday: Boolean,
)

@Serializable
data class StatsResponse(
    val orders: Int,
    val moneyNotSpentCents: Long,
    val caloriesAvoided: Long,
    val restaurants: Int,
)

@Serializable
data class BadgeResponse(
    val key: String,
    val tier: String,
    val name: String,
    val description: String,
    val earnedAt: String?,
)

@Serializable
data class StampCardResponse(
    val restaurantSlug: String,
    val restaurantName: String,
    val stamps: Int,
    val perCard: Int,
    val completed: Int,
)

@Serializable
data class CouponRestaurantResponse(
    val slug: String,
    val name: String,
)

@Serializable
data class CouponResponse(
    val code: String,
    val kind: String,
    val value: Long,
    val maxDiscountCents: Long?,
    val minimumOrderCents: Long,
    val restaurant: CouponRestaurantResponse?,
    val source: String,
    val expiresAt: String,
)

@Serializable
data class RewardsResponse(
    val level: LevelResponse,
    val spinsAvailable: Int,
    val streak: StreakResponse,
    val stats: StatsResponse,
    val badges: List<BadgeResponse>,
    val stampCards: List<StampCardResponse>,
    val coupons: List<CouponResponse>,
)

@Serializable
data class CouponsResponse(
    val items: List<CouponResponse>,
)

@Serializable
data class SpinResponse(
    val prize: String,
    val coupon: CouponResponse?,
    val xpGained: Int,
    val level: LevelResponse?,
    val badges: List<BadgeResponse>,
    val spinsAvailable: Int,
)

@Serializable
data class ExportMemberResponse(
    val xp: Long,
    val level: Int,
    val spinsAvailable: Int,
    val orders: Int,
    val moneyNotSpentCents: Long,
    val caloriesAvoided: Long,
    val restaurants: Int,
    val streakDays: Int,
    val longestStreakDays: Int,
    val lastOrderDay: String?,
)

@Serializable
data class ExportBadgeResponse(
    val key: String,
    val earnedAt: String,
)

@Serializable
data class ExportCouponResponse(
    val coupon: CouponResponse,
    val state: String,
    val createdAt: String,
)

@Serializable
data class ExportResponse(
    val member: ExportMemberResponse?,
    val badges: List<ExportBadgeResponse>,
    val stampCards: List<StampCardResponse>,
    val coupons: List<ExportCouponResponse>,
)

/** `/api/loyalty/v1`: the signed-in customer's rewards. Texts in `?lang=` or Accept-Language. */
class LoyaltyRoutes(
    private val viewRewards: ViewRewards,
    private val listCoupons: ListCoupons,
    private val spinWheel: SpinWheel,
    private val exportRewards: ExportRewards,
) : HttpRoutes {
    override fun Route.register() {
        route("/api/loyalty/v1") {
            authenticated {
                get("/me") { call.respond(viewRewards(call.user.id).response(call.language())) }
                get("/coupons") { call.respond(CouponsResponse(listCoupons(call.user.id).map { it.response() })) }
                post("/spins") { call.respond(spinWheel(call.user.id).response(call.language())) }
                get("/me/export") {
                    val export = exportRewards(call.user.id)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"nothingeats-rewards.json\"")
                    call.respond(
                        ExportResponse(
                            member =
                                export.member?.let {
                                    ExportMemberResponse(
                                        xp = it.xp,
                                        level = it.level,
                                        spinsAvailable = it.spinsAvailable,
                                        orders = it.orders,
                                        moneyNotSpentCents = it.moneyNotSpentCents,
                                        caloriesAvoided = it.caloriesAvoided,
                                        restaurants = it.restaurants,
                                        streakDays = it.streak.current,
                                        longestStreakDays = it.streak.longest,
                                        lastOrderDay = it.streak.lastDay?.toString(),
                                    )
                                },
                            badges = export.badges.map { ExportBadgeResponse(it.key, it.at.toString()) },
                            stampCards = export.stampCards.map { it.response() },
                            coupons =
                                export.coupons.map {
                                    ExportCouponResponse(
                                        it.response(),
                                        it.state::class.simpleName.orEmpty().uppercase(),
                                        it.createdAt.toString(),
                                    )
                                },
                        ),
                    )
                }
            }
        }
    }

    private fun ApplicationCall.language(): Language {
        val language = Language.parse(request.queryParameters["lang"] ?: request.acceptLanguage())
        response.header(HttpHeaders.ContentLanguage, language.tag)
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        return language
    }

    private fun RewardsOverview.response(language: Language) =
        RewardsResponse(
            level = LevelResponse(member.level, member.xp, levelStartXp, nextLevelXp),
            spinsAvailable = member.spinsAvailable,
            streak = StreakResponse(streakDays, member.streak.longest, orderedToday),
            stats = StatsResponse(member.orders, member.moneyNotSpentCents, member.caloriesAvoided, member.restaurants),
            badges = badges.map { it.definition.response(language, it.earnedAt?.toString()) },
            stampCards = stampCards.map { it.response() },
            coupons = coupons.map { it.response() },
        )

    private fun SpinResult.response(language: Language) =
        SpinResponse(
            prize = prize.name,
            coupon = coupon?.response(),
            xpGained = award?.xpGained ?: 0,
            level =
                award?.member?.let { LevelResponse(it.level, it.xp, Levels.startOf(it.level), Levels.startOf(it.level + 1)) },
            badges = award?.badges.orEmpty().map { it.response(language, null) },
            spinsAvailable = spinsLeft,
        )

    private fun BadgeDefinition.response(
        language: Language,
        earnedAt: String?,
    ) = BadgeResponse(key, tier.name, name.of(language), description.of(language), earnedAt)

    private fun StampCard.response() = StampCardResponse(restaurant.slug, restaurant.name, stamps, StampCard.PER_CARD, completed)
}

internal fun Coupon.response() =
    CouponResponse(
        code = code,
        kind = terms.kind.name,
        value = terms.value,
        maxDiscountCents = terms.maxDiscountCents,
        minimumOrderCents = terms.minimumOrderCents,
        restaurant = terms.restaurant?.let { CouponRestaurantResponse(it.slug, it.name) },
        source = terms.source.name,
        expiresAt = expiresAt.toString(),
    )
