package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-05-5 MINOR-4: a budget read failure must reach the dashboard data as an explicit
 * `budgetReadFailed` flag (fail-closed for Money Radar), never as an indistinguishable
 * "no budget" empty list; a successful read must leave the flag false; and cancellation of the
 * collector must not be converted into a read failure.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardDataProviderBudgetReadFailedTest {

    private val expenseRepository = mockk<DashboardExpenseRepository>()
    private val categoryRepository = mockk<DashboardCategoryRepository>()
    private val budgetRepository = mockk<DashboardBudgetRepository>()
    private val reviewQueueRepository = mockk<DashboardReviewQueueRepository>()
    private val financialWeatherRepository = mockk<DashboardFinancialWeatherRepository>()
    private val savingsGoalRepository = mockk<DashboardSavingsGoalRepository>()

    private val provider = DashboardDataProvider(
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        budgetRepository = budgetRepository,
        reviewQueueRepository = reviewQueueRepository,
        financialWeatherRepository = financialWeatherRepository,
        savingsGoalRepository = savingsGoalRepository,
        insightsEngine = mockk<InsightsEngine>(relaxed = true),
        synthesisEngine = mockk<SynthesisEngine>(relaxed = true),
        timeProvider = mockk<TimeProvider>(relaxed = true),
        analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
    )

    private fun stubNonBudgetSources(
        expenses: List<DashboardExpense> = emptyList(),
        categories: List<DashboardCategory> = emptyList()
    ) {
        every { expenseRepository.observeDashboardExpenses() } returns flowOf(expenses)
        every { categoryRepository.observeDashboardCategories() } returns flowOf(categories)
        every { reviewQueueRepository.observePendingReviewCount() } returns flowOf(0)
        every { financialWeatherRepository.observeFinancialWeather() } returns flowOf(
            FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            )
        )
        every { financialWeatherRepository.observeRecurringPatterns() } returns flowOf(emptyList())
        every { financialWeatherRepository.observePlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
    }

    private fun budgetStatus() = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = 1000.0,
        categoryName = null,
        spentAmount = 250.0,
        remainingAmount = 750.0,
        percentUsed = 25.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 1_000L,
        periodEnd = 2_000_000_000L,
        currency = "EUR",
        currencyAssumption = "EXPLICIT"
    )

    private fun failingBudgetFlow(): Flow<List<BudgetStatusSnapshot>> =
        flow { throw IllegalStateException("TEST_BUDGET_READ_FAILURE") }

    @Test
    fun `budget read failure sets budgetReadFailed with empty budgets in base data`() = runTest {
        stubNonBudgetSources()
        every { budgetRepository.observeBudgetStatuses() } returns failingBudgetFlow()

        val base = provider.getBaseDataFlow().first()

        assertTrue(base.budgetReadFailed)
        assertTrue(base.budgetStatuses.isEmpty())
    }

    @Test
    fun `budget read failure sets budgetReadFailed in the combined dashboard data`() = runTest {
        stubNonBudgetSources()
        every { budgetRepository.observeBudgetStatuses() } returns failingBudgetFlow()

        val data = provider.getAllDataFlow().first()

        assertTrue(data.budgetReadFailed)
        assertTrue(data.budgetStatuses.isEmpty())
    }

    @Test
    fun `successful budget read keeps budgetReadFailed false and carries the statuses`() = runTest {
        stubNonBudgetSources()
        val status = budgetStatus()
        every { budgetRepository.observeBudgetStatuses() } returns flowOf(listOf(status))

        val base = provider.getBaseDataFlow().first()
        val data = provider.getAllDataFlow().first()

        assertFalse(base.budgetReadFailed)
        assertEquals(listOf(status), base.budgetStatuses)
        assertFalse(data.budgetReadFailed)
        assertEquals(listOf(status), data.budgetStatuses)
    }

    @Test
    fun `successful empty budget read is no budget and not a read failure`() = runTest {
        stubNonBudgetSources()
        every { budgetRepository.observeBudgetStatuses() } returns flowOf(emptyList())

        val base = provider.getBaseDataFlow().first()

        assertFalse(base.budgetReadFailed)
        assertTrue(base.budgetStatuses.isEmpty())
    }

    @Test
    fun `budget read failure does not flag other sources as failed or drop their data`() = runTest {
        stubNonBudgetSources(categories = listOf(DashboardCategory(7L, "Food", "F", "#112233")))
        every { budgetRepository.observeBudgetStatuses() } returns failingBudgetFlow()

        val base = provider.getBaseDataFlow().first()

        assertTrue(base.budgetReadFailed)
        assertEquals(listOf(7L), base.categories.map { it.id })
    }

    @Test
    fun `cancelling the collector during a budget read is not turned into budgetReadFailed`() = runTest {
        stubNonBudgetSources()
        val readStarted = CompletableDeferred<Unit>()
        every { budgetRepository.observeBudgetStatuses() } returns flow {
            readStarted.complete(Unit)
            awaitCancellation()
        }
        val emitted = mutableListOf<BaseData>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.getBaseDataFlow().toList(emitted)
        }
        readStarted.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("cancellation must not emit a failure-flagged BaseData", emitted.isEmpty())
    }
}
