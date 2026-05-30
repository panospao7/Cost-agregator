package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

/**
 * P6-CURRENT-014: When the occurrence DAO is available, the block-party calendar must use
 * only PLANNED occurrences as future recurring obligations. PAID occurrences are
 * already-fulfilled actuals and must NOT appear as future bill-day targets (they would
 * double-count against the matching actual expense).
 */
class SynthesisEngineBlockPartyPaidExclusionTest {

    private val timeProvider = mockk<TimeProvider>()
    private val occurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)

    private fun dayOfMonth(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun occurrence(ruleId: Long, dueDate: Long, status: String) = RecurringOccurrence(
        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
        sourceId = ruleId,
        occurrenceKey = "$ruleId|$dueDate|$status",
        dueDate = dueDate,
        status = status,
        expectedAmount = 100.0,
        expectedCurrency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY.name,
        merchant = "LANDLORD"
    )

    private fun budgetSnapshot(limit: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = limit,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = limit,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 0,
        periodEnd = 0
    )

    private fun pace() = SpendingPace(
        currentMonthSpent = 0.0,
        daysElapsed = 1,
        daysInMonth = 30,
        projectedTotal = 0.0,
        previousMonthTotal = 0.0,
        averageMonthlyTotal = 0.0,
        pacePercentage = 0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR"
    )

    @Test
    fun `block party excludes PAID occurrences from recurring impact`() = runBlocking {
        val now = dayOfMonth(2024, Calendar.APRIL, 15)
        every { timeProvider.now() } returns now

        val plannedDue = dayOfMonth(2024, Calendar.APRIL, 10)
        val paidDue = dayOfMonth(2024, Calendar.APRIL, 20)
        // Rule 1 PLANNED (future obligation) on day 10; Rule 2 PAID on day 20 (already fulfilled).
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = plannedDue, status = "PLANNED"),
            occurrence(ruleId = 2L, dueDate = paidDue, status = "PAID")
        )

        val engine = SynthesisEngine(timeProvider, occurrenceDao)

        // Two manual patterns (id != null) so the occurrence path is taken for both rules.
        val patterns = listOf(
            com.yourname.expensetracker.domain.model.RecurringPattern(
                merchantName = "LANDLORD", averageAmount = 100.0, currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY, periodVarianceDays = 0,
                amountVariancePercent = 0.0, nextExpectedDate = plannedDue, confidence = 1.0f,
                previousDates = emptyList(), id = 1L
            ),
            com.yourname.expensetracker.domain.model.RecurringPattern(
                merchantName = "LANDLORD", averageAmount = 100.0, currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY, periodVarianceDays = 0,
                amountVariancePercent = 0.0, nextExpectedDate = paidDue, confidence = 1.0f,
                previousDates = emptyList(), id = 2L
            )
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = patterns,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budgetSnapshot(2000.0)),
            spendingPace = pace()
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(30) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.find { it.dayOfMonth == 10 }
        val day20 = blockParty.find { it.dayOfMonth == 20 }
        assertNotNull(day10)
        assertNotNull(day20)
        // PLANNED occurrence shows as a future obligation.
        assertEquals(100.0, day10!!.recurringImpact, 0.001)
        // PAID occurrence must NOT appear as a future recurring obligation.
        assertEquals(0.0, day20!!.recurringImpact, 0.001)
    }

    /**
     * DBG-04: A rule whose only in-range occurrence is SKIPPED must NOT reappear as a
     * bill day. Before the fix, the occurrence query filtered to PLANNED|PAID only, so
     * a SKIPPED-only rule returned no rows → it fell into missingRuleIds → the legacy
     * isRecurringExpected date-matcher RE-ADDED it on its monthly anchor day. After the
     * fix, ANY materialised occurrence row (regardless of status) marks the rule as
     * "has occurrences", so it never falls back to legacy matching — and since SKIPPED
     * is not PLANNED, it contributes nothing to the bill-day map.
     */
    @Test
    fun `block party excludes SKIPPED occurrences and does not resurface via legacy matching`() = runBlocking {
        val now = dayOfMonth(2024, Calendar.APRIL, 15)
        every { timeProvider.now() } returns now

        val skippedDue = dayOfMonth(2024, Calendar.APRIL, 10)
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = skippedDue, status = "SKIPPED")
        )

        val engine = SynthesisEngine(timeProvider, occurrenceDao)

        // Manual pattern (id != null) whose MONTHLY anchor is day 10 — exactly the day the
        // legacy matcher would otherwise re-add it.
        val patterns = listOf(
            com.yourname.expensetracker.domain.model.RecurringPattern(
                merchantName = "LANDLORD", averageAmount = 100.0, currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY, periodVarianceDays = 0,
                amountVariancePercent = 0.0, nextExpectedDate = skippedDue, confidence = 1.0f,
                previousDates = emptyList(), id = 1L
            )
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = patterns,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budgetSnapshot(2000.0)),
            spendingPace = pace()
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(30) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.find { it.dayOfMonth == 10 }
        assertNotNull(day10)
        // The user explicitly SKIPPED this occurrence — it must NOT reappear as a bill day.
        assertEquals(0.0, day10!!.recurringImpact, 0.001)
    }

    /**
     * DBG-04 (CANCELLED variant): same guarantee as the SKIPPED case — a rule whose only
     * in-range occurrence is CANCELLED must not be resurrected by the legacy fallback.
     */
    @Test
    fun `block party excludes CANCELLED occurrences and does not resurface via legacy matching`() = runBlocking {
        val now = dayOfMonth(2024, Calendar.APRIL, 15)
        every { timeProvider.now() } returns now

        val cancelledDue = dayOfMonth(2024, Calendar.APRIL, 10)
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = cancelledDue, status = "CANCELLED")
        )

        val engine = SynthesisEngine(timeProvider, occurrenceDao)

        val patterns = listOf(
            com.yourname.expensetracker.domain.model.RecurringPattern(
                merchantName = "LANDLORD", averageAmount = 100.0, currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY, periodVarianceDays = 0,
                amountVariancePercent = 0.0, nextExpectedDate = cancelledDue, confidence = 1.0f,
                previousDates = emptyList(), id = 1L
            )
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = patterns,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budgetSnapshot(2000.0)),
            spendingPace = pace()
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(30) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.find { it.dayOfMonth == 10 }
        assertNotNull(day10)
        // The user explicitly CANCELLED this occurrence — it must NOT reappear as a bill day.
        assertEquals(0.0, day10!!.recurringImpact, 0.001)
    }
}
