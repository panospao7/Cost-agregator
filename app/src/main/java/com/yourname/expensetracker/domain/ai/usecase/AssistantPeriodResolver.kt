package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A resolved assistant period: half-open `[startMs, endMs)` in epoch milliseconds plus the
 * calendar unit it belongs to. [granularity] is `null` for custom or unknown ranges.
 *
 * Plain values only: allowlisted callers wrap this into their own range type.
 */
data class AssistantPeriod(
    val startMs: Long,
    val endMs: Long,
    val granularity: PeriodGranularity?
)

/** Result of [AssistantPeriodResolver.resolveText]. */
data class TextPeriodResolution(
    val period: AssistantPeriod,
    val comparisonRequested: Boolean
)

/**
 * Single owner of the assistant's English period vocabulary, comparison cue and period arithmetic
 * (ai-fixes INV-C8..C12).
 *
 * Pure: no clock reads, no I/O. Every function takes `nowMs` explicitly (callers pass the
 * interpretation input's current time) and a [ZoneId] that defaults to the system zone, which is the
 * zone `TimePeriodUtils` and the repository filters use. All boundaries are local midnights built with
 * java.time, so DST days (23/25 h) and month lengths are handled by calendar arithmetic, never by
 * fixed millisecond durations.
 *
 * Shapes of the *current* unit (P3): week and month are FULL (end = start of the next unit);
 * day, quarter and year are TO_DATE (end = `nowMs`). `last/previous/prior <unit>` is always the
 * previous FULL calendar unit.
 */
object AssistantPeriodResolver {

    private val UNITS = listOf(
        "week" to PeriodGranularity.WEEK,
        "month" to PeriodGranularity.MONTH,
        "quarter" to PeriodGranularity.QUARTER,
        "year" to PeriodGranularity.YEAR
    )

    private const val UNIT_ALTERNATION = "week|month|quarter|year"

    private val COMPARISON_WORD = Regex("\\b(?:compare|compared|comparison|vs|versus)\\b")
    private val COMPARISON_THAN_TARGET =
        Regex("\\bthan\\s+(?:the\\s+)?(?:last|previous|prior)\\s+(?:$UNIT_ALTERNATION)\\b")
    private val COMPARISON_PREVIOUS_PERIOD = Regex("\\bprevious\\s+period\\b")

    /**
     * Comparison target: `(last|previous|prior) <unit>` that follows a comparison connector.
     * Group 1 is the connector (kept); the target phrase is removed before period resolution.
     * The `compare...with/to` connector tolerates words in between ("compare food with ...").
     */
    private val COMPARISON_TARGET = Regex(
        "(\\bcompar(?:e|ed|ing|ison)\\b[^.?!]*?\\b(?:with|to)|\\bvs\\.?|\\bversus|\\bthan|\\bagainst)" +
            "\\s+(?:the\\s+)?(?:last|previous|prior)\\s+(?:$UNIT_ALTERNATION)\\b"
    )

    private val TODAY = Regex("\\btoday\\b")
    private val LAST_UNIT = UNITS.map { (name, g) ->
        g to Regex("\\b(?:last|previous|prior)\\s+$name\\b")
    }
    private val THIS_UNIT = UNITS.map { (name, g) ->
        g to Regex("\\b(?:this|current)\\s+$name\\b")
    }

    /**
     * The one comparison cue. True iff the (lower-cased, trimmed) query contains the word
     * compare/compared/comparison/vs/versus, the phrase `than (the)? (last|previous|prior) <unit>`,
     * or `previous period`. A bare "previous" is a period phrase, not a comparison cue.
     */
    fun isComparisonRequested(normalizedQuery: String): Boolean =
        COMPARISON_WORD.containsMatchIn(normalizedQuery) ||
            COMPARISON_THAN_TARGET.containsMatchIn(normalizedQuery) ||
            COMPARISON_PREVIOUS_PERIOD.containsMatchIn(normalizedQuery)

    /**
     * Resolves the period of a lower-cased, trimmed English query (INV-C9 precedence):
     * comparison target removal, `today`, `last/previous/prior <unit>`, `this/current <unit>`,
     * bare `<unit>` substring, then this month.
     */
    fun resolveText(
        normalizedQuery: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): TextPeriodResolution {
        val comparison = isComparisonRequested(normalizedQuery)
        val query = if (comparison) {
            COMPARISON_TARGET.replace(normalizedQuery, "$1 ")
        } else {
            normalizedQuery
        }

        val period = when {
            TODAY.containsMatchIn(query) -> currentUnit(nowMs, PeriodGranularity.DAY, zone)
            else -> {
                val last = LAST_UNIT.firstOrNull { (_, regex) -> regex.containsMatchIn(query) }
                val current = THIS_UNIT.firstOrNull { (_, regex) -> regex.containsMatchIn(query) }
                val bare = UNITS.firstOrNull { (name, _) -> query.contains(name) }
                when {
                    last != null -> previousFullUnit(nowMs, last.first, zone)
                    current != null -> currentUnit(nowMs, current.first, zone)
                    bare != null -> currentUnit(nowMs, bare.second, zone)
                    else -> currentUnit(nowMs, PeriodGranularity.MONTH, zone)
                }
            }
        }
        return TextPeriodResolution(period = period, comparisonRequested = comparison)
    }

    /**
     * Resolves a model `periodKeyword` (case-insensitive). Supported: TODAY, THIS_WEEK, THIS_MONTH,
     * LAST_WEEK, LAST_MONTH, THIS_QUARTER, LAST_QUARTER, THIS_YEAR, LAST_YEAR. Anything else is null.
     */
    fun resolveKeyword(
        keyword: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AssistantPeriod? = when (keyword.trim().uppercase(Locale.ROOT)) {
        "TODAY" -> currentUnit(nowMs, PeriodGranularity.DAY, zone)
        "THIS_WEEK" -> currentUnit(nowMs, PeriodGranularity.WEEK, zone)
        "THIS_MONTH" -> currentUnit(nowMs, PeriodGranularity.MONTH, zone)
        "THIS_QUARTER" -> currentUnit(nowMs, PeriodGranularity.QUARTER, zone)
        "THIS_YEAR" -> currentUnit(nowMs, PeriodGranularity.YEAR, zone)
        "LAST_WEEK" -> previousFullUnit(nowMs, PeriodGranularity.WEEK, zone)
        "LAST_MONTH" -> previousFullUnit(nowMs, PeriodGranularity.MONTH, zone)
        "LAST_QUARTER" -> previousFullUnit(nowMs, PeriodGranularity.QUARTER, zone)
        "LAST_YEAR" -> previousFullUnit(nowMs, PeriodGranularity.YEAR, zone)
        else -> null
    }

    /** Resolves a clarification period option. THIS_* are the current unit; LAST_MONTH is the previous full month. */
    fun resolveChoice(
        choice: PeriodChoice,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AssistantPeriod = when (choice) {
        PeriodChoice.THIS_MONTH -> currentUnit(nowMs, PeriodGranularity.MONTH, zone)
        PeriodChoice.THIS_WEEK -> currentUnit(nowMs, PeriodGranularity.WEEK, zone)
        PeriodChoice.LAST_MONTH -> previousFullUnit(nowMs, PeriodGranularity.MONTH, zone)
    }

    /**
     * Calendar-aware previous equivalent of `[startMs, endMs)` (INV-C11, INV-C12).
     *
     * - [granularity] null: classified by an exact FULL match against DAY/WEEK/MONTH/QUARTER/YEAR,
     *   otherwise CUSTOM.
     * - FULL unit (`startMs` is the unit start and `endMs` is the unit end): `[start of the previous unit, startMs)`.
     * - TO_DATE unit (`startMs` is the unit start and `endMs` is before the unit end):
     *   `[start of the previous unit, min(startMs, endMs minus one unit))`, with java.time
     *   calendar arithmetic (month-end clamp, DST).
     * - CUSTOM (anything else, including an explicit granularity with an unaligned start):
     *   `[startMs - (endMs - startMs), startMs)`.
     *
     * Always `previous.endMs <= startMs`; for FULL, `previous.endMs == startMs`.
     */
    fun previousEquivalent(
        startMs: Long,
        endMs: Long,
        granularity: PeriodGranularity?,
        zone: ZoneId = ZoneId.systemDefault()
    ): AssistantPeriod {
        val unit = granularity ?: classifyFull(startMs, endMs, zone)
        if (unit != null) {
            val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            val unitStartDate = unitStartDate(startDate, unit)
            val unitStartMs = unitStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val unitEndMs = plusUnit(unitStartDate, unit, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val prevStartMs = unitStartOf(minusUnit(startDate, unit, 1), unit, zone)

            if (startMs == unitStartMs && endMs == unitEndMs) {
                // FULL (both bounds aligned to the unit)
                return AssistantPeriod(prevStartMs, startMs, unit)
            }
            if (startMs == unitStartMs && endMs < unitEndMs) {
                // TO_DATE
                val endMinusUnit = minusUnit(Instant.ofEpochMilli(endMs).atZone(zone), unit, 1)
                    .toInstant().toEpochMilli()
                val prevEndMs = minOf(startMs, endMinusUnit).coerceAtLeast(prevStartMs)
                return AssistantPeriod(prevStartMs, prevEndMs, unit)
            }
        }
        // CUSTOM: duration rule, kept only for non-calendar ranges.
        val duration = endMs - startMs
        return AssistantPeriod(startMs - duration, startMs, null)
    }

    // ---- unit arithmetic (java.time, in [zone]) -------------------------------------------------

    private fun currentUnit(nowMs: Long, unit: PeriodGranularity, zone: ZoneId): AssistantPeriod {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val startDate = unitStartDate(today, unit)
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = when (unit) {
            PeriodGranularity.WEEK, PeriodGranularity.MONTH ->
                plusUnit(startDate, unit, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            PeriodGranularity.DAY, PeriodGranularity.QUARTER, PeriodGranularity.YEAR -> nowMs
        }
        return AssistantPeriod(startMs, endMs, unit)
    }

    private fun previousFullUnit(nowMs: Long, unit: PeriodGranularity, zone: ZoneId): AssistantPeriod {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val currentStart = unitStartDate(today, unit)
        val prevStart = minusUnit(currentStart, unit, 1)
        return AssistantPeriod(
            startMs = prevStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = currentStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            granularity = unit
        )
    }

    private fun classifyFull(startMs: Long, endMs: Long, zone: ZoneId): PeriodGranularity? {
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        return PeriodGranularity.entries.firstOrNull { unit ->
            val unitStart = unitStartDate(startDate, unit)
            unitStart.atStartOfDay(zone).toInstant().toEpochMilli() == startMs &&
                plusUnit(unitStart, unit, 1).atStartOfDay(zone).toInstant().toEpochMilli() == endMs
        }
    }

    private fun unitStartOf(date: LocalDate, unit: PeriodGranularity, zone: ZoneId): Long =
        unitStartDate(date, unit).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun unitStartDate(date: LocalDate, unit: PeriodGranularity): LocalDate = when (unit) {
        PeriodGranularity.DAY -> date
        PeriodGranularity.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        PeriodGranularity.MONTH -> date.withDayOfMonth(1)
        PeriodGranularity.QUARTER -> date.withDayOfMonth(1).withMonth(((date.monthValue - 1) / 3) * 3 + 1)
        PeriodGranularity.YEAR -> date.withDayOfYear(1)
    }

    private fun plusUnit(date: LocalDate, unit: PeriodGranularity, n: Long): LocalDate = when (unit) {
        PeriodGranularity.DAY -> date.plusDays(n)
        PeriodGranularity.WEEK -> date.plusWeeks(n)
        PeriodGranularity.MONTH -> date.plusMonths(n)
        PeriodGranularity.QUARTER -> date.plusMonths(3 * n)
        PeriodGranularity.YEAR -> date.plusYears(n)
    }

    private fun minusUnit(date: LocalDate, unit: PeriodGranularity, n: Long): LocalDate =
        plusUnit(date, unit, -n)

    private fun minusUnit(dateTime: ZonedDateTime, unit: PeriodGranularity, n: Long): ZonedDateTime = when (unit) {
        PeriodGranularity.DAY -> dateTime.minusDays(n)
        PeriodGranularity.WEEK -> dateTime.minusWeeks(n)
        PeriodGranularity.MONTH -> dateTime.minusMonths(n)
        PeriodGranularity.QUARTER -> dateTime.minusMonths(3 * n)
        PeriodGranularity.YEAR -> dateTime.minusYears(n)
    }
}
