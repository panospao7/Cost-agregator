package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * B6-4: `calculatePeriodRange` mode routing (CALENDAR vs ROLLING) with exact range instants.
 *
 * Fixed-length DAILY / WEEKLY windows and the convenience-wrapper delegation live in
 * [BudgetCalculatorTest]; MONTHLY / YEARLY anchored windows live in
 * [BudgetCalculatorTimeBoundaryTest]. Row tags: `BCT` = former BudgetCalculatorTest,
 * `B` = former BudgetCalculatorBoundaryTest, `P6-016` = B3-4 weekly rows.
 *
 * The JVM default zone is pinned to UTC for the class ([PinnedUtcTimeZoneRule]) and every
 * fixture instant is built in an explicit UTC zone.
 */
class BudgetCalculatorBoundaryTest {

    companion object {
        // Zone pinned to UTC for the whole class: BudgetCalculator reads ZoneId.systemDefault().
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val UTC: ZoneId = ZoneId.of("UTC")
    }

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    @Before
    fun setUp() {
        every { timeProvider.now() } returns 0L
        calculator = BudgetCalculator(timeProvider)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(UTC).toInstant().toEpochMilli()

    private fun dayStart(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(UTC).toInstant().toEpochMilli()

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
     * @property explicitNow true when the original test passed `now` straight to
     * `calculatePeriodRange(budget, now)`; false when it drove `now` through the TimeProvider
     * (exercising the default-parameter path).
     */
    private data class RangeRow(
        val name: String,
        val periodMode: String,
        val period: BudgetPeriod,
        val startDate: Long,
        val now: Long,
        val expectedStart: Long,
        val expectedEndExclusive: Long,
        val explicitNow: Boolean = false
    )

    private fun assertRangeTable(table: String, expectedRowCount: Int, rows: List<RangeRow>) {
        assertEquals("$table: row count guard", expectedRowCount, rows.size)
        assertEquals("$table: row names must be unique", rows.size, rows.map { it.name }.toSet().size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val budget = budget(row.periodMode, row.period, row.startDate)
            val (start, end) = if (row.explicitNow) {
                // Prove the explicit parameter wins over the provider.
                every { timeProvider.now() } returns 0L
                calculator.calculatePeriodRange(budget, row.now)
            } else {
                every { timeProvider.now() } returns row.now
                calculator.calculatePeriodRange(budget)
            }
            if (start != row.expectedStart) {
                failures += "[$table/${row.name}] start expected ${Instant.ofEpochMilli(row.expectedStart)} " +
                    "but was ${Instant.ofEpochMilli(start)}"
            }
            if (end != row.expectedEndExclusive) {
                failures += "[$table/${row.name}] end expected ${Instant.ofEpochMilli(row.expectedEndExclusive)} " +
                    "but was ${Instant.ofEpochMilli(end)}"
            }
            if (start >= end) {
                failures += "[$table/${row.name}] range must not be empty or inverted (start=$start end=$end)"
            }
            if (!(start <= row.now && row.now < end)) {
                failures += "[$table/${row.name}] now ${Instant.ofEpochMilli(row.now)} must be inside the half-open range"
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    @Test
    fun `calendar mode ranges ignore the anchor and use natural boundaries table`() {
        assertRangeTable(
            table = "calendar",
            expectedRowCount = 8,
            rows = listOf(
                // Anchor is in March, but CALENDAR yearly ignores it: Jan 1 2026 .. Jan 1 2027.
                RangeRow("BCT calendar yearly resolves Jan 1 to Jan 1 regardless of anchor",
                    "CALENDAR", BudgetPeriod.YEARLY, at(2025, 3, 15), at(2026, 6, 10, 14, 0),
                    dayStart(2026, 1, 1), dayStart(2027, 1, 1)),
                RangeRow("B calendar yearly resolves Jan 1 to Jan 1 ignoring mid-year anchor",
                    "CALENDAR", BudgetPeriod.YEARLY, at(2024, 7, 1), at(2026, 9, 15, 10, 0),
                    dayStart(2026, 1, 1), dayStart(2027, 1, 1)),
                RangeRow("B calendar yearly on Jan 1 itself returns full year",
                    "CALENDAR", BudgetPeriod.YEARLY, at(2025, 6, 15), at(2026, 1, 1, 0, 0),
                    dayStart(2026, 1, 1), dayStart(2027, 1, 1)),
                RangeRow("B calendar yearly on Dec 31 returns current year",
                    "CALENDAR", BudgetPeriod.YEARLY, at(2025, 1, 1), at(2026, 12, 31, 23, 59),
                    dayStart(2026, 1, 1), dayStart(2027, 1, 1)),
                // The anchor (now - 1 day) is irrelevant for CALENDAR daily.
                RangeRow("BCT calendar daily returns the day range",
                    "CALENDAR", BudgetPeriod.DAILY, at(2026, 4, 14, 14, 30), at(2026, 4, 15, 14, 30),
                    dayStart(2026, 4, 15), dayStart(2026, 4, 16)),
                // Empty (legacy) period mode falls back to CALENDAR.
                RangeRow("B empty period mode falls back to calendar mode and returns valid monthly range",
                    "", BudgetPeriod.MONTHLY, at(2026, 2, 10), at(2026, 3, 15, 14, 0),
                    dayStart(2026, 3, 1), dayStart(2026, 4, 1)),
                // Dec 27 2026 is a Sunday: its calendar-mode (ISO Monday-start) budget period
                // is Mon Dec 21 - Mon Dec 28, not the week starting the following Monday.
                RangeRow("P6-016 calendar weekly places a Sunday expense in the week that began the previous Monday",
                    "CALENDAR", BudgetPeriod.WEEKLY, at(2026, 1, 1), at(2026, 12, 27, 15, 0),
                    dayStart(2026, 12, 21), dayStart(2026, 12, 28), explicitNow = true),
                RangeRow("P6-016 calendar weekly places Monday midnight in the new week",
                    "CALENDAR", BudgetPeriod.WEEKLY, at(2026, 1, 1), at(2026, 12, 28, 0, 0),
                    dayStart(2026, 12, 28), dayStart(2027, 1, 4), explicitNow = true)
            )
        )
    }

    @Test
    fun `calendar daily range equals the TimePeriodUtils day range for the same instant`() {
        // Parity with the shared period utility: CALENDAR DAILY must resolve exactly the
        // [start of day, start of next day) range that TimePeriodUtils.getDayRange yields.
        val now = at(2026, 4, 15, 14, 30)
        every { timeProvider.now() } returns now
        val budget = budget("CALENDAR", BudgetPeriod.DAILY, at(2026, 4, 14, 14, 30))

        val range = calculator.calculatePeriodRange(budget)

        assertEquals("parity with TimePeriodUtils.getDayRange", TimePeriodUtils.getDayRange(now), range)
        assertEquals("hard-coded start", dayStart(2026, 4, 15), range.first)
        assertEquals("hard-coded end", dayStart(2026, 4, 16), range.second)
    }

    @Test
    fun `rolling mode ranges resolve the anchored cycle containing now table`() {
        assertRangeTable(
            table = "rolling",
            expectedRowCount = 4,
            rows = listOf(
                // Anchor day 31 and now = March 5 (before the 31st): the active cycle started
                // Feb 28 (31st coerced to the February length) and ends Mar 31.
                RangeRow("B rolling monthly resolves active anchored cycle containing now",
                    "ROLLING", BudgetPeriod.MONTHLY, at(2026, 1, 31), at(2026, 3, 5, 12, 0),
                    dayStart(2026, 2, 28), dayStart(2026, 3, 31)),
                // Anchor is in March; ROLLING yearly uses the anniversary, not Jan 1.
                RangeRow("BCT rolling yearly uses anchor anniversary not Jan 1",
                    "ROLLING", BudgetPeriod.YEARLY, at(2025, 3, 15), at(2026, 6, 10, 14, 0),
                    dayStart(2026, 3, 15), dayStart(2027, 3, 15)),
                // Anchor is July 1; now is Sep 2026, past Jul 1 2026 -> Jul 1 2026 .. Jul 1 2027.
                RangeRow("B rolling yearly anchored July 1 resolves correct anniversary cycle",
                    "ROLLING", BudgetPeriod.YEARLY, at(2024, 7, 1), at(2026, 9, 15, 10, 0),
                    dayStart(2026, 7, 1), dayStart(2027, 7, 1)),
                // Now is March 2026, before the Jul 1 anniversary -> Jul 1 2025 .. Jul 1 2026.
                RangeRow("B rolling yearly before anniversary date returns previous cycle",
                    "ROLLING", BudgetPeriod.YEARLY, at(2024, 7, 1), at(2026, 3, 1, 10, 0),
                    dayStart(2025, 7, 1), dayStart(2026, 7, 1))
            )
        )
    }
}
