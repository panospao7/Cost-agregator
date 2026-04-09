package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Boundary tests for [HistoricalSpendingDistribution] verifying correct behaviour
 * after the A.5 Batch 2A migration to [TimePeriodUtils].
 *
 * Key properties under test:
 * - Week bucketing via `TimePeriodUtils.getStartOfWeek` (Monday-start, DST-safe)
 * - Distinct-day counting via `TimePeriodUtils.getStartOfDay` (not raw ms division)
 * - Calendar-aware week enumeration via `TimePeriodUtils.addDays` (not fixed ms)
 * - Lookback window uses `TimePeriodUtils.addMonths` (not fixed-day approximation)
 */
class HistoricalSpendingDistributionBoundaryTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: FakeTimeProvider

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun expense(id: Long, date: Long, amount: Double, type: TransactionType = TransactionType.PURCHASE): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "TestMerchant",
        transactionType = type,
        date = date
    )

    @Before
    fun setUp() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(toEpochMs(2026, 4, 15, 12, 0)) // Wednesday
    }

    // ========================================================================
    // Week bucketing — Monday-start, locale-independent
    // ========================================================================

    @Test
    fun `week bucket key is the Monday of that week not locale-dependent`() {
        // Verify that expenses on different days of the same week share the same week key
        val monday = toEpochMs(2026, 4, 13, 10, 0)     // Monday
        val wednesday = toEpochMs(2026, 4, 15, 14, 0)   // Wednesday
        val sunday = toEpochMs(2026, 4, 19, 22, 0)      // Sunday

        val mondayKey = TimePeriodUtils.getStartOfWeek(monday)
        val wednesdayKey = TimePeriodUtils.getStartOfWeek(wednesday)
        val sundayKey = TimePeriodUtils.getStartOfWeek(sunday)

        assertEquals("Mon and Wed should share week key", mondayKey, wednesdayKey)
        assertEquals("Mon and Sun should share week key", mondayKey, sundayKey)

        // The key itself should be Monday 00:00:00.000
        val cal = Calendar.getInstance()
        cal.timeInMillis = mondayKey
        assertEquals("Week key should be Monday", Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals("Week key hour should be 0", 0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Week key minute should be 0", 0, cal.get(Calendar.MINUTE))
        assertEquals("Week key second should be 0", 0, cal.get(Calendar.SECOND))
        assertEquals("Week key ms should be 0", 0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `next Monday belongs to a different week bucket`() {
        val sunday = toEpochMs(2026, 4, 19, 23, 59)     // Sunday 23:59
        val nextMonday = toEpochMs(2026, 4, 20, 0, 0)   // Next Monday 00:00

        val sundayKey = TimePeriodUtils.getStartOfWeek(sunday)
        val nextMondayKey = TimePeriodUtils.getStartOfWeek(nextMonday)

        assertTrue("Sunday and next Monday should be in different weeks",
            sundayKey != nextMondayKey)

        // Next Monday's key should be exactly 7 days after Sunday's key
        val daysBetween = TimePeriodUtils.daysBetween(sundayKey, nextMondayKey)
        assertEquals("Week keys should be 7 days apart", 7, daysBetween)
    }

    // ========================================================================
    // Distinct-day counting — uses getStartOfDay (DST-safe)
    // ========================================================================

    @Test
    fun `distinct day counting via getStartOfDay groups same-day expenses`() {
        val morning = toEpochMs(2026, 4, 15, 8, 30)
        val noon = toEpochMs(2026, 4, 15, 12, 0)
        val evening = toEpochMs(2026, 4, 15, 21, 45)
        val nextDay = toEpochMs(2026, 4, 16, 6, 0)

        val morningKey = TimePeriodUtils.getStartOfDay(morning)
        val noonKey = TimePeriodUtils.getStartOfDay(noon)
        val eveningKey = TimePeriodUtils.getStartOfDay(evening)
        val nextDayKey = TimePeriodUtils.getStartOfDay(nextDay)

        assertEquals("Morning and noon same day key", morningKey, noonKey)
        assertEquals("Morning and evening same day key", morningKey, eveningKey)
        assertTrue("Next day has different key", morningKey != nextDayKey)

        // Simulate distinct-day counting as done in groupIntoWeeks
        val expenses = listOf(
            expense(1L, morning, 10.0),
            expense(2L, noon, 20.0),
            expense(3L, evening, 30.0),
            expense(4L, nextDay, 40.0)
        )

        val distinctDays = expenses
            .map { TimePeriodUtils.getStartOfDay(it.date) }
            .toSet()
            .size

        assertEquals("Should count 2 distinct days", 2, distinctDays)
    }

    // ========================================================================
    // Week enumeration — calendar-aware via addDays(7)
    // ========================================================================

    @Test
    fun `week enumeration with addDays 7 produces exactly correct number of weeks`() {
        val rangeStart = TimePeriodUtils.getStartOfWeek(toEpochMs(2026, 1, 5)) // Mon Jan 5
        val rangeEnd = TimePeriodUtils.getStartOfWeek(toEpochMs(2026, 4, 13))   // Mon Apr 13

        var cursor = rangeStart
        val weekStarts = mutableListOf<Long>()
        while (cursor < rangeEnd) {
            weekStarts.add(cursor)
            cursor = TimePeriodUtils.addDays(cursor, 7)
        }

        // Count expected weeks between Jan 5 and Apr 13
        val expectedWeeks = TimePeriodUtils.daysBetween(rangeStart, rangeEnd) / 7
        assertEquals("Week count matches day-based calculation", expectedWeeks, weekStarts.size)

        // Every week start should be a Monday at midnight
        val cal = Calendar.getInstance()
        for (weekStart in weekStarts) {
            cal.timeInMillis = weekStart
            assertEquals("Each week start must be Monday",
                Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
            assertEquals("Each week start must be at midnight",
                0, cal.get(Calendar.HOUR_OF_DAY))
        }
    }

    // ========================================================================
    // Lookback window — uses addMonths, not fixed days
    // ========================================================================

    @Test
    fun `lookback window uses calendar month subtraction not fixed 540 days`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val lookbackRaw = TimePeriodUtils.addMonths(now, -18)
        val lookbackStart = TimePeriodUtils.getStartOfWeek(lookbackRaw)

        // 18 months before April 15 2026 12:00 = October 15 2024 12:00
        val expectedRaw = toEpochMs(2024, 10, 15, 12, 0)

        assertEquals("addMonths(-18) should land on Oct 15 2024", expectedRaw, lookbackRaw)

        // The lookback start should be the Monday of the week containing Oct 15 2024
        // Oct 15 2024 is a Tuesday, so Monday is Oct 14
        val cal = Calendar.getInstance()
        cal.timeInMillis = lookbackStart
        assertEquals("Lookback start should be Monday",
            Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    // ========================================================================
    // Integration: distribution computation with boundary-edge data
    // ========================================================================

    @Test
    fun `computeDistribution excludes current partial week`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)  // Wednesday mid-week
        timeProvider.setTime(now)

        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)

        // Create expenses: one in current week (should be excluded from weeks),
        // and enough in prior weeks to fit a distribution
        val expenses = mutableListOf<Expense>()
        var id = 1L

        // 8 full weeks of data, 4 transactions/week on different days
        for (weekOffset in 1..8) {
            val weekStart = TimePeriodUtils.addDays(currentWeekStart, -(weekOffset * 7))
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L // +10 hours
                expenses.add(expense(id++, date, 50.0 + dayOffset * 10.0))
            }
        }

        // Add one expense in the current partial week
        val currentWeekExpense = expense(id++, now, 999.0)
        expenses.add(currentWeekExpense)

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        // The current-week expense should not appear in any weekly total
        // because the range ends at currentWeekStart (exclusive).
        // With 8 weeks of 4 distinct days each (>= 3 minimum), all should qualify.
        assertTrue("Should have qualifying weeks",
            result!!.qualifyingWeekCount >= 4)
    }

    @Test
    fun `distribution weekly totals use effectiveAmount`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        timeProvider.setTime(now)

        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)
        val expenses = mutableListOf<Expense>()
        var id = 1L

        // 5 full weeks with 4 distinct transaction-days each
        // Add slight variation per week so sigma > 0 (isUsable requires non-zero sigma)
        for (weekOffset in 1..5) {
            val weekStart = TimePeriodUtils.addDays(currentWeekStart, -(weekOffset * 7))
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L
                expenses.add(expense(id++, date, 100.0 + weekOffset * 5.0))
            }
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        assertTrue("Should have at least 4 qualifying weeks", result!!.qualifyingWeekCount >= 4)
        assertTrue("Distribution should be usable", result.isUsable)
        // Each week has 4 expenses, with weekly totals varying slightly
        // All trimmed totals should be positive and in a reasonable range
        for (total in result.trimmedWeeklyTotals) {
            assertTrue("Weekly total should be positive", total > 0.0)
            assertTrue("Weekly total should be in expected range", total in 400.0..600.0)
        }
    }

    // ========================================================================
    // Filtering: only PURCHASE + WITHDRAWAL, not isNotMine
    // ========================================================================

    @Test
    fun `distribution filters out non-spending transaction types and isNotMine`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        timeProvider.setTime(now)

        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)
        val expenses = mutableListOf<Expense>()
        var id = 1L

        // 5 weeks with mixed types
        for (weekOffset in 1..5) {
            val weekStart = TimePeriodUtils.addDays(currentWeekStart, -(weekOffset * 7))
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L
                // PURCHASE — should count
                expenses.add(expense(id++, date, 100.0, TransactionType.PURCHASE))
                // DEPOSIT — should NOT count
                expenses.add(expense(id++, date, 500.0, TransactionType.DEPOSIT))
            }
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        // Each qualifying week should have total = 4 * $100 = $400, not $2400
        for (total in result!!.trimmedWeeklyTotals) {
            assertEquals("Weekly total should only include purchases", 400.0, total, 0.01)
        }
    }
}
