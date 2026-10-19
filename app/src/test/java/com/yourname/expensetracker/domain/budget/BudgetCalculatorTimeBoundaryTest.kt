package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * T4B-3: Real-path boundary coverage for [BudgetCalculator]'s month-anchoring /
 * day-coercion logic after migrating from `java.util.Calendar` to
 * `TimePeriodUtils` / `java.time`.
 *
 * B6-4: this class also owns every MONTHLY / YEARLY anchored-window row that used to be
 * spread over BudgetCalculatorTest and BudgetCalculatorBoundaryTest. Rows are tagged with
 * their origin (`TB` = this class, `BCT` = BudgetCalculatorTest, `B` =
 * BudgetCalculatorBoundaryTest; `TB` also covers the neighbour boundaries added in B6-4).
 *
 * The JVM default zone is pinned to UTC for the whole class ([PinnedUtcTimeZoneRule]) and
 * every fixture instant is built in an explicit zone, so no assertion depends on the host
 * zone and no wall-clock reads are involved. Multi-zone rows (DST) switch the default zone
 * under [GlobalTimeZoneTestLock] and restore UTC.
 *
 * Covered requirements:
 * - Jan 31 previous-month cycle;
 * - leap February (Feb 29 anchor, leap and non-leap evaluation years);
 * - month-end / day-of-month coercion;
 * - DST transition crossed by a budget period;
 * - exact start-inclusive / end-exclusive boundaries;
 * - invalid range behavior (invalid period mode never silently defaults, and
 *   no computed window is ever inverted or missing the evaluation time).
 */
class BudgetCalculatorTimeBoundaryTest {

    companion object {
        // Zone pinned to UTC for the whole class: BudgetCalculator reads ZoneId.systemDefault().
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val UTC: ZoneId = ZoneId.of("UTC")
        private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    @Before
    fun setup() {
        every { timeProvider.now() } returns 0L
        calculator = BudgetCalculator(timeProvider)
    }

    // ============================================================================
    // Fixtures
    // ============================================================================

    /** Fixed instant for the given local date/time in [zone] (explicit, never the default). */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, zone: ZoneId = UTC): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Fixed instant for local midnight of the given date in [zone]. */
    private fun dayStart(year: Int, month: Int, day: Int, zone: ZoneId = UTC): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun budget(periodMode: String, period: BudgetPeriod, startDate: Long): Budget =
        Budget(
            id = 1L,
            categoryId = null,
            amount = 1000.0,
            period = period,
            periodMode = periodMode,
            startDate = startDate
        )

    private data class WindowRow(
        val name: String,
        val period: BudgetPeriod,
        val anchor: Long,
        val evaluationTime: Long,
        val expectedStart: Long,
        val expectedEndExclusive: Long
    )

    /**
     * Evaluates every row against the production calculator and reports ALL mismatching rows
     * (named) in one failure so a single bad row never masks the others.
     */
    private fun assertWindowTable(table: String, expectedRowCount: Int, rows: List<WindowRow>) {
        assertEquals("$table: row count guard", expectedRowCount, rows.size)
        assertEquals("$table: row names must be unique", rows.size, rows.map { it.name }.toSet().size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val window = calculator.calculatePeriodWindowForTime(row.period, row.anchor, row.evaluationTime)
            if (window.startInclusiveMillis != row.expectedStart) {
                failures += "[$table/${row.name}] start expected ${Instant.ofEpochMilli(row.expectedStart)} " +
                    "but was ${Instant.ofEpochMilli(window.startInclusiveMillis)}"
            }
            if (window.endExclusiveMillis != row.expectedEndExclusive) {
                failures += "[$table/${row.name}] end expected ${Instant.ofEpochMilli(row.expectedEndExclusive)} " +
                    "but was ${Instant.ofEpochMilli(window.endExclusiveMillis)}"
            }
            if (!window.contains(row.evaluationTime)) {
                failures += "[$table/${row.name}] window [${Instant.ofEpochMilli(window.startInclusiveMillis)}, " +
                    "${Instant.ofEpochMilli(window.endExclusiveMillis)}) must contain the evaluation time " +
                    "${Instant.ofEpochMilli(row.evaluationTime)}"
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    /**
     * Switches the JVM-wide default timezone to [zoneId] for [block], holding the
     * process-wide [GlobalTimeZoneTestLock] for the whole mutation so parallel test
     * classes never observe a foreign default zone. The previous timezone (UTC, pinned by
     * the class rule) is always restored inside the lock, even when [block] throws.
     */
    private fun <T> withDefaultTimeZone(zoneId: String, block: () -> T): T =
        GlobalTimeZoneTestLock.withLock {
            val previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            try {
                block()
            } finally {
                TimeZone.setDefault(previous)
            }
        }

    // ============================================================================
    // Jan 31 previous-month cycles
    // ============================================================================

    @Test
    fun `T4B3 monthly anchor jan 31 resolves previous month start table`() {
        assertWindowTable(
            table = "jan31",
            expectedRowCount = 5,
            rows = listOf(
                // Feb 15 < coerced anchor (28) in Feb 2025 -> the cycle started in January.
                WindowRow("TB non-leap 2025", BudgetPeriod.MONTHLY, at(2025, 1, 31, 9, 0), at(2025, 2, 15, 10, 0),
                    dayStart(2025, 1, 31), dayStart(2025, 2, 28)),
                // Feb 15 < coerced anchor (29) in Feb 2024 -> Jan 31 - Feb 29.
                WindowRow("TB leap 2024", BudgetPeriod.MONTHLY, at(2024, 1, 31, 9, 0), at(2024, 2, 15, 10, 0),
                    dayStart(2024, 1, 31), dayStart(2024, 2, 29)),
                WindowRow("BCT leap 2024 midnight", BudgetPeriod.MONTHLY, at(2024, 1, 31, 0, 0), at(2024, 2, 15, 0, 0),
                    dayStart(2024, 1, 31), dayStart(2024, 2, 29)),
                WindowRow("BCT non-leap 2023 midnight", BudgetPeriod.MONTHLY, at(2023, 1, 31, 0, 0), at(2023, 2, 15, 0, 0),
                    dayStart(2023, 1, 31), dayStart(2023, 2, 28)),
                WindowRow("B non-leap 2026 coerces february boundary", BudgetPeriod.MONTHLY,
                    at(2026, 1, 31, 9, 0), at(2026, 2, 15, 10, 0),
                    dayStart(2026, 1, 31), dayStart(2026, 2, 28))
            )
        )
    }

    // ============================================================================
    // Leap February
    // ============================================================================

    @Test
    fun `T4B3 monthly leap february anchor cycles table`() {
        assertWindowTable(
            table = "leapFeb",
            expectedRowCount = 4,
            rows = listOf(
                // Mar 15 < anchor day 29 -> previous month: Feb 29 - Mar 29 2024.
                WindowRow("TB feb 29 anchor full leap cycle", BudgetPeriod.MONTHLY,
                    at(2024, 2, 29, 0, 0), at(2024, 3, 15, 12, 0),
                    dayStart(2024, 2, 29), dayStart(2024, 3, 29)),
                WindowRow("BCT feb 29 anchor handles leap year", BudgetPeriod.MONTHLY,
                    at(2024, 2, 29, 0, 0), at(2024, 3, 15, 0, 0),
                    dayStart(2024, 2, 29), dayStart(2024, 3, 29)),
                // Feb 28 < coerced anchor (29) in Feb 2024 -> Jan 29 (anchor day clamped to
                // January's 31 days) - Feb 29.
                WindowRow("TB evaluation before anchor day uses previous month with clamped start", BudgetPeriod.MONTHLY,
                    at(2024, 2, 29, 0, 0), at(2024, 2, 28, 12, 0),
                    dayStart(2024, 1, 29), dayStart(2024, 2, 29)),
                // Anchor day 29 coerces to Feb 28 in the non-leap year, then returns to 29 in March.
                WindowRow("B leap anchor day 29 coerces to feb 28 in non-leap year then returns to 29", BudgetPeriod.MONTHLY,
                    at(2024, 3, 29, 8, 0), at(2025, 2, 28, 12, 0),
                    dayStart(2025, 2, 28), dayStart(2025, 3, 29))
            )
        )
    }

    @Test
    fun `T4B3 yearly feb 29 anchor clamps and recovers table`() {
        assertWindowTable(
            table = "yearly",
            expectedRowCount = 6,
            rows = listOf(
                // June 2025 is after the 2025 anniversary, which is clamped to Feb 28
                // because 2025 is not a leap year.
                WindowRow("TB feb 29 anchor clamps to feb 28 in non leap year", BudgetPeriod.YEARLY,
                    at(2024, 2, 29, 0, 0), at(2025, 6, 15, 12, 0),
                    dayStart(2025, 2, 28), dayStart(2026, 2, 28)),
                WindowRow("BCT jan 1 anchor returns 12 month window", BudgetPeriod.YEARLY,
                    at(2024, 1, 1, 0, 0), at(2024, 6, 15, 0, 0),
                    dayStart(2024, 1, 1), dayStart(2025, 1, 1)),
                // Feb 28 2025 is the (clamped) anniversary itself: already passed (inclusive start).
                WindowRow("BCT feb 29 anchor treated as passed on feb 28 in non leap year", BudgetPeriod.YEARLY,
                    at(2024, 2, 29, 0, 0), at(2025, 2, 28, 12, 0),
                    dayStart(2025, 2, 28), dayStart(2026, 2, 28)),
                // The day before the clamped anniversary still belongs to the 2024 cycle.
                WindowRow("TB feb 27 2025 is before the clamped anniversary", BudgetPeriod.YEARLY,
                    at(2024, 2, 29, 0, 0), at(2025, 2, 27, 12, 0),
                    dayStart(2024, 2, 29), dayStart(2025, 2, 28)),
                // The next leap year recovers the real Feb 29 anniversary.
                WindowRow("TB 2028 leap year recovers feb 29", BudgetPeriod.YEARLY,
                    at(2024, 2, 29, 0, 0), at(2028, 3, 1, 12, 0),
                    dayStart(2028, 2, 29), dayStart(2029, 2, 28)),
                // The cycle ending in the leap year ends on the real Feb 29.
                WindowRow("TB 2027 cycle ends on leap feb 29 2028", BudgetPeriod.YEARLY,
                    at(2024, 2, 29, 0, 0), at(2027, 6, 1, 12, 0),
                    dayStart(2027, 2, 28), dayStart(2028, 2, 29))
            )
        )
    }

    // ============================================================================
    // Month-end / day-of-month coercion
    // ============================================================================

    @Test
    fun `T4B3 monthly month-end coercion table`() {
        assertWindowTable(
            table = "monthEnd",
            expectedRowCount = 5,
            rows = listOf(
                // Apr 15 < coerced anchor (30) in Apr 2024 -> Mar 31 - Apr 30.
                WindowRow("TB mar 31 to apr 30", BudgetPeriod.MONTHLY,
                    at(2024, 3, 31, 0, 0), at(2024, 4, 15, 12, 0),
                    dayStart(2024, 3, 31), dayStart(2024, 4, 30)),
                WindowRow("BCT mar 31 month coercion midnight", BudgetPeriod.MONTHLY,
                    at(2024, 3, 31, 0, 0), at(2024, 4, 15, 0, 0),
                    dayStart(2024, 3, 31), dayStart(2024, 4, 30)),
                WindowRow("TB anchor day 30 coerces to feb 29 in leap year", BudgetPeriod.MONTHLY,
                    at(2024, 1, 30, 0, 0), at(2024, 2, 15, 12, 0),
                    dayStart(2024, 1, 30), dayStart(2024, 2, 29)),
                WindowRow("TB anchor day 30 coerces to feb 28 in non-leap year", BudgetPeriod.MONTHLY,
                    at(2025, 1, 30, 0, 0), at(2025, 2, 15, 12, 0),
                    dayStart(2025, 1, 30), dayStart(2025, 2, 28)),
                // Dec 31 anchor evaluated Jan 15: cycle started in the previous YEAR.
                WindowRow("BCT dec 31 anchor year boundary", BudgetPeriod.MONTHLY,
                    at(2023, 12, 31, 0, 0), at(2024, 1, 15, 0, 0),
                    dayStart(2023, 12, 31), dayStart(2024, 1, 31))
            )
        )
    }

    // ============================================================================
    // Anchor-day cycle derivation (current / year wrap / historical / next / future)
    // ============================================================================

    @Test
    fun `T4B3 monthly anchor day cycle derivation table`() {
        assertWindowTable(
            table = "cycles",
            expectedRowCount = 6,
            rows = listOf(
                WindowRow("BCT respects anchor day 15", BudgetPeriod.MONTHLY,
                    at(2024, 1, 15, 10, 0), at(2024, 1, 20, 10, 0),
                    dayStart(2024, 1, 15), dayStart(2024, 2, 15)),
                WindowRow("BCT wraps around year", BudgetPeriod.MONTHLY,
                    at(2023, 12, 15, 0, 0), at(2023, 12, 20, 0, 0),
                    dayStart(2023, 12, 15), dayStart(2024, 1, 15)),
                // Anchor day 10; evaluate on Feb 15 2024 -> window is Feb 10 - Mar 10 2024.
                WindowRow("BCT historical window when evaluation time is in the past", BudgetPeriod.MONTHLY,
                    at(2023, 11, 10, 0, 0), at(2024, 2, 15, 12, 0),
                    dayStart(2024, 2, 10), dayStart(2024, 3, 10)),
                // Anchor day 10; evaluate on Mar 12 2024 -> window is Mar 10 - Apr 10 2024.
                WindowRow("BCT next window when evaluation time advances past current cycle", BudgetPeriod.MONTHLY,
                    at(2023, 11, 10, 0, 0), at(2024, 3, 12, 12, 0),
                    dayStart(2024, 3, 10), dayStart(2024, 4, 10)),
                // Aug 20 >= anchor day 15, so cycle is Aug 15 - Sep 15.
                WindowRow("B explicit past evaluation time", BudgetPeriod.MONTHLY,
                    at(2025, 6, 15, 0, 0), at(2025, 8, 20, 10, 0),
                    dayStart(2025, 8, 15), dayStart(2025, 9, 15)),
                // Jan 10 < anchor day 15 -> cycle started in the previous month: Dec 15, 2026.
                WindowRow("B explicit future evaluation time", BudgetPeriod.MONTHLY,
                    at(2025, 6, 15, 0, 0), at(2027, 1, 10, 10, 0),
                    dayStart(2026, 12, 15), dayStart(2027, 1, 15))
            )
        )
    }

    // ============================================================================
    // DST transition crossed by a budget period
    // ============================================================================

    @Test
    fun `T4B3 monthly window crossing DST spring forward keeps local midnight boundaries`() {
        withDefaultTimeZone("America/New_York") {
            val anchor = at(2026, 2, 15, 0, 0, NEW_YORK)
            val evaluationTime = at(2026, 2, 20, 12, 0, NEW_YORK)

            val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

            // Both boundaries are local midnights; the window crosses the
            // 2026-03-08 spring-forward (a 23-hour day).
            assertEquals(dayStart(2026, 2, 15, NEW_YORK), window.startInclusiveMillis)
            assertEquals(dayStart(2026, 3, 15, NEW_YORK), window.endExclusiveMillis)

            // Calendar-aware arithmetic only: the wall-clock duration must NOT equal
            // a fixed 28 * DAY_MS when a DST transition shortens the period.
            val duration = window.endExclusiveMillis - window.startInclusiveMillis
            val fixedTwentyEightDays = 28 * TimePeriodUtils.DAY_IN_MILLIS
            assertTrue(
                "Duration must be calendar-aware across DST (got $duration)",
                duration != fixedTwentyEightDays
            )
            // Exact: 28 calendar days minus the one hour lost at spring-forward.
            assertEquals(fixedTwentyEightDays - 3_600_000L, duration)
        }
    }

    // ============================================================================
    // Exact start-inclusive / end-exclusive boundaries
    // ============================================================================

    @Test
    fun `T4B3 exact boundaries are half-open start inclusive end exclusive`() {
        val anchor = at(2026, 1, 15, 0, 0)
        val evaluationTime = at(2026, 2, 10, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        assertEquals(dayStart(2026, 1, 15), window.startInclusiveMillis)
        assertEquals(dayStart(2026, 2, 15), window.endExclusiveMillis)

        assertTrue("start boundary is inclusive", window.contains(window.startInclusiveMillis))
        assertTrue("evaluation time is contained", window.contains(evaluationTime))
        assertTrue("one millisecond before end is contained", window.contains(window.endExclusiveMillis - 1L))
        assertTrue("end boundary is exclusive", !window.contains(window.endExclusiveMillis))
    }

    // ============================================================================
    // Invalid range behavior
    // ============================================================================

    @Test
    fun `T4B3 invalid period mode throws instead of silently becoming calendar`() {
        val now = at(2026, 3, 15, 14, 0)
        every { timeProvider.now() } returns now
        val invalid = budget(periodMode = "QUARTERLY_BOGUS", period = BudgetPeriod.MONTHLY, startDate = now)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            calculator.calculatePeriodRange(invalid)
        }
        assertTrue("reason must be a controlled code", ex.message.orEmpty().contains("Invalid periodMode"))
    }

    @Test
    fun `T4B3 windows stay well formed and contain evaluation time across boundary dates`() {
        val anchorDates = listOf(
            at(2024, 1, 31, 0, 0),
            at(2024, 2, 29, 0, 0),
            at(2025, 2, 28, 0, 0),
            at(2024, 3, 31, 0, 0),
            at(2023, 12, 31, 0, 0),
            at(2024, 1, 1, 0, 0)
        )
        val evaluationTimes = listOf(
            at(2024, 2, 15, 12, 0),
            at(2024, 2, 28, 12, 0),
            at(2024, 2, 29, 12, 0),
            at(2024, 4, 15, 12, 0),
            at(2025, 2, 28, 12, 0),
            at(2026, 12, 31, 23, 59)
        )
        val periods = listOf(BudgetPeriod.MONTHLY, BudgetPeriod.YEARLY)

        var combinations = 0
        for (period in periods) {
            for (anchor in anchorDates) {
                for (evaluationTime in evaluationTimes) {
                    combinations++
                    val window = calculator.calculatePeriodWindowForTime(period, anchor, evaluationTime)

                    assertTrue(
                        "window must never be inverted for period=$period anchor=$anchor eval=$evaluationTime",
                        window.startInclusiveMillis < window.endExclusiveMillis
                    )
                    assertTrue(
                        "evaluation time must be contained for period=$period anchor=$anchor eval=$evaluationTime",
                        window.contains(evaluationTime)
                    )
                }
            }
        }
        // Size guard: 2 periods x 6 anchors x 6 evaluation times.
        assertEquals("sweep combination count", 72, combinations)
    }
}
