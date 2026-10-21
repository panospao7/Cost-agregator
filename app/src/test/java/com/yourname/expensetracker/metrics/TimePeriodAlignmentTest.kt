package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.testCurrencyConverter
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import com.yourname.expensetracker.domain.util.PinnedTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/**
 * Ensures TimePeriodUtils, BudgetCalculator, and AdvancedAnalyticsEngine
 * use consistent [startInclusive, endExclusive) boundaries for month, week,
 * and year periods.
 *
 * Time-zone policy: no test depends on the JVM default zone. The whole class runs with
 * the default zone pinned to [ZONE] by [pinnedZone] (process-wide
 * [GlobalTimeZoneTestLock] held for the duration of each test, original zone restored
 * afterwards), so the default-zone [ts] helper, [BudgetCalculator] and the engine all
 * resolve in the same explicit zone.
 */
class TimePeriodAlignmentTest {

    private companion object {
        const val ZONE = "Europe/Athens"
    }

    @get:Rule
    val pinnedZone = PinnedTimeZoneRule(ZONE)

    private val timeProvider = mockk<TimeProvider>()
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var advancedAnalyticsEngine: AdvancedAnalyticsEngine

    private fun ts(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setup() {
        every { timeProvider.now() } returns ts(2024, 5, 15)
        budgetCalculator = BudgetCalculator(timeProvider)
        val currencySettingsRepository = TestCurrencySettingsRepository()
        val currencyConverter = testCurrencyConverter()
        advancedAnalyticsEngine = AdvancedAnalyticsEngine(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            currencySettingsRepository,
            testAnalyticsCurrencyNormalizer(currencyConverter),
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )
    }

    // ============================================================================
    // MONTH ALIGNMENT
    // ============================================================================

    @Test
    fun `alignment - TimePeriodUtils and AdvancedAnalyticsEngine month start match`() {
        val ref = ts(2024, 5, 15)
        val utilsStart = TimePeriodUtils.getStartOfMonth(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        assertEquals("Month start must match", utilsStart, advRange.startMs)
    }

    @Test
    fun `alignment - TimePeriodUtils getMonthRange and AdvancedAnalyticsEngine month end match`() {
        val ref = ts(2024, 5, 15)
        val (utilsStart, utilsEnd) = TimePeriodUtils.getMonthRange(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        assertEquals("Month start must match", utilsStart, advRange.startMs)
        // Both must use the same exclusive end boundary (start of next month)
        assertEquals("Month end must match (half-open exclusive)", utilsEnd, advRange.endMs)
    }

    @Test
    fun `alignment - BudgetCalculator MONTHLY with anchor day 1 matches calendar month`() {
        val ref = ts(2024, 5, 15)
        val anchorFirstOfMonth = TimePeriodUtils.getStartOfMonth(ref)
        val budgetRange = budgetCalculator.calculatePeriodWindow(
            BudgetPeriod.MONTHLY,
            anchorFirstOfMonth
        )
        val utilsStart = TimePeriodUtils.getStartOfMonth(ref)
        val utilsEnd = TimePeriodUtils.getEndOfMonth(ref)
        assertEquals("Budget MONTHLY start should match", utilsStart, budgetRange.startInclusiveMillis)
        assertTrue("Budget MONTHLY end should be start of next month",
            budgetRange.endExclusiveMillis >= utilsEnd && budgetRange.endExclusiveMillis <= utilsEnd + 86400000)
    }

    @Test
    fun `alignment - week start TimePeriodUtils and AdvancedAnalyticsEngine match`() {
        val ref = ts(2024, 5, 15)
        val utilsStart = TimePeriodUtils.getStartOfWeek(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
        assertEquals("Week start must match", utilsStart, advRange.startMs)
    }

    // Exact wall-clock week durations in the pinned zone (Europe/Athens, 2024 DST:
    // spring-forward Sun Mar 31, fall-back Sun Oct 27). The week is Monday 00:00 to the
    // next Monday 00:00, so a week containing a transition is 167h / 169h.
    private data class WeekDurationRow(val name: String, val reference: LocalDateTime, val expectedHours: Long)

    @Test
    fun `alignment - week duration table is exactly 7 days except 167h and 169h across DST`() {
        val rows = listOf(
            WeekDurationRow("non-DST week of Jun 10 2024", LocalDateTime.of(2024, 6, 15, 12, 0), 168L),
            WeekDurationRow("spring-forward week of Mar 25 2024", LocalDateTime.of(2024, 3, 27, 12, 0), 167L),
            WeekDurationRow("fall-back week of Oct 21 2024", LocalDateTime.of(2024, 10, 23, 12, 0), 169L)
        )
        assertEquals("week duration table size", 3, rows.size)

        val zone = ZoneId.of(ZONE)
        for (row in rows) {
            val ref = row.reference.atZone(zone).toInstant().toEpochMilli()
            val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
            assertEquals(
                "${row.name}: exact week duration [$ZONE]",
                row.expectedHours * 60 * 60 * 1000L,
                advRange.endMs - advRange.startMs
            )
        }
    }

    // ============================================================================
    // EDGE CASES - Month boundaries (half-open: endExclusive = 1st of next month)
    // ============================================================================

    // Table (merged from the former "first day of month", "last day of February 2024
    // (leap)", "last day of February 2023 (non-leap)", "December 31 year boundary" and
    // "January 1 year boundary" tests; the two Jan 1 tests were the same scenario and
    // are one row). Calendar-field assertions became exact local-midnight instants in
    // [ZONE], which are at least as strict. Each row asserts the helpers and the range.
    private data class MonthEdgeRow(
        val name: String,
        val reference: LocalDateTime,
        val expectedStart: LocalDateTime,
        val expectedEndExclusive: LocalDateTime
    )

    @Test
    fun `edge table - month start and exclusive end at month and year boundaries`() {
        val rows = listOf(
            MonthEdgeRow("Jan 1 2024: end is exclusive Feb 1", LocalDateTime.of(2024, 1, 1, 12, 0), LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 2, 1, 0, 0)),
            MonthEdgeRow("Feb 29 2024 (leap): end is exclusive Mar 1", LocalDateTime.of(2024, 2, 29, 12, 0), LocalDateTime.of(2024, 2, 1, 0, 0), LocalDateTime.of(2024, 3, 1, 0, 0)),
            MonthEdgeRow("Feb 28 2023 (non-leap): end is exclusive Mar 1", LocalDateTime.of(2023, 2, 28, 12, 0), LocalDateTime.of(2023, 2, 1, 0, 0), LocalDateTime.of(2023, 3, 1, 0, 0)),
            MonthEdgeRow("Dec 31 2024: end is exclusive Jan 1 2025", LocalDateTime.of(2024, 12, 31, 12, 0), LocalDateTime.of(2024, 12, 1, 0, 0), LocalDateTime.of(2025, 1, 1, 0, 0))
        )
        assertEquals("month edge table size", 4, rows.size)

        val zone = ZoneId.of(ZONE)
        for (row in rows) {
            val ref = row.reference.atZone(zone).toInstant().toEpochMilli()
            val expectedStart = row.expectedStart.atZone(zone).toInstant().toEpochMilli()
            val expectedEnd = row.expectedEndExclusive.atZone(zone).toInstant().toEpochMilli()
            assertEquals("${row.name}: getStartOfMonth [$ZONE]", expectedStart, TimePeriodUtils.getStartOfMonth(ref))
            assertEquals("${row.name}: getEndOfMonth [$ZONE]", expectedEnd, TimePeriodUtils.getEndOfMonth(ref))
            assertEquals("${row.name}: getMonthRange [$ZONE]", expectedStart to expectedEnd, TimePeriodUtils.getMonthRange(ref))
        }
    }

    // ============================================================================
    // HALF-OPEN CONTRACT TESTS
    // ============================================================================

    private data class ContiguityRow(
        val name: String,
        val range: (Long, Int) -> Pair<Long, Long>,
        val offsets: IntRange
    )

    // Table (merged from the former month / week / year / quarter contiguity tests; the
    // former "stress - previous month range does not overlap current" check is the
    // month row's offset -1 -> 0 pair). Offsets per row are unchanged.
    @Test
    fun `contract table - month week year and quarter ranges are contiguous and non-overlapping`() {
        val rows = listOf(
            ContiguityRow("Month", { ref, offset -> TimePeriodUtils.getMonthRange(ref, offset) }, -6..5),
            ContiguityRow("Week", { ref, offset -> TimePeriodUtils.getWeekRange(ref, offset) }, -4..3),
            ContiguityRow("Year", { ref, offset -> TimePeriodUtils.getYearRange(ref, offset) }, -2..1),
            ContiguityRow("Quarter", { ref, offset -> TimePeriodUtils.getQuarterRange(ref, offset) }, -4..3)
        )
        assertEquals("contiguity table size", 4, rows.size)

        val ref = ts(2024, 5, 15)
        for (row in rows) {
            for (offset in row.offsets) {
                val (_, currEnd) = row.range(ref, offset)
                val (nextStart, _) = row.range(ref, offset + 1)
                assertEquals("${row.name} $offset end must equal ${row.name} ${offset + 1} start (contiguous half-open) [$ZONE]",
                    currEnd, nextStart)
            }
        }
    }

    // ============================================================================
    // STRESS - Multiple months
    // ============================================================================

    @Test
    fun `stress - 24 months getMonthRange consistent`() {
        val ref = ts(2024, 5, 15)
        for (offset in -12..11) {
            val (start, end) = TimePeriodUtils.getMonthRange(ref, offset)
            assertTrue("Start < end for offset $offset", start < end)
            val durationDays = TimePeriodUtils.daysBetween(start, end)
            assertTrue("Month should be 28-31 days (got $durationDays for offset $offset)",
                durationDays in 28..31)
        }
    }

    @Test
    fun `stress - getStartOfMonth idempotent`() {
        val ref = ts(2024, 5, 15)
        val s1 = TimePeriodUtils.getStartOfMonth(ref)
        val s2 = TimePeriodUtils.getStartOfMonth(s1)
        assertEquals(s1, s2)
    }

    // ============================================================================
    // NEW HELPERS ALIGNMENT
    // ============================================================================

    @Test
    fun `alignment - getDayRange matches getStartOfDay and getEndOfDay`() {
        val ref = ts(2024, 5, 15)
        val (dayStart, dayEnd) = TimePeriodUtils.getDayRange(ref)
        assertEquals(TimePeriodUtils.getStartOfDay(ref), dayStart)
        assertEquals(TimePeriodUtils.getEndOfDay(ref), dayEnd)
    }

    @Test
    fun `alignment - getEndOfWeek matches getWeekRange end`() {
        val ref = ts(2024, 5, 15)
        val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(ref)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(ref)
        assertEquals("getEndOfWeek must match getWeekRange end", weekRangeEnd, endOfWeek)
    }

    @Test
    fun `alignment - isInRange canonical containment for month boundary`() {
        val ref = ts(2024, 5, 15)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(ref)

        // ref is within its own month range
        assertTrue("ref should be in its own month range",
            TimePeriodUtils.isInRange(ref, monthStart, monthEnd))

        // monthStart is included (half-open start-inclusive)
        assertTrue("monthStart should be in month range",
            TimePeriodUtils.isInRange(monthStart, monthStart, monthEnd))

        // monthEnd is excluded (half-open end-exclusive)
        assertTrue("monthEnd (exclusive) should NOT be in month range",
            !TimePeriodUtils.isInRange(monthEnd, monthStart, monthEnd))
    }

    @Test
    fun `alignment - isInRange canonical containment for week boundary`() {
        val ref = ts(2024, 5, 15)
        val weekStart = TimePeriodUtils.getStartOfWeek(ref)
        val weekEnd = TimePeriodUtils.getEndOfWeek(ref)

        assertTrue("ref should be in its own week range",
            TimePeriodUtils.isInRange(ref, weekStart, weekEnd))
        assertTrue("Monday midnight should be in week range",
            TimePeriodUtils.isInRange(weekStart, weekStart, weekEnd))
        assertTrue("Next Monday (exclusive) should NOT be in week range",
            !TimePeriodUtils.isInRange(weekEnd, weekStart, weekEnd))
    }
}
