package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DashboardContractsAdapterTest {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
    private val financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true)
    private val savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true)
    private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val timeBoundaryTicker = mockk<TimeBoundaryTicker>(relaxed = true)

    private lateinit var adapter: DashboardContractsAdapter

    @Before
    fun setup() {
        adapter = DashboardContractsAdapter(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            reviewQueueRepository = reviewQueueRepository,
            financialWeatherRepository = financialWeatherRepository,
            savingsGoalRepository = savingsGoalRepository,
            analyticsRepository = analyticsRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            timeBoundaryTicker = timeBoundaryTicker
        )
    }

    @Test
    fun `observeRecurringPatterns uses confirmed recurring feed for dashboard forecast consumers`() = runTest {
        val confirmedPattern = recurringPattern("Confirmed Rent", 900.0)
        val mergedSuggestion = recurringPattern("Suggested Gym", 45.0)

        every { financialWeatherRepository.getConfirmedRecurringPatterns() } returns flowOf(listOf(confirmedPattern))
        every { financialWeatherRepository.getAllRecurringPatterns() } returns flowOf(listOf(mergedSuggestion))

        val result = adapter.observeRecurringPatterns().first()

        assertEquals(listOf("Confirmed Rent"), result.map { it.merchantName })
        verify(exactly = 1) { financialWeatherRepository.getConfirmedRecurringPatterns() }
        verify(exactly = 0) { financialWeatherRepository.getAllRecurringPatterns() }
    }

    private fun recurringPattern(merchant: String, amount: Double): RecurringPattern {
        return RecurringPattern(
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = 1_710_000_000_000L,
            confidence = 1.0f,
            previousDates = emptyList()
        )
    }
}
