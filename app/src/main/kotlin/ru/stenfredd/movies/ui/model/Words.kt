package ru.stenfredd.movies.ui.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Russian plural form for [n]: 1 серия, 2 серии, 5 серий. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** How long ago [at] (epoch ms) was, by calendar days: "сегодня", "вчера", "3 дня назад", "2 недели назад", "в прошлом году"… */
internal fun daysAgo(at: Long): String {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), LocalDate.now(zone)).toInt()
    return when {
        days <= 0 -> "сегодня"
        days == 1 -> "вчера"
        days < 7 -> "$days ${plural(days, "день", "дня", "дней")} назад"
        days < 14 -> "неделю назад"
        days < 30 -> "${days / 7} недели назад"
        days < 60 -> "месяц назад"
        days < 365 -> (days / 30).let { "$it ${plural(it, "месяц", "месяца", "месяцев")} назад" }
        days < 730 -> "год назад"
        else -> (days / 365).let { "$it ${plural(it, "год", "года", "лет")} назад" }
    }
}
