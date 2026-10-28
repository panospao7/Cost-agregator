package com.yourname.expensetracker.domain.receipt.statement

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Date parsing for text statements. Returns null (never throws) for impossible dates. */
object StatementDates {
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2100

    private val DMY = Regex("""^(\d{2})/(\d{2})/(\d{4})$""")
    private val TIME = Regex("""^(\d{2}):(\d{2}):(\d{2})$""")
    private val REVOLUT = Regex("""^([A-Za-z]{3})\s+(\d{1,2}),?\s+(\d{4})$""")

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    /** "dd/MM/yyyy" to the start of that day in [zone]. */
    fun parseDmy(text: String, zone: ZoneId): Long? {
        val date = dmyDate(text) ?: return null
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * "dd/MM/yyyy" plus "HH:mm:ss" in [zone]; an unreadable time falls back to the start of the
     * day, an impossible date returns null.
     */
    fun parseDmyTime(date: String, time: String, zone: ZoneId): Long? {
        val day = dmyDate(date) ?: return null
        val match = TIME.matchEntire(time.trim())
        val localTime = if (match == null) {
            LocalTime.MIDNIGHT
        } else {
            val (hour, minute, second) = match.destructured
            try {
                LocalTime.of(hour.toInt(), minute.toInt(), second.toInt())
            } catch (e: DateTimeException) {
                LocalTime.MIDNIGHT
            }
        }
        return day.atTime(localTime).atZone(zone).toInstant().toEpochMilli()
    }

    /** "Jan 10, 2023" to the start of that day in [zone]. */
    fun parseRevolut(text: String, zone: ZoneId): Long? {
        val match = REVOLUT.matchEntire(text.trim()) ?: return null
        val month = MONTHS[match.groupValues[1].lowercase()] ?: return null
        val date = localDate(match.groupValues[3].toInt(), month, match.groupValues[2].toInt()) ?: return null
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** True when [token] is an English three-letter month abbreviation. */
    fun isRevolutMonth(token: String): Boolean = MONTHS.containsKey(token.lowercase())

    private fun dmyDate(text: String): LocalDate? {
        val match = DMY.matchEntire(text.trim()) ?: return null
        val (day, month, year) = match.destructured
        return localDate(year.toInt(), month.toInt(), day.toInt())
    }

    private fun localDate(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in MIN_YEAR..MAX_YEAR) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }
    }
}
