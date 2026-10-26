package io.github.slashncode.nothingeats.loyalty.domain

import java.time.LocalDate

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts `de`, `en-GB` or an Accept-Language header; anything unknown is German. */
        fun parse(raw: String?): Language {
            val first =
                raw
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.take(2)
            return entries.firstOrNull { it.tag == first } ?: DE
        }
    }
}

data class Text(
    val de: String,
    val en: String,
) {
    fun of(language: Language) =
        when (language) {
            Language.DE -> de
            Language.EN -> en
        }
}

/**
 * Levels grow slowly: level 2 needs 100 XP, every next level 50 XP more than the one before
 * (100, 150, 200, …). An order of nothing brings 25–100 XP, so the first levels come quickly.
 */
object Levels {
    private const val FIRST_STEP = 100L
    private const val STEP_GROWTH = 50L

    /** Total XP at which [level] begins (level 1 at 0). */
    fun startOf(level: Int): Long {
        require(level >= 1) { "levels start at 1" }
        val steps = (level - 1).toLong()
        return FIRST_STEP * steps + STEP_GROWTH * steps * (steps - 1) / 2
    }

    fun of(xp: Long): Int {
        var level = 1
        while (startOf(level + 1) <= xp) level++
        return level
    }
}

/** Days in a row with at least one order, counted in the shop's time zone. */
data class Streak(
    val current: Int,
    val longest: Int,
    val lastDay: LocalDate?,
) {
    /** An order on [day]: same day keeps it, the next day extends it, a gap starts over. */
    fun record(day: LocalDate): Streak {
        val last = lastDay
        val next =
            when {
                last == null -> 1
                day == last -> current
                day == last.plusDays(1) -> current + 1
                day.isBefore(last) -> return this // a late event of an earlier day changes nothing
                else -> 1
            }
        return Streak(next, maxOf(longest, next), day)
    }

    /** The streak as it stands on [today]: broken once a whole day went by without an order. */
    fun currentOn(today: LocalDate): Int {
        val last = lastDay ?: return 0
        return if (!last.isBefore(today.minusDays(1))) current else 0
    }

    companion object {
        val NONE = Streak(0, 0, null)
    }
}

/** A customer's loyalty account, created with their first delivered order (or a look at the rewards page). */
data class Member(
    val userId: String,
    val xp: Long,
    val spinsAvailable: Int,
    val orders: Int,
    val moneyNotSpentCents: Long,
    val caloriesAvoided: Long,
    val restaurants: Int,
    val dealOrders: Int,
    val couponOrders: Int,
    val stampCardsCompleted: Int,
    val streak: Streak,
) {
    val level: Int get() = Levels.of(xp)

    companion object {
        fun new(userId: String) = Member(userId, 0, 0, 0, 0, 0, 0, 0, 0, 0, Streak.NONE)
    }
}

/** Five deliveries from one restaurant fill a card; a full card becomes a free dish there. */
data class StampCard(
    val userId: String,
    val restaurant: RestaurantRef,
    val stamps: Int,
    val completed: Int,
) {
    val isNew: Boolean get() = stamps == 0 && completed == 0

    /** One more stamp; a full card starts over and counts as completed. */
    fun stamp(): StampCard = if (stamps + 1 >= PER_CARD) copy(stamps = 0, completed = completed + 1) else copy(stamps = stamps + 1)

    companion object {
        const val PER_CARD = 5

        fun empty(
            userId: String,
            restaurant: RestaurantRef,
        ) = StampCard(userId, restaurant, 0, 0)
    }
}

data class RestaurantRef(
    /** The catalog's restaurant id. */
    val id: String,
    val slug: String,
    val name: String,
)
