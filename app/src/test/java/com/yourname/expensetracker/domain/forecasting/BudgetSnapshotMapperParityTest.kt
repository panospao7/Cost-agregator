package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardContractsAdapter
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fix-1.0.4 (FL-06): the forecast input assembler and the dashboard adapter map a repository
 * [BudgetStatus] to the same [com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot],
 * effective fields included. Before the fix the assembler dropped the effective spend, so the weather
 * and installment-weather paths saw a budget as unavailable.
 */
class BudgetSnapshotMapperParityTest {

    private val start = 1_780_000_000_000L
    private val end = start + 30 * 86_400_000L

    private fun budget(categoryId: Long?, amount: Double) = Budget(
        id = if (categoryId == null) 1L else 2L,
        categoryId = categoryId,
        amount = amount,
        period = BudgetPeriod.MONTHLY,
        startDate = start,
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.9f,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED"
    )

    /** Overall budget with a shared-expense breakdown: gross 1200, effective 950 on limit 1000. */
    private val withBreakdown = BudgetStatus(
        budget = budget(null, 1_000.0),
        category = null,
        spentAmount = 1_200.0,
        remainingAmount = 0.0,
        percentUsed = 1.2f,
        healthStatus = BudgetHealthStatus.EXCEEDED,
        periodStart = start,
        periodEnd = end,
        effectiveLimit = 1_000.0,
        adjustedSpendBreakdown = AdjustedSpendBreakdown(
            personalSpend = 1_200.0,
            sharedSpend = 0.0,
            reimbursedAmount = 250.0,
            netSharedLiability = 0.0,
            effectiveSpend = 950.0,
            pendingReimbursements = 0.0
        )
    )

    /** Category budget with no breakdown and a partial-conversion warning. */
    private val withoutBreakdown = BudgetStatus(
        budget = budget(7L, 300.0),
        category = Category(id = 7L, name = "Food", icon = "food", color = "#000000"),
        spentAmount = 120.0,
        remainingAmount = 180.0,
        percentUsed = 0.4f,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = start,
        periodEnd = end,
        effectiveLimit = 300.0,
        adjustedSpendBreakdown = null,
        isPartial = true,
        conversionWarning = "TEST_PARTIAL"
    )

    private fun assembler() = ForecastInputAssembler(
        mockk<TimeProvider>(relaxed = true),
        analyticsCurrencyNormalizer = mockk(relaxed = true),
        currencySettingsRepository = mockk(relaxed = true),
        currencyConverter = mockk(relaxed = true),
        recurringLifecycleCoordinator = mockk(relaxed = true),
        recurringOccurrenceDao = mockk(relaxed = true),
        databaseReadBarrier = mockk(relaxed = true)
    )

    private fun adapter(statuses: List<BudgetStatus>): DashboardContractsAdapter {
        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(statuses)
        return DashboardContractsAdapter(
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            categoryRepository = mockk<CategoryRepository>(relaxed = true),
            budgetRepository = budgetRepository,
            reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true),
            financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true),
            savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true),
            analyticsRepository = mockk<AnalyticsRepository>(relaxed = true),
            recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true),
            plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true),
            timeBoundaryTicker = TimeBoundaryTicker(FakeTimeProvider(start))
        )
    }

    @Test
    fun `assembler mapping equals the dashboard adapter mapping for a status with a breakdown`() = runTest {
        val viaAssembler = assembler().mapBudgetSnapshots(listOf(withBreakdown))
        val viaAdapter = adapter(listOf(withBreakdown)).observeBudgetStatuses().first()

        assertEquals(viaAdapter, viaAssembler)
        val snapshot = viaAssembler.single()
        assertEquals("effective spend must survive the assembler", 950.0, snapshot.effectiveSpend!!, 0.0)
        assertEquals(BudgetHealthStatus.CRITICAL, snapshot.effectiveHealthStatus)
        assertEquals("gross health stays gross", BudgetHealthStatus.EXCEEDED, snapshot.healthStatus)
    }

    @Test
    fun `assembler mapping equals the dashboard adapter mapping for a status without a breakdown`() = runTest {
        val viaAssembler = assembler().mapBudgetSnapshots(listOf(withoutBreakdown))
        val viaAdapter = adapter(listOf(withoutBreakdown)).observeBudgetStatuses().first()

        assertEquals(viaAdapter, viaAssembler)
        val snapshot = viaAssembler.single()
        assertNull("no breakdown means no effective spend, never the gross 120", snapshot.effectiveSpend)
        assertNull(snapshot.effectiveHealthStatus)
        assertEquals(120.0, snapshot.spentAmount, 0.0)
        assertEquals(true, snapshot.isPartial)
        assertEquals("TEST_PARTIAL", snapshot.conversionWarning)
    }

    @Test
    fun `assembler mapping equals the dashboard adapter mapping for a mixed list in order`() = runTest {
        val statuses = listOf(withBreakdown, withoutBreakdown)

        assertEquals(
            adapter(statuses).observeBudgetStatuses().first(),
            assembler().mapBudgetSnapshots(statuses)
        )
    }
}
