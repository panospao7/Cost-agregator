package com.yourname.expensetracker.domain.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Calendar

/**
 * Canonical owner of all shared calendar boundary math for the ExpenseTracker app.
 *
 * ## Half-open interval contract
 *
 * All period ranges returned by this utility follow the **`[startInclusive, endExclusive)`**
 * convention. A timestamp `t` belongs to a period when:
 * ```
 *   t >= periodStart && t < periodEnd
 * ```
 *
 * ### Key rules
 * - **Day end** = start of the **next** day (midnight), not `23:59:59.999`.
 * - **Week start** = Monday `00:00:00.000`, locale-independent.
 * - **Week end** = next Monday `00:00:00.000` (exclusive).
 * - **Month end** = 1st of next month `00:00:00.000`.
 * - **Quarter end** = 1st of next quarter `00:00:00.000`.
 * - **Year end** = Jan 1st of next year `00:00:00.000`.
 *
 * ### Calendar-aware arithmetic
 * All day/month/year addition uses [Calendar.add] (or `java.time`), never raw
 * millisecond multiplication. This preserves correctness across DST transitions,
 * leap years, and varying month lengths.
 *
 * ### Timezone
 * All calculations use the system default timezone. This utility intentionally
 * does **not** perform UTC normalization — that concern belongs elsewhere.
 *
 * @see daysBetween for DST-safe calendar-day difference via `java.time.LocalDate`.
 */
object TimePeriodUtils {

    /**
     * Number of milliseconds in a standard 24-hour day.
     *
     * **Prefer calendar-aware helpers** ([addDays], [getWeekRange], [daysBetween])
     * over manual multiplication with this constant. During DST transitions a
     * calendar day can be 23 or 25 hours, so `n * DAY_IN_MILLIS` is not always
     * correct for logical day arithmetic.
     */
    const val DAY_IN_MILLIS: Long = 24L * 60L * 60L * 1000L

    // ============================================================================
    // HALF-OPEN CONTAINMENT CHECK
    // ============================================================================

    /**
     * Returns `true` if [timestamp] falls within the half-open range
     * `[startInclusive, endExclusive)`.
     *
     * This is the canonical containment check for **all** period boundaries
     * produced by this utility. Prefer this over hand-written
     * `t >= start && t < end` or Kotlin `in start..end` (which is inclusive
     * on both sides and therefore **wrong** for period boundaries).
     */
    fun isInRange(timestamp: Long, startInclusive: Long, endExclusive: Long): Boolean {
        return timestamp >= startInclusive && timestamp < endExclusive
    }

    // ============================================================================
    // DAY BOUNDARIES
    // ============================================================================

    /**
     * Returns the start of the day (`00:00:00.000`) for the given [timestamp]
     * in the system default timezone.
     */
    fun getStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns the **exclusive** upper bound of the day containing [timestamp]:
     * the start of the **next** day (`00:00:00.000`).
     *
     * Half-open contract: a timestamp `t` is in this day when
     * `t >= getStartOfDay(ts) && t < getEndOfDay(ts)`.
     */
    fun getEndOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfDay(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * Returns a `[startInclusive, endExclusive)` pair for the calendar day
     * containing [timestamp].
     *
     * - Start: `00:00:00.000` of the day.
     * - End: `00:00:00.000` of the **next** day (exclusive).
     */
    fun getDayRange(timestamp: Long): Pair<Long, Long> {
        val start = getStartOfDay(timestamp)
        val end = getEndOfDay(timestamp)
        return start to end
    }

    // ============================================================================
    // WEEK BOUNDARIES  (Monday-start, locale-independent)
    // ============================================================================

    /**
     * Returns the start of the ISO week (Monday `00:00:00.000`) containing [timestamp].
     *
     * This is **locale-independent**: the week always starts on Monday regardless
     * of the device's [java.util.Locale] or [Calendar.firstDayOfWeek] setting.
     */
    fun getStartOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Sun=1, Mon=2, Tue=3 … Sat=7
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY

        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        return cal.timeInMillis
    }

    /**
     * Returns the **exclusive** upper bound of the week containing [timestamp]:
     * the **next** Monday at `00:00:00.000`.
     *
     * Half-open contract: `t >= getStartOfWeek(ts) && t < getEndOfWeek(ts)`.
     */
    fun getEndOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfWeek(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        return cal.timeInMillis
    }

    /**
     * Returns a `[startInclusive, endExclusive)` pair for the calendar week
     * containing [timestamp], optionally shifted by [weekOffset] weeks.
     *
     * - Week starts on Monday `00:00:00.000`.
     * - Week ends at the **next** Monday `00:00:00.000` (exclusive).
     *
     * @param timestamp Reference time to determine which week.
     * @param weekOffset 0 for current week, -1 for previous week, etc.
     */
    fun getWeekRange(timestamp: Long, weekOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp

        // Apply week offset using calendar-aware addition
        if (weekOffset != 0) {
            cal.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
        }

        // Calculate Monday of this week using delta logic (locale-independent)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)

        // Start of week: Monday 00:00:00.000
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis

        // End of week: next Monday 00:00:00.000 (exclusive, calendar-aware)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        val endMs = cal.timeInMillis

        return startMs to endMs
    }

    // ============================================================================
    // MONTH BOUNDARIES
    // ============================================================================

    /**
     * Returns the start of the month (1st, `00:00:00.000`) for the given [timestamp].
     */
    fun getStartOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns the **exclusive** upper bound of the month containing [timestamp]:
     * the 1st of the **next** month at `00:00:00.000`.
     *
     * Half-open contract: `t >= getStartOfMonth(ts) && t < getEndOfMonth(ts)`.
     */
    fun getEndOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfMonth(timestamp)
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * Returns a `[startInclusive, endExclusive)` pair for the calendar month
     * containing [timestamp], optionally shifted by [monthOffset] months.
     *
     * @param timestamp Reference time.
     * @param monthOffset 0 for current month, -1 for previous month, etc.
     */
    fun getMonthRange(timestamp: Long, monthOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (monthOffset != 0) {
            cal.add(Calendar.MONTH, monthOffset)
        }

        val start = getStartOfMonth(cal.timeInMillis)
        val end = getEndOfMonth(cal.timeInMillis)
        return start to end
    }

    /**
     * Returns a half-open `[start, end)` range for a specific calendar
     * [year] and [month] (1-based: 1 = January, 12 = December).
     *
     * The start is the first millisecond of the month; the end is the first
     * millisecond of the **next** month.
     */
    fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)  // Calendar uses 0-based months
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return getMonthRange(cal.timeInMillis)
    }

    // ============================================================================
    // QUARTER BOUNDARIES
    // ============================================================================

    /**
     * Returns the start of the quarter containing [timestamp]
     * (1st of the quarter's first month at `00:00:00.000`).
     */
    fun getStartOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val month = cal.get(Calendar.MONTH)
        val quarterStartMonth = (month / 3) * 3
        cal.set(Calendar.MONTH, quarterStartMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns the **exclusive** upper bound of the quarter containing [timestamp]:
     * the 1st of the **next** quarter at `00:00:00.000`.
     */
    fun getEndOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfQuarter(timestamp)
        cal.add(Calendar.MONTH, 3)
        return cal.timeInMillis
    }

    /**
     * Returns a `[startInclusive, endExclusive)` pair for the quarter
     * containing [timestamp], optionally shifted by [quarterOffset] quarters.
     *
     * @param timestamp Reference time.
     * @param quarterOffset 0 for current quarter, -1 for previous quarter, etc.
     */
    fun getQuarterRange(timestamp: Long, quarterOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (quarterOffset != 0) {
            cal.add(Calendar.MONTH, quarterOffset * 3)
        }

        val start = getStartOfQuarter(cal.timeInMillis)
        val end = getEndOfQuarter(cal.timeInMillis)
        return start to end
    }

    // ============================================================================
    // YEAR BOUNDARIES
    // ============================================================================

    /**
     * Returns the start of the year (Jan 1st, `00:00:00.000`) for [timestamp].
     */
    fun getStartOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns the **exclusive** upper bound of the year containing [timestamp]:
     * Jan 1st of the **next** year at `00:00:00.000`.
     */
    fun getEndOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfYear(timestamp)
        cal.add(Calendar.YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * Returns a `[startInclusive, endExclusive)` pair for the year
     * containing [timestamp], optionally shifted by [yearOffset] years.
     *
     * @param timestamp Reference time.
     * @param yearOffset 0 for current year, -1 for previous year, etc.
     */
    fun getYearRange(timestamp: Long, yearOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (yearOffset != 0) {
            cal.add(Calendar.YEAR, yearOffset)
        }

        val start = getStartOfYear(cal.timeInMillis)
        val end = getEndOfYear(cal.timeInMillis)
        return start to end
    }

    /**
     * Returns a half-open `[start, end)` range for a specific calendar [year].
     *
     * The start is January 1 at `00:00:00.000`; the end is January 1 of the
     * **next** year at `00:00:00.000`.
     */
    fun getYearRange(year: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return getYearRange(cal.timeInMillis)
    }

    // ============================================================================
    // ROLLING / LOOKBACK RANGES
    // ============================================================================

    /**
     * Returns a pair of (start, end) timestamps for the last [days] days.
     *
     * The start is the beginning of the day exactly [days] calendar days before [now].
     * The end is [now] itself (raw, not day-aligned).
     *
     * @param now Current timestamp (pass `timeProvider.now()`).
     * @param days Number of calendar days to look back.
     */
    fun getLastNDaysRange(now: Long, days: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_MONTH, -days)
        val start = getStartOfDay(cal.timeInMillis)
        return start to now
    }

    // ============================================================================
    // FIELD ACCESSORS & DAY/MONTH QUERIES
    // ============================================================================

    /**
     * Returns the number of days remaining in the month (excluding the current day).
     */
    fun getDaysRemainingInMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        return daysInMonth - dayOfMonth
    }

    /**
     * Returns the day of month (1–31) for the given [timestamp].
     */
    fun getDayOfMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Returns the number of days in the month containing [timestamp].
     */
    fun getDaysInMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * Returns the 0-based day index from the start of the month.
     * E.g. the 1st returns 0, the 15th returns 14.
     */
    fun getDayIndexFromMonthStart(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return (cal.get(Calendar.DAY_OF_MONTH) - 1).coerceAtLeast(0)
    }

    /**
     * Checks whether two timestamps fall in the same calendar month and year.
     */
    fun isSameMonth(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)
    }

    // ============================================================================
    // CALENDAR-AWARE ARITHMETIC
    // ============================================================================

    /**
     * Adds [months] calendar months to [timestamp] using [Calendar.add].
     * Handles month-end coercion (e.g. Jan 31 + 1 month → Feb 28/29).
     */
    fun addMonths(timestamp: Long, months: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    /**
     * Adds [days] calendar days to [timestamp] using [Calendar.add].
     * DST-safe: correctly handles 23-hour and 25-hour days.
     */
    fun addDays(timestamp: Long, days: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.DAY_OF_MONTH, days)
        return cal.timeInMillis
    }

    /**
     * Adds [years] calendar years to [timestamp] using [Calendar.add].
     */
    fun addYears(timestamp: Long, years: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.YEAR, years)
        return cal.timeInMillis
    }

    // ============================================================================
    // FIELD EXTRACTORS
    // ============================================================================

    /**
     * Returns the calendar year for [timestamp].
     */
    fun getYear(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.YEAR)
    }

    /**
     * Returns the calendar month for [timestamp] (0 = January, 11 = December).
     */
    fun getMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.MONTH)
    }

    /**
     * Returns the week-of-year for [timestamp], using Monday as the first day
     * of the week and requiring a minimum of **1** day in the first week.
     *
     * This definition is **always consistent with [getYear]**: the week number
     * and calendar year are read from the same [Calendar] instance, so pairing
     * them (e.g. `"${getYear(ts)}-W${getWeekOfYear(ts)}"`) is safe at every
     * year boundary.
     *
     * For example, 2021-01-01 (Friday) returns week **1 of 2021** under this
     * definition (the week containing Jan 1 is always week 1), which matches
     * `getYear` → 2021.
     *
     * This is **locale-independent**: `firstDayOfWeek` is explicitly set to
     * Monday regardless of the device locale.
     *
     * Note: [getWeekBasedYear] / [getWeekOfYear] still exist for callers that
     * need strict ISO-8601 week numbering; migrate those callers separately.
     */
    fun getWeekOfYear(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
        }
        return cal.get(Calendar.WEEK_OF_YEAR)
    }

    /**
     * Returns the **ISO week-based year** for [timestamp].
     *
     * This is the year that corresponds to the ISO week returned by
     * [getWeekOfYear]. Around New Year boundaries the ISO week-based year can
     * differ from the calendar year returned by [getYear]:
     *
     * - 2021-01-01 (Friday) → ISO week 53 of **2020** → `getWeekBasedYear` = 2020
     * - 2020-12-31 (Thursday) → ISO week 53 of **2020** → `getWeekBasedYear` = 2020
     *
     * **Always use this together with [getWeekOfYear]** when constructing a
     * week-scoped key such as `"${getWeekBasedYear(ts)}-W${getWeekOfYear(ts)}"`.
     *
     * Existing callers that still pair [getYear] with [getWeekOfYear] are
     * incorrect at year boundaries and will be migrated in a later batch.
     */
    fun getWeekBasedYear(timestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val isoWeekBasedYearField = WeekFields.of(DayOfWeek.MONDAY, 4).weekBasedYear()
        return localDate.get(isoWeekBasedYearField)
    }

    /**
     * Returns the day of week using [Calendar] constants (SUNDAY = 1 … SATURDAY = 7).
     */
    fun getDayOfWeek(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    /**
     * Returns the hour of day (0–23) for [timestamp].
     */
    fun getHourOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    // ============================================================================
    // CALENDAR-DAY DIFFERENCE (DST-safe)
    // ============================================================================

    /**
     * Returns the number of **calendar days** between [startTimestamp] and [endTimestamp].
     *
     * Time-of-day and DST differences are ignored: both timestamps are first
     * converted to `java.time.LocalDate` in the system default timezone, then
     * the day difference is computed.
     *
     * Positive when [endTimestamp] is after [startTimestamp].
     */
    fun daysBetween(startTimestamp: Long, endTimestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startTimestamp).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endTimestamp).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(startDate, endDate).toInt()
    }
}
