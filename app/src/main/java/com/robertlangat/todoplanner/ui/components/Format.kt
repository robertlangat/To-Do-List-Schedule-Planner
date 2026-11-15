package com.robertlangat.todoplanner.ui.components

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun formatDate(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> if (date.year == today.year) date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

fun formatTime(time: LocalTime): String = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
