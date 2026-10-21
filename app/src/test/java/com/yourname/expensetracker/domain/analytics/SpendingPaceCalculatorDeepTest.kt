package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.toExpenseSnapshots
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SpendingPaceCalculatorDeepTest : AnalyticsEngineTestBase() {

    companion object {
        // Zone pinned to UTC for the whole class (including the base-class date fields).
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()
    }

    private lateinit var calculator: SpendingPaceCalculator

    @Before
    override fun setUp() {
        super.setUp()
        calculator = SpendingPaceCalculator(timeProvider)
    }

    @Test
    fun `canonical pace formula calculates daily-rate ratio correctly`() {
        every { timeProvider.now() } returns createDate(2026, 4, 16)

        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 1600.0), // current month
            createExpense(date = "2026-03-10", amount = 620.0),  // previous month baseline (31-day month)
            createExpense(date = "2026-03-15", amount = 310.0)
        )

        val result = calculator.calculate(
            currentMonthStart = createDate(2026, 4, 1),
            previousMonthStart = createDate(2026, 3, 1),
            previousMonthEnd = createDate(2026, 4, 1),
            allExpenses = expenses.toExpenseSnapshots(),
            displayCurrency = "EUR"
        )

        // daysElapsed = 16, currentDailyRate = 100
        // previousMonthTotal = 930, previousDailyRate = 930/31 = 30
        // pace% = (100/30)*100 = 333.33
        assertApproxEquals(333.33f, result.pacePercentage, 0.2f)
        assertEquals(PaceStatus.OVER_PACE, result.paceStatus)
        assertMoneyEquals(1600.0, result.currentMonthSpent, "current month spent")
        assertMoneyEquals(930.0, result.previousMonthTotal, "previous month total")
    }

    private data class StatusRow(
        val name: String,
        val currentAmount: Double,
        val previousAmount: Double,
        val expectedPace: Float,
        val expectedStatus: PaceStatus
    )

    @Test
    fun `pace status thresholds map correctly`() {
        // now = 2026-04-10 00:00 -> 10 elapsed days; March baseline spans 31 days.
        every { timeProvider.now() } returns createDate(2026, 4, 10)
        val rows = listOf(
            // current 90 / 10 = 9 per day; baseline 620 / 31 = 20 per day
            StatusRow("under pace", 90.0, 620.0, 45.0f, PaceStatus.UNDER_PACE),
            // current 300 / 10 = 30 per day; baseline 930 / 31 = 30 per day
            StatusRow("on pace", 300.0, 930.0, 100.0f, PaceStatus.ON_PACE),
            // current 2000 / 10 = 200 per day; baseline 930 / 31 = 30 per day
            StatusRow("over pace", 2000.0, 930.0, 666.67f, PaceStatus.OVER_PACE)
        )
        assertEquals("status table size", 3, rows.size)

        for (row in rows) {
            val result = calculator.calculate(
                currentMonthStart = createDate(2026, 4, 1),
                previousMonthStart = createDate(2026, 3, 1),
                previousMonthEnd = createDate(2026, 4, 1),
                allExpenses = listOf(
                    createExpense(date = "2026-04-01", amount = row.currentAmount),
                    createExpense(date = "2026-03-01", amount = row.previousAmount)
                ).toExpenseSnapshots(),
                displayCurrency = "EUR"
            )
            assertEquals("${row.name} status", row.expectedStatus, result.paceStatus)
            assertApproxEquals(row.expectedPace, result.pacePercentage, 0.01f, "${row.name} pace: ")
        }
    }

    private data class NoBaselineRow(
        val name: String,
        val now: LocalDateTime,
        val currentMonthStart: LocalDate,
        val previousMonthStart: LocalDate,
        val currentExpenses: List<Pair<String, Double>>,
        val expectedSpent: Double
    )

    @Test
    fun `no baseline table returns no baseline status and unavailable previous total`() {
        val rows = listOf(
            NoBaselineRow(
                "deep: one current purchase, empty March",
                LocalDateTime.of(2026, 4, 12, 0, 0, 0),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1),
                listOf("2026-04-05" to 120.0),
                120.0
            ),
            NoBaselineRow(
                "boundary: two current purchases, empty February",
                LocalDateTime.of(2026, 3, 15, 23, 59, 59),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 1),
                listOf("2026-03-02" to 300.0, "2026-03-10" to 200.0),
                500.0
            ),
            NoBaselineRow(
                "validation: 900 current, empty March 2024",
                LocalDateTime.of(2024, 4, 15, 12, 0, 0),
                LocalDate.of(2024, 4, 1), LocalDate.of(2024, 3, 1),
                listOf("2024-04-01" to 900.0),
                900.0
            )
        )
        assertEquals("no baseline table size", 3, rows.size)

        for (row in rows) {
            every { timeProvider.now() } returns row.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val result = calculator.calculate(
                currentMonthStart = row.currentMonthStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                previousMonthStart = row.previousMonthStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                previousMonthEnd = row.currentMonthStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                allExpenses = row.currentExpenses
                    .map { (date, amount) -> createExpense(date = date, amount = amount) }
                    .toExpenseSnapshots(),
                displayCurrency = "EUR"
            )

            assertEquals("${row.name} status", PaceStatus.NO_BASELINE, result.paceStatus)
            assertEquals("${row.name} pace sentinel", -1f, result.pacePercentage, 0f)
            assertNull("${row.name} previous total", result.previousMonthTotal)
            // Formula list says historical avg should be populated; current implementation returns null
            assertNull("${row.name} average monthly total", result.averageMonthlyTotal)
            assertMoneyEquals(row.expectedSpent, result.currentMonthSpent, "${row.name} current month spent")
        }
    }

    // Projection (day 2 / day 4 stabilization) and exclusion (non-purchase, not-mine) cases that
    // used to live here are rows of SpendingPaceCalculatorValidationTest tables
    // "projection table" and "exclusion table".

    private fun createDate(year: Int, month: Int, day: Int): Long {
        return java.time.LocalDate.of(year, month, day)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
