package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * T4B-3: Real-path boundary coverage for [BudgetCalculator]'s month-anchoring /
 * day-coercion logic after migrating from `java.util.Calendar` to
 * `TimePeriodUtils` / `java.time`.
 *
 * All timestamps are fixed dates built with `java.time` in the system-default
 * timezone (the same zone BudgetCalculator uses), so no wall-clock reads are
 * involved and the tests are deterministic.
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

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Before
    fun setup() {
        every { timeProvider.now() } returns 0L
        calculator = BudgetCalculator(timeProvider)
    }

    // ============================================================================
    // Fixtures
    // ============================================================================

    /** Fixed instant for the given local date/time in the system-default zone. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Fixed instant for local midnight of the given date. */
    private fun dayStart(year: Int, month: Int, day: Int): Long =
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

    /**
     * Switches the JVM-wide default timezone to [zoneId] for [block], holding the
     * process-wide [GlobalTimeZoneTestLock] for the whole mutation so parallel test
     * classes never observe a foreign default zone. The original timezone is always
     * restored inside the lock, even when [block] throws.
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
    fun `T4B3 monthly anchor jan 31 resolves previous month start in non-leap year`() {
        val anchor = at(2025, 1, 31, 9, 0)
        val evaluationTime = at(2025, 2, 15, 10, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        // Feb 15 < coerced anchor (28) in Feb 2025 → the cycle started in January.
        assertEquals(dayStart(2025, 1, 31), window.startInclusiveMillis)
        assertEquals(dayStart(2025, 2, 28), window.endExclusiveMillis)
    }

    @Test
    fun `T4B3 monthly anchor jan 31 resolves previous month start in leap year`() {
        val anchor = at(2024, 1, 31, 9, 0)
        val evaluationTime = at(2024, 2, 15, 10, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        // Feb 15 < coerced anchor (29) in Feb 2024 → Jan 31 – Feb 29.
        assertEquals(dayStart(2024, 1, 31), window.startInclusiveMillis)
        assertEquals(dayStart(2024, 2, 29), window.endExclusiveMillis)
    }

    // ============================================================================
    // Leap February
    // ============================================================================

    @Test
    fun `T4B3 monthly feb 29 anchor full leap cycle`() {
        val anchor = at(2024, 2, 29, 0, 0)
        val evaluationTime = at(2024, 3, 15, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        // Mar 15 < anchor day 29 → previous month: Feb 29 – Mar 29 2024.
        assertEquals(dayStart(2024, 2, 29), window.startInclusiveMillis)
        assertEquals(dayStart(2024, 3, 29), window.endExclusiveMillis)
    }

    @Test
    fun `T4B3 monthly leap february evaluation before anchor day uses previous month with clamped start`() {
        val anchor = at(2024, 2, 29, 0, 0)
        val evaluationTime = at(2024, 2, 28, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        // Feb 28 < coerced anchor (29) in Feb 2024 → Jan 29 (anchor day clamped to
        // January's 31 days) – Feb 29.
        assertEquals(dayStart(2024, 1, 29), window.startInclusiveMillis)
        assertEquals(dayStart(2024, 2, 29), window.endExclusiveMillis)
    }

    @Test
    fun `T4B3 yearly feb 29 anchor clamps to feb 28 in non leap year`() {
        val anchor = at(2024, 2, 29, 0, 0)
        val evaluationTime = at(2025, 6, 15, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.YEARLY, anchor, evaluationTime)

        // June 2025 is after the 2025 anniversary, which is clamped to Feb 28
        // because 2025 is not a leap year.
        assertEquals(dayStart(2025, 2, 28), window.startInclusiveMillis)
        assertEquals(dayStart(2026, 2, 28), window.endExclusiveMillis)
    }

    // ============================================================================
    // Month-end / day-of-month coercion
    // ============================================================================

    @Test
    fun `T4B3 monthly month-end coercion mar 31 to apr 30`() {
        val anchor = at(2024, 3, 31, 0, 0)
        val evaluationTime = at(2024, 4, 15, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

        // Apr 15 < coerced anchor (30) in Apr 2024 → Mar 31 – Apr 30.
        assertEquals(dayStart(2024, 3, 31), window.startInclusiveMillis)
        assertEquals(dayStart(2024, 4, 30), window.endExclusiveMillis)
    }

    @Test
    fun `T4B3 monthly anchor day 30 coerces to feb 29 in leap and feb 28 in non-leap`() {
        val leapAnchor = at(2024, 1, 30, 0, 0)
        val leapWindow = calculator.calculatePeriodWindowForTime(
            BudgetPeriod.MONTHLY, leapAnchor, at(2024, 2, 15, 12, 0)
        )
        assertEquals(dayStart(2024, 1, 30), leapWindow.startInclusiveMillis)
        assertEquals(dayStart(2024, 2, 29), leapWindow.endExclusiveMillis)

        val nonLeapAnchor = at(2025, 1, 30, 0, 0)
        val nonLeapWindow = calculator.calculatePeriodWindowForTime(
            BudgetPeriod.MONTHLY, nonLeapAnchor, at(2025, 2, 15, 12, 0)
        )
        assertEquals(dayStart(2025, 1, 30), nonLeapWindow.startInclusiveMillis)
        assertEquals(dayStart(2025, 2, 28), nonLeapWindow.endExclusiveMillis)
    }

    // ============================================================================
    // DST transition crossed by a budget period
    // ============================================================================

    @Test
    fun `T4B3 monthly window crossing DST spring forward keeps local midnight boundaries`() {
        withDefaultTimeZone("America/New_York") {
            val anchor = at(2026, 2, 15, 0, 0)
            val evaluationTime = at(2026, 2, 20, 12, 0)

            val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evaluationTime)

            // Both boundaries are local midnights; the window crosses the
            // 2026-03-08 spring-forward (a 23-hour day).
            assertEquals(dayStart(2026, 2, 15), window.startInclusiveMillis)
            assertEquals(dayStart(2026, 3, 15), window.endExclusiveMillis)

            // Calendar-aware arithmetic only: the wall-clock duration must NOT equal
            // a fixed 28 * DAY_MS when a DST transition shortens the period.
            val fixedTwentyEightDays = 28 * TimePeriodUtils.DAY_IN_MILLIS
            assertTrue(
                "Duration must be calendar-aware across DST (got ${window.endExclusiveMillis - window.startInclusiveMillis})",
                window.endExclusiveMillis - window.startInclusiveMillis != fixedTwentyEightDays
            )
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

        for (period in listOf(BudgetPeriod.MONTHLY, BudgetPeriod.YEARLY)) {
            for (anchor in anchorDates) {
                for (evaluationTime in evaluationTimes) {
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
    }
}
