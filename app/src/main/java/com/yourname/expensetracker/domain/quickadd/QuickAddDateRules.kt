package com.yourname.expensetracker.domain.quickadd

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Date and time-of-day rules (spec 4.7, 4.8, INV-6).
 *
 * All calendar arithmetic is `LocalDate` arithmetic in the input zone. Nothing here reads a clock
 * or the system zone: "now" and the zone are arguments.
 */

/** A recognised date expression, before it is resolved against "now". */
internal sealed interface DateExpr {
    /** today minus [daysAgo] days (0, 1 or 2). */
    data class Relative(val daysAgo: Long) : DateExpr

    /** tomorrow and friends. */
    data object Future : DateExpr

    /** modifier + weekday ("last monday"). */
    data object Unsupported : DateExpr

    data class Weekday(val day: DayOfWeek) : DateExpr

    /** Day-first numeric date. [yearDigits] is the digit count of the written year, or 0 when absent. */
    data class Numeric(val day: Int, val month: Int, val year: Int, val yearDigits: Int) : DateExpr
}

internal object QuickAddDateRules {

    /** Year inference window for a numeric date without a year: at most this many days back. */
    private const val YEAR_BOUNDARY_DAYS = 92L

    /**
     * Resolves the recognised date expressions of one line.
     * Zero expressions give Absent; two or more give DATE_AMBIGUOUS even if they agree.
     */
    fun resolve(
        expressions: List<DateExpr>,
        nowEpochMs: Long,
        zone: ZoneId
    ): FieldParse<ResolvedDate> {
        if (expressions.isEmpty()) return FieldParse.Absent
        if (expressions.size >= 2) return FieldParse.Flagged(FlagReason.DATE_AMBIGUOUS)

        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val today = now.toLocalDate()

        return when (val expr = expressions.single()) {
            is DateExpr.Relative -> value(today.minusDays(expr.daysAgo), now, zone)
            DateExpr.Future -> FieldParse.Flagged(FlagReason.DATE_FUTURE)
            DateExpr.Unsupported -> FieldParse.Flagged(FlagReason.DATE_UNSUPPORTED)
            is DateExpr.Weekday -> {
                // offset = (T - W) mod 7; 0 means "same weekday as today" and is flagged (OD-4).
                val offset = Math.floorMod(today.dayOfWeek.value - expr.day.value, 7)
                if (offset == 0) {
                    FieldParse.Flagged(FlagReason.DATE_AMBIGUOUS)
                } else {
                    value(today.minusDays(offset.toLong()), now, zone)
                }
            }
            is DateExpr.Numeric -> resolveNumeric(expr, today, now, zone)
        }
    }

    private fun resolveNumeric(
        expr: DateExpr.Numeric,
        today: LocalDate,
        now: ZonedDateTime,
        zone: ZoneId
    ): FieldParse<ResolvedDate> {
        if (expr.yearDigits > 0) {
            val year = when (expr.yearDigits) {
                2 -> 2000 + expr.year
                4 -> expr.year
                else -> return FieldParse.Flagged(FlagReason.DATE_INVALID)
            }
            if (year < 2000) return FieldParse.Flagged(FlagReason.DATE_INVALID)
            val date = dateOrNull(year, expr.month, expr.day)
                ?: return FieldParse.Flagged(FlagReason.DATE_INVALID)
            return if (date.isAfter(today)) {
                FieldParse.Flagged(FlagReason.DATE_FUTURE)
            } else {
                value(date, now, zone)
            }
        }

        val currentYear = today.year
        val thisYear = dateOrNull(currentYear, expr.month, expr.day)
        if (thisYear != null && !thisYear.isAfter(today)) return value(thisYear, now, zone)

        val lastYear = dateOrNull(currentYear - 1, expr.month, expr.day)
        if (lastYear != null && ChronoUnit.DAYS.between(lastYear, today) <= YEAR_BOUNDARY_DAYS) {
            return value(lastYear, now, zone)
        }

        return if (thisYear != null) {
            FieldParse.Flagged(FlagReason.DATE_FUTURE)
        } else {
            FieldParse.Flagged(FlagReason.DATE_INVALID)
        }
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day < 1 || year < 1) return null
        val yearMonth = YearMonth.of(year, month)
        if (day > yearMonth.lengthOfMonth()) return null
        return yearMonth.atDay(day)
    }

    /**
     * INV-6: the local time of "now" (truncated to seconds) is carried onto [localDate] in [zone].
     * java.time gap rule: a time inside a DST gap moves forward by the gap, on the same date.
     * Overlap rule: the earlier offset is used. The result stays on [localDate]; if a zone ever
     * produced another date (a fully skipped day), the start of that date is used instead.
     */
    private fun value(localDate: LocalDate, now: ZonedDateTime, zone: ZoneId): FieldParse<ResolvedDate> {
        val time = now.toLocalTime().truncatedTo(ChronoUnit.SECONDS)
        var resolved = localDate.atTime(time).atZone(zone)
        if (resolved.toLocalDate() != localDate) {
            resolved = localDate.atStartOfDay(zone)
        }
        return FieldParse.Value(ResolvedDate(localDate, resolved.toInstant().toEpochMilli()))
    }
}
