package com.yourname.expensetracker.domain.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Calendar
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange

// M11 FIXED: Week helpers now explicitly split into ISO-8601 and app-calendar
// families. Old ambiguous pairing (getWeekOfYear + getWeekBasedYear) is
// deprecated. Caller migration is deferred to a later PR.

/**
 * Canonical owner of all shared calendar boundary math for the ExpenseTracker app.
 *
 * ## Pure utility — no internal clock access
 *
 * This object **NEVER calls the system clock** internally. Every function is
 * seeded from an explicit timestamp parameter (`now`, `timestamp`, `date`, etc.).
 * The `Calendar.getInstance()` calls inside this utility create a new `Calendar`
 * instance and then immediately set its `timeInMillis` to the **caller-provided**
 * timestamp — they do NOT fetch the current wall-clock time.
 *
 * This design means [TimePeriodUtils] can always be tested with arbitrary
 * timestamps without dependency injection, and is safe to use from any thread
 * without mocking.
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
 * ### Pre-Gregorian compatibility seam
 * `java.time` is a **proleptic** Gregorian calendar: it applies Gregorian rules
 * and historical timezone offsets (Local Mean Time) to every date, including
 * dates before 1582. The legacy `java.util.Calendar` (`GregorianCalendar`)
 * instead switches to the **Julian** calendar before its default cutover
 * (`1582-10-15T00:00:00Z`, see `GREGORIAN_CUTOVER_EPOCH_MILLIS`) and applies
 * the timezone's standard offset.
 *
 * [getStartOfDay] and [getEndOfDay] honor that legacy behavior: timestamps
 * strictly before the cutover are delegated to the private
 * `legacyStartOfDay`/`legacyEndOfDay` helpers so the pre-migration `Calendar`
 * results are reproduced exactly, while modern (post-cutover) timestamps keep
 * the java.time implementation. The two paths agree for every post-cutover
 * timestamp the app stores; they can diverge only for pre-1582 dates (Julian
 * date interpretation and offset rules) and at the representational `Long`
 * extremes.
 *
 * ### Supported epoch range
 * All helpers accept any `Long` epoch-millis value: `Instant.ofEpochMilli` maps
 * the full `Long` range onto `java.time.Instant` without throwing, and
 * java.time's date conversion covers the same range. Day-boundary helpers such
 * as [getStartOfDay] and [getEndOfDay] require the *result* to fit in a `Long`
 * epoch-millis when the java.time path is used.
 *
 * ### Controlled failure at the `Long` extremes
 * - `getEndOfDay(Long.MAX_VALUE)` — next-day midnight lies after the latest
 *   representable epoch-millis, so the java.time path fails **deterministically**
 *   with `ArithmeticException` (overflow inside `Instant.toEpochMilli()`); it
 *   never silently wraps or returns undefined values.
 * - `getStartOfDay(Long.MIN_VALUE)` — `Long.MIN_VALUE` lies far before the
 *   cutover, so it is handled by the legacy Calendar seam and returns
 *   `Calendar`'s deterministic result (no exception).
 *
 * Every other `Long` input yields a deterministic result. Realistic app data
 * (roughly year 1–9999) is always inside the supported range.
 *
 * @see daysBetween for DST-safe calendar-day difference via `java.time.LocalDate`.
 * @see TimeProvider for the single source of "now" that callers should use.
 * @see PeriodRange for the typed period model in `domain.core.time`.
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
    // DAY BOUNDARIES  (half-open [start, next-start))
    //
    // Modern timestamps use java.time (LocalDate.atStartOfDay / plusDays(1)).
    // Pre-Gregorian-cutover timestamps delegate to the legacy Calendar
    // implementation through the private seam helpers below so the pre-migration
    // behavior is reproduced exactly. See the class docs.
    // ============================================================================

    /**
     * Epoch-millis instant of the legacy [java.util.GregorianCalendar]
     * Julian-to-Gregorian cutover: `1582-10-15T00:00:00Z` (`-12_219_292_800_000`).
     *
     * This is the hardcoded default cutover used by `Calendar.getInstance()`
     * (`GregorianCalendar.DEFAULT_GREGORIAN_CUTOVER`). Timestamps strictly
     * before this instant are rendered by the legacy [Calendar] under the
     * **Julian** calendar with the timezone's standard offset, whereas
     * `java.time` (proleptic Gregorian, historical LMT offsets) can diverge.
     */
    private const val GREGORIAN_CUTOVER_EPOCH_MILLIS: Long = -12219292800000L

    /**
     * Returns `true` when [timestamp] lies strictly before the legacy
     * [java.util.GregorianCalendar] cutover (`GREGORIAN_CUTOVER_EPOCH_MILLIS`,
     * `1582-10-15T00:00:00Z`). The cutover instant itself is Gregorian, so the
     * check is exclusive (`timestamp < cutover`).
     *
     * See the class docs, "Pre-Gregorian compatibility seam".
     */
    private fun isBeforeGregorianCutover(timestamp: Long): Boolean {
        return timestamp < GREGORIAN_CUTOVER_EPOCH_MILLIS
    }

    /**
     * Legacy [Calendar]-based start of day (`00:00:00.000` local): the exact
     * algorithm [getStartOfDay] used before the java.time migration. Used by the
     * pre-Gregorian compatibility seam for timestamps before
     * `GREGORIAN_CUTOVER_EPOCH_MILLIS` so the old Julian-calendar / standard
     * offset behavior is reproduced exactly.
     */
    private fun legacyStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Legacy [Calendar]-based end of day: start of the **next** day
     * (`00:00:00.000` local, exclusive). Used by the pre-Gregorian compatibility
     * seam for timestamps before `GREGORIAN_CUTOVER_EPOCH_MILLIS`.
     */
    private fun legacyEndOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfDay(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * Returns the start of the day (`00:00:00.000`) for the given [timestamp]
     * in the system default timezone.
     *
     * Modern (post-cutover) timestamps use `java.time`: the local date is
     * derived from the instant and converted back with
     * `LocalDate.atStartOfDay(zone)`. This preserves the previous `Calendar`
     * semantics for those dates exactly — local midnight, DST-aware, no fixed
     * `DAY_IN_MILLIS` arithmetic.
     *
     * **Pre-Gregorian compatibility seam:** timestamps strictly before the
     * legacy `GregorianCalendar` cutover (`1582-10-15T00:00:00Z`, see
     * `GREGORIAN_CUTOVER_EPOCH_MILLIS`) are delegated to the private
     * `legacyStartOfDay` helper, reproducing the pre-migration `Calendar`
     * behavior exactly (Julian calendar rules and the timezone's standard
     * offset). See the class docs for the full seam description.
     *
     * @throws ArithmeticException in the java.time path if the resulting local
     * midnight cannot be represented as a `Long` epoch-millis (see class docs
     * for the supported epoch range and controlled failure).
     */
    fun getStartOfDay(timestamp: Long): Long {
        if (isBeforeGregorianCutover(timestamp)) {
            return legacyStartOfDay(timestamp)
        }
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return localDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Returns the **exclusive** upper bound of the day containing [timestamp]:
     * the start of the **next** day (`00:00:00.000`).
     *
     * Half-open contract: a timestamp `t` is in this day when
     * `t >= getStartOfDay(ts) && t < getEndOfDay(ts)`.
     *
     * Modern (post-cutover) timestamps use `java.time`: the end is the start of
     * the **next** local date (`LocalDate.plusDays(1).atStartOfDay(zone)`), so a
     * day spanning a DST transition is naturally 23 or 25 hours — never a fixed
     * `DAY_IN_MILLIS`.
     *
     * **Pre-Gregorian compatibility seam:** timestamps strictly before the
     * legacy `GregorianCalendar` cutover (`1582-10-15T00:00:00Z`, see
     * `GREGORIAN_CUTOVER_EPOCH_MILLIS`) are delegated to the private
     * `legacyEndOfDay` helper, reproducing the pre-migration `Calendar`
     * behavior exactly. See the class docs for the full seam description.
     *
     * @throws ArithmeticException in the java.time path if the resulting
     * next-day midnight cannot be represented as a `Long` epoch-millis — by
     * construction the case `timestamp == Long.MAX_VALUE` (see class docs for
     * the supported epoch range and controlled failure).
     */
    fun getEndOfDay(timestamp: Long): Long {
        if (isBeforeGregorianCutover(timestamp)) {
            return legacyEndOfDay(timestamp)
        }
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
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
     * Returns the **exclusive** upper bound of the ISO week containing [timestamp]:
     * the **next** Monday at `00:00:00.000`.
     *
     * This always uses ISO week boundaries (Monday-start, locale-independent),
     * not an app-configured week start day.
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
     * Returns a `[startInclusive, endExclusive)` pair for the ISO calendar week
     * containing [timestamp], optionally shifted by [weekOffset] weeks.
     *
     * This always uses ISO week boundaries (Monday-start, locale-independent),
     * not an app-configured week start day.
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

    /**
     * Derives canonical Monday-start week bounds from a persisted week key.
     *
     * Supported key formats:
     * - `%Y-%W` (SQLite week index, Monday-based, 00-53)
     * - `%Y-%U` (SQLite week index, Sunday-based, 00-53; normalized to Monday-start)
     *
     * Returns a half-open `[startInclusive, endExclusive)` range where start is Monday midnight
     * and end is next Monday midnight.
     */
    fun getCanonicalWeekRangeFromKey(weekKey: String): Pair<Long, Long> {
        val parts = weekKey.split("-")
        require(parts.size == 2) { "Invalid weekKey format: $weekKey" }

        val year = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid week year: $weekKey")
        val week = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid week index: $weekKey")
        require(week in 0..53) { "Invalid week index (expected 00-53): $weekKey" }

        val firstMondayOfYear = getFirstMondayOfYear(year)
        val canonicalStart = if (week == 0) {
            addDays(firstMondayOfYear, -7)
        } else {
            addDays(firstMondayOfYear, (week - 1) * 7)
        }
        val canonicalEnd = addDays(canonicalStart, 7)
        return canonicalStart to canonicalEnd
    }

    private fun getFirstMondayOfYear(year: Int): Long {
        val jan1 = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dayOfWeek = jan1.get(Calendar.DAY_OF_WEEK)
        val daysUntilMonday = (Calendar.MONDAY - dayOfWeek + 7) % 7
        jan1.add(Calendar.DAY_OF_MONTH, daysUntilMonday)
        return jan1.timeInMillis
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

    /**
     * Formats a timestamp as canonical month key (`yyyy-MM`).
     */
    fun formatMonthKey(timestamp: Long): String {
        val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return formatMonthKey(
            year = zoned.year,
            month = zoned.monthValue
        )
    }

    /**
     * Formats year/month (1-based month) as canonical month key (`yyyy-MM`).
     */
    fun formatMonthKey(year: Int, month: Int): String {
        require(month in 1..12) { "Invalid month: $month" }
        return String.format("%04d-%02d", year, month)
    }

    /**
     * Parses canonical month key (`yyyy-MM`) into `(year, month)` (1-based month).
     */
    fun parseMonthKey(monthKey: String): Pair<Int, Int> {
        val parts = monthKey.split("-")
        require(parts.size == 2) { "Unexpected month key: $monthKey" }

        val year = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Unexpected month key: $monthKey")
        val month = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Unexpected month key: $monthKey")
        require(month in 1..12) { "Unexpected month key: $monthKey" }

        return year to month
    }

    /**
     * Builds an inclusive month-key range (`yyyy-MM`) from [startMonthKey] to [endMonthKey].
     */
    fun buildMonthKeyRange(startMonthKey: String, endMonthKey: String): List<String> {
        val (startYear, startMonth) = parseMonthKey(startMonthKey)
        val (endYear, endMonth) = parseMonthKey(endMonthKey)

        val start = YearMonth.of(startYear, startMonth)
        val end = YearMonth.of(endYear, endMonth)

        require(!start.isAfter(end)) {
            "startMonthKey must be <= endMonthKey: $startMonthKey > $endMonthKey"
        }

        val monthKeys = mutableListOf<String>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            monthKeys.add(formatMonthKey(cursor.year, cursor.monthValue))
            cursor = cursor.plusMonths(1)
        }
        return monthKeys
    }

    // ============================================================================
    // WEEK KEY AND DAY KEY PARSING
    // ============================================================================

    /**
     * Parses a week key of format `yyyy-Www` into the epoch-ms start of that ISO week.
     * Returns null if the key cannot be parsed.
     */
    fun parseWeekKeyToStart(weekKey: String): Long? {
        return try {
            val parts = weekKey.split("-W")
            if (parts.size != 2) return null
            val year = parts[0].toIntOrNull() ?: return null
            val weekNum = parts[1].toIntOrNull() ?: return null
            val jan4 = java.time.LocalDate.of(year, 1, 4)
            val weekOneStart = getStartOfWeek(jan4.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            addDays(weekOneStart, (weekNum - 1) * 7)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a day key of format `yyyy-MM-dd` into the epoch-ms start of that day.
     * Returns null if the key cannot be parsed.
     */
    fun parseDayKeyToStart(dayKey: String): Long? {
        return try {
            val date = java.time.LocalDate.parse(dayKey, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
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
    @Deprecated(
        message = "Ambiguous semantics. Use getLastNCalendarDaysRange for calendar days including today, " +
                "getLastNCompleteDaysRange for complete days excluding today, " +
                "or getTrailingElapsedRange for exact elapsed intervals.",
        replaceWith = ReplaceWith("getLastNCalendarDaysRange(now, days)")
    )
    fun getLastNDaysRange(now: Long, days: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_MONTH, -days)
        val start = getStartOfDay(cal.timeInMillis)
        return start to now
    }

    /**
     * Parses a canonical month key (`yyyy-MM`) into a half-open `[start, end)` range.
     *
     * Delegates to [parseMonthKey] for key parsing and [getMonthRange] for the
     * actual boundary computation.
     *
     * @param monthKey Month key in `yyyy-MM` format (e.g. `"2026-04"`).
     * @return A `[startInclusive, endExclusive)` pair for the specified month.
     * @throws IllegalArgumentException if the key format is invalid.
     */
    fun parseMonthKeyToRange(monthKey: String): Pair<Long, Long> {
        val (year, month) = parseMonthKey(monthKey)
        return getMonthRange(year, month)
    }

    /**
     * Returns a half-open `[start, end)` range covering the last [days] calendar
     * days **including today**.
     *
     * The start is midnight of the day [days]-1 days ago. The end is midnight
     * of the day **after** [now] (exclusive), giving you full calendar days.
     *
     * Example: for `now = Apr 10 15:30` and `days = 3`:
     * - Start = Apr 8 00:00
     * - End   = Apr 11 00:00 (tomorrow's start)
     *
     * For calendar days **not** including today (i.e. complete days ending at
     * today's start), see [getLastNCompleteDaysRange].
     *
     * @param now Reference timestamp (typically `timeProvider.now()`).
     * @param days Number of calendar days to include (must be >= 1).
     */
    fun getLastNCalendarDaysRange(now: Long, days: Int): Pair<Long, Long> {
        require(days >= 1) { "days must be >= 1, was $days" }
        val start = addDays(getStartOfDay(now), -(days - 1))
        val end = getEndOfDay(now)
        return start to end
    }

    /**
     * Returns a half-open `[start, end)` range covering [days] complete calendar
     * days **ending at the start of today**.
     *
     * "Complete" means the range does not include the current partial day.
     * This is useful for "last N full days" reports where today's incomplete
     * data should be excluded.
     *
     * Example: for `now = Apr 10 15:30` and `days = 3`:
     * - Start = Apr 7 00:00
     * - End   = Apr 10 00:00 (today's start, exclusive)
     *
     * For calendar days **including** today, see [getLastNCalendarDaysRange].
     *
     * @param now Reference timestamp.
     * @param days Number of complete days (must be >= 1).
     */
    fun getLastNCompleteDaysRange(now: Long, days: Int): Pair<Long, Long> {
        require(days >= 1) { "days must be >= 1, was $days" }
        val start = addDays(getStartOfDay(now), -days)
        val end = getStartOfDay(now)
        return start to end
    }

    /**
     * Returns a half-open `[start, end)` range covering exactly [durationMs]
     * milliseconds of elapsed time ending at [now].
     *
     * **Not for calendar-aligned reports.** This is a pure wall-clock interval
     * computed as `now - durationMs` to `now`. It does not round to day
     * boundaries and is NOT DST-safe for long durations spanning DST transitions.
     * Use calendar helpers such as [getLastNCalendarDaysRange] or
     * [getLastNCompleteDaysRange] for day-aligned ranges.
     *
     * @param now Reference timestamp (end of the interval).
     * @param durationMs Duration in milliseconds (must be >= 0).
     */
    fun getTrailingElapsedRange(now: Long, durationMs: Long): Pair<Long, Long> {
        require(durationMs >= 0) { "durationMs must be >= 0, was $durationMs" }
        val start = now - durationMs
        return start to now
    }

    /**
     * Returns a 0-based day index within a period for sparkline or bucketing
     * placement.
     *
     * The index is computed as [daysBetween]`(periodStart, timestamp)`, clamped
     * to `>= 0`. For example, if [periodStart] is April 1 and [timestamp] is
     * April 3, the result is 2 (the third day).
     *
     * @param timestamp The timestamp to locate within the period.
     * @param periodStart The start of the period (inclusive).
     * @return 0-based day offset from [periodStart], minimum 0.
     */
    fun getDayIndexForSparkline(timestamp: Long, periodStart: Long): Int {
        return daysBetween(periodStart, timestamp).coerceAtLeast(0)
    }

    // ============================================================================
    // FIELD ACCESSORS & DAY/MONTH QUERIES
    // ============================================================================

    /**
     * Returns the number of days remaining in the month (excluding the current day).
     */
    fun getDaysRemainingInMonth(timestamp: Long): Int {
        val localDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        return localDate.lengthOfMonth() - localDate.dayOfMonth
    }

    /**
     * Returns the day of month (1–31) for the given [timestamp].
     */
    fun getDayOfMonth(timestamp: Long): Int {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).dayOfMonth
    }

    /**
     * Returns the number of days in the month containing [timestamp].
     */
    fun getDaysInMonth(timestamp: Long): Int {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().lengthOfMonth()
    }

    /**
     * Returns the 0-based day index from the start of the month.
     * E.g. the 1st returns 0, the 15th returns 14.
     */
    fun getDayIndexFromMonthStart(timestamp: Long): Int {
        val dayOfMonth = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).dayOfMonth
        return (dayOfMonth - 1).coerceAtLeast(0)
    }

    /**
     * Checks whether two timestamps fall in the same calendar month and year.
     */
    fun isSameMonth(timestamp1: Long, timestamp2: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val zoned1 = Instant.ofEpochMilli(timestamp1).atZone(zone)
        val zoned2 = Instant.ofEpochMilli(timestamp2).atZone(zone)
        return zoned1.year == zoned2.year && zoned1.monthValue == zoned2.monthValue
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
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year
    }

    /**
     * Returns the calendar month for [timestamp] (0 = January, 11 = December).
     */
    fun getMonth(timestamp: Long): Int {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).monthValue - 1
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
     * @deprecated Use [getIsoWeekNumber] for ISO-8601 week numbering or
     * [getAppCalendarWeekNumber] for app-calendar week numbering (identical to
     * this implementation). Do not mix week numbering systems. Scheduled for
     * removal in a future PR after all callers have been migrated.
     */
    @Deprecated(
        "Use getIsoWeekNumber() for ISO-8601 week numbering or getAppCalendarWeekNumber() for app calendar week numbering. " +
        "Do not mix week numbering systems.",
        ReplaceWith("getAppCalendarWeekNumber(timestamp)")
    )
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
     *
     * @deprecated Use [getIsoWeekBasedYear] instead (identical implementation,
     * renamed for clarity). Scheduled for removal in a future PR after all
     * callers have been migrated.
     */
    @Deprecated(
        "Use getIsoWeekBasedYear() for ISO-8601 week-based year or getAppCalendarWeekYear() for app calendar year. " +
        "Do not mix week numbering systems.",
        ReplaceWith("getIsoWeekBasedYear(timestamp)")
    )
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
        return when (Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).dayOfWeek) {
            DayOfWeek.SUNDAY -> Calendar.SUNDAY
            DayOfWeek.MONDAY -> Calendar.MONDAY
            DayOfWeek.TUESDAY -> Calendar.TUESDAY
            DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
            DayOfWeek.THURSDAY -> Calendar.THURSDAY
            DayOfWeek.FRIDAY -> Calendar.FRIDAY
            DayOfWeek.SATURDAY -> Calendar.SATURDAY
        }
    }

    // ============================================================================
    // ISO-8601 WEEK HELPERS
    // ============================================================================

    /**
     * Returns the ISO-8601 week number for [timestamp].
     *
     * Week 1 is the first week that contains at least 4 days in the new year.
     * Monday is the first day of the week.
     *
     * Always pair with [getIsoWeekBasedYear] when constructing a week-scoped key.
     */
    fun getIsoWeekNumber(timestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val isoWeekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
        return localDate.get(isoWeekFields.weekOfWeekBasedYear())
    }

    /**
     * Returns the ISO-8601 week-based year for [timestamp].
     *
     * This is the year that corresponds to the ISO week number from
     * [getIsoWeekNumber]. Around New Year boundaries this can differ from
     * the calendar year returned by [getYear].
     *
     * Always pair with [getIsoWeekNumber] when constructing a week-scoped key.
     */
    fun getIsoWeekBasedYear(timestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val isoWeekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
        return localDate.get(isoWeekFields.weekBasedYear())
    }

    /**
     * Returns a week-scoped key using the ISO-8601 week definition.
     *
     * Format: `YYYY-WNN` (e.g., `"2020-W53"`).
     */
    fun getIsoWeekKey(timestamp: Long): String {
        val year = getIsoWeekBasedYear(timestamp)
        val week = getIsoWeekNumber(timestamp).toString().padStart(2, '0')
        return "${year}-W${week}"
    }

    // ============================================================================
    // APP-CALENDAR WEEK HELPERS
    // ============================================================================

    /**
     * Returns the app-calendar week number for [timestamp].
     *
     * Monday is the first day of the week, and week 1 is the week containing
     * January 1. This is **always consistent with [getAppCalendarWeekYear]**.
     *
     * Always pair with [getAppCalendarWeekYear] when constructing a week-scoped key.
     */
    fun getAppCalendarWeekNumber(timestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val appCalendarWeekFields = WeekFields.of(DayOfWeek.MONDAY, 1)
        return localDate.get(appCalendarWeekFields.weekOfYear())
    }

    /**
     * Returns the app-calendar week year for [timestamp].
     *
     * This is the calendar year, and is **always consistent with
     * [getAppCalendarWeekNumber]** at every year boundary.
     */
    fun getAppCalendarWeekYear(timestamp: Long): Int {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year
    }

    /**
     * Returns a week-scoped key using the app-calendar week definition.
     *
     * Format: `YYYY-WNN` (e.g., `"2021-W01"`).
     */
    fun getAppCalendarWeekKey(timestamp: Long): String {
        val year = getAppCalendarWeekYear(timestamp)
        val week = getAppCalendarWeekNumber(timestamp).toString().padStart(2, '0')
        return "${year}-W${week}"
    }

    /**
     * Returns the hour of day (0–23) for [timestamp].
     */
    fun getHourOfDay(timestamp: Long): Int {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).hour
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

    // ============================================================================
    // TYPED PERIOD CONVERSION
    // ============================================================================

    /**
     * Converts an untyped [Pair]<[Long],[Long]> range into a typed [PeriodRange].
     *
     * This is a convenience wrapper for callers that have existing [Pair] results
     * from [TimePeriodUtils] helpers and need to pass them through typed APIs.
     *
     * @param pair The `(startInclusive, endExclusive)` pair from a range helper.
     * @param kind The semantic kind of this period.
     * @param label Optional human-readable label (defaults to empty string).
     */
    fun toPeriodRange(pair: Pair<Long, Long>, kind: PeriodKind, label: String = ""): PeriodRange {
        return PeriodRange(
            kind = kind,
            startInclusiveMillis = pair.first,
            endExclusiveMillis = pair.second,
            label = label
        )
    }
}
