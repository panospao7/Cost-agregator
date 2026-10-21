package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Validation tests for SpendingPaceCalculator: pace ratio, projection, exclusion and
 * zero-spend behaviour, expressed as tables with hard-coded expected values.
 */
class SpendingPaceCalculatorValidationTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()
    }

    // ----- helpers (zone is pinned to UTC by [utcZone]) -----

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun snapshot(
        id: Long,
        amount: Double,
        date: LocalDate,
        type: DomainTransactionType = DomainTransactionType.PURCHASE,
        isNotMine: Boolean = false,
        effectiveAmount: Double = amount
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = "EUR",
        merchant = "Test Merchant",
        merchantKey = null,
        transactionType = type,
        date = millis(date),
        categoryId = null,
        isNotMine = isNotMine,
        transferDirection = null,
        notes = null
    )

    /** Fresh calculator and clock per row so rows cannot influence each other. */
    private fun calculate(
        now: LocalDateTime,
        monthStart: LocalDate,
        expenses: List<ExpenseSnapshot>
    ): SpendingPace {
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns millis(now)
        return SpendingPaceCalculator(timeProvider).calculate(
            currentMonthStart = millis(monthStart),
            previousMonthStart = millis(monthStart.minusMonths(1)),
            previousMonthEnd = millis(monthStart),
            allExpenses = expenses,
            displayCurrency = "EUR"
        )
    }

    // ========== Pace ratio table (daily-rate comparisons) ==========

    private data class PaceRow(
        val name: String,
        val now: LocalDateTime,
        val monthStart: LocalDate,
        val currentAmount: Double,
        val previousDate: LocalDate,
        val previousAmount: Double,
        val expectedSpent: Double,
        val expectedDaysElapsed: Int,
        val expectedDaysInMonth: Int,
        val expectedPreviousTotal: Double,
        val expectedProjected: Double,
        val expectedPace: Float,
        val expectedStatus: PaceStatus
    )

    @Test
    fun `pace ratio table compares current and previous daily rates`() {
        val apr2024 = LocalDate.of(2024, 4, 1)
        val rows = listOf(
            // 750 / 15 = 50 per day vs 1550 / 31 = 50 per day
            PaceRow(
                "equal daily rate", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024, 750.0,
                LocalDate.of(2024, 3, 15), 1550.0, 750.0, 15, 30, 1550.0, 1500.0, 100.0f, PaceStatus.ON_PACE
            ),
            // 1500 / 15 = 100 per day vs 50 per day
            PaceRow(
                "double daily rate", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024, 1500.0,
                LocalDate.of(2024, 3, 15), 1550.0, 1500.0, 15, 30, 1550.0, 3000.0, 200.0f, PaceStatus.OVER_PACE
            ),
            // 375 / 15 = 25 per day vs 50 per day
            PaceRow(
                "half daily rate", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024, 375.0,
                LocalDate.of(2024, 3, 15), 1550.0, 375.0, 15, 30, 1550.0, 750.0, 50.0f, PaceStatus.UNDER_PACE
            ),
            // 900 / 15 = 60 per day vs 50 per day
            PaceRow(
                "120 percent daily rate", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024, 900.0,
                LocalDate.of(2024, 3, 15), 1550.0, 900.0, 15, 30, 1550.0, 1800.0, 120.0f, PaceStatus.OVER_PACE
            ),
            // last day of month: projection equals actual spend; 1500 / 30 = 50 per day
            PaceRow(
                "last day of month", LocalDateTime.of(2024, 4, 30, 12, 0), apr2024, 1500.0,
                LocalDate.of(2024, 3, 15), 1550.0, 1500.0, 30, 30, 1550.0, 1500.0, 100.0f, PaceStatus.ON_PACE
            ),
            // February (28 days): 700 / 14 = 50 per day vs January 1550 / 31 = 50 per day
            PaceRow(
                "february 28 days", LocalDateTime.of(2023, 2, 14, 12, 0), LocalDate.of(2023, 2, 1), 700.0,
                LocalDate.of(2023, 1, 15), 1550.0, 700.0, 14, 28, 1550.0, 1400.0, 100.0f, PaceStatus.ON_PACE
            )
        )
        assertEquals("pace ratio table size", 6, rows.size)

        for (row in rows) {
            val result = calculate(
                now = row.now,
                monthStart = row.monthStart,
                expenses = listOf(
                    snapshot(1, row.currentAmount, row.monthStart),
                    snapshot(2, row.previousAmount, row.previousDate)
                )
            )

            assertMoneyEquals(row.expectedSpent, result.currentMonthSpent, "${row.name} spent")
            assertEquals("${row.name} daysElapsed", row.expectedDaysElapsed, result.daysElapsed)
            assertEquals("${row.name} daysInMonth", row.expectedDaysInMonth, result.daysInMonth)
            assertMoneyEquals(row.expectedPreviousTotal, result.previousMonthTotal, "${row.name} previous total")
            assertMoneyEquals(row.expectedProjected, result.projectedTotal, "${row.name} projected")
            assertEquals("${row.name} pace", row.expectedPace, result.pacePercentage, 0.01f)
            assertEquals("${row.name} status", row.expectedStatus, result.paceStatus)
        }
    }

    // ========== Projection table (no previous-month baseline) ==========

    private data class ProjectionRow(
        val name: String,
        val now: LocalDateTime,
        val monthStart: LocalDate,
        val amount: Double,
        val expectedDaysElapsed: Int,
        val expectedDaysInMonth: Int,
        val expectedProjected: Double
    )

    @Test
    fun `projection table uses linear after five days and five day floor before`() {
        val apr2024 = LocalDate.of(2024, 4, 1)
        val apr2026 = LocalDate.of(2026, 4, 1)
        val rows = listOf(
            // day 10 >= 5: 500 * 30 / 10
            ProjectionRow("normal day 10", LocalDateTime.of(2024, 4, 10, 12, 0), apr2024, 500.0, 10, 30, 1500.0),
            // day 2 < 5, no baseline: stabilized floor 5 => 200 * 30 / 5
            ProjectionRow("early day 2", LocalDateTime.of(2024, 4, 2, 12, 0), apr2024, 200.0, 2, 30, 1200.0),
            // day 4 < 5, no baseline: 400 * 30 / 5
            ProjectionRow("early day 4", LocalDateTime.of(2024, 4, 4, 12, 0), apr2024, 400.0, 4, 30, 2400.0),
            // leap February: 700 * 29 / 14
            ProjectionRow(
                "february leap year 29 days", LocalDateTime.of(2024, 2, 14, 12, 0), LocalDate.of(2024, 2, 1),
                700.0, 14, 29, 1450.0
            ),
            // same as early day 2 but with now at local midnight (2026)
            ProjectionRow("early day 2 at midnight", LocalDateTime.of(2026, 4, 2, 0, 0), apr2026, 200.0, 2, 30, 1200.0),
            // same as early day 4 but with now at local midnight (2026)
            ProjectionRow("early day 4 at midnight", LocalDateTime.of(2026, 4, 4, 0, 0), apr2026, 400.0, 4, 30, 2400.0)
        )
        assertEquals("projection table size", 6, rows.size)

        for (row in rows) {
            val result = calculate(
                now = row.now,
                monthStart = row.monthStart,
                expenses = listOf(snapshot(1, row.amount, row.monthStart))
            )

            assertEquals("${row.name} daysElapsed", row.expectedDaysElapsed, result.daysElapsed)
            assertEquals("${row.name} daysInMonth", row.expectedDaysInMonth, result.daysInMonth)
            assertMoneyEquals(row.expectedProjected, result.projectedTotal, "${row.name} projected")
        }
    }

    // ========== Exclusion table ==========

    private data class ExclusionRow(
        val name: String,
        val now: LocalDateTime,
        val monthStart: LocalDate,
        val expenses: List<ExpenseSnapshot>,
        val expectedSpent: Double
    )

    @Test
    fun `exclusion table counts only own purchases`() {
        val apr2024 = LocalDate.of(2024, 4, 1)
        val apr2026 = LocalDate.of(2026, 4, 1)
        val rows = listOf(
            ExclusionRow(
                "deposit is not a purchase", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024,
                listOf(
                    snapshot(1, 500.0, LocalDate.of(2024, 4, 1)),
                    snapshot(2, 1000.0, LocalDate.of(2024, 4, 2), type = DomainTransactionType.DEPOSIT),
                    snapshot(3, 400.0, LocalDate.of(2024, 4, 3))
                ),
                900.0
            ),
            ExclusionRow(
                "not-mine flag excludes even with full effective amount", LocalDateTime.of(2024, 4, 15, 12, 0), apr2024,
                listOf(
                    snapshot(1, 500.0, LocalDate.of(2024, 4, 1)),
                    snapshot(2, 1000.0, LocalDate.of(2024, 4, 2), isNotMine = true),
                    snapshot(3, 400.0, LocalDate.of(2024, 4, 3))
                ),
                900.0
            ),
            ExclusionRow(
                "deposit and zero-effective not-mine together", LocalDateTime.of(2026, 4, 10, 0, 0), apr2026,
                listOf(
                    snapshot(1, 100.0, LocalDate.of(2026, 4, 1)),
                    snapshot(2, 80.0, LocalDate.of(2026, 4, 2), isNotMine = true, effectiveAmount = 0.0),
                    snapshot(3, 250.0, LocalDate.of(2026, 4, 3), type = DomainTransactionType.DEPOSIT),
                    snapshot(4, 60.0, LocalDate.of(2026, 4, 4))
                ),
                160.0
            )
        )
        assertEquals("exclusion table size", 3, rows.size)

        for (row in rows) {
            val result = calculate(row.now, row.monthStart, row.expenses)
            assertMoneyEquals(row.expectedSpent, result.currentMonthSpent, "${row.name} spent")
        }
    }

    // ========== Zero spend (available zero, not unavailable) ==========

    @Test
    fun `no current spending is available zero not unavailable`() {
        val result = calculate(
            now = LocalDateTime.of(2024, 4, 15, 12, 0),
            monthStart = LocalDate.of(2024, 4, 1),
            expenses = emptyList()
        )

        // assertMoneyExact fails on null and requires exactly 0.0, so an unavailable or
        // merely near-zero result cannot masquerade as zero.
        assertMoneyExact(0.0, result.currentMonthSpent, "current month spent")
        assertEquals(15, result.daysElapsed)
        assertMoneyExact(0.0, result.projectedTotal, "projected total")
    }
}
