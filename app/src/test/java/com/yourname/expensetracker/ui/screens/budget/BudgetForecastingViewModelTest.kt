package com.yourname.expensetracker.ui.screens.budget

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.budget.BudgetForecastResult
import com.yourname.expensetracker.domain.budget.BudgetRecommendation
import com.yourname.expensetracker.domain.budget.BudgetRecommendationEngine
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetForecastingViewModelTest : ViewModelTestUtils() {

    private val forecastingEngine = mockk<BudgetForecastingEngine>(relaxed = true)
    private val recommendationEngine = mockk<BudgetRecommendationEngine>(relaxed = true)

    private lateinit var viewModel: BudgetForecastingViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { forecastingEngine.generateForecastResult(any(), any()) } returns BudgetForecastResult.Available(createForecast())
        every { recommendationEngine.generateRecommendations(any(), any(), any()) } returns emptyList()

        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        viewModel = BudgetForecastingViewModel(
            forecastingEngine = forecastingEngine,
            recommendationEngine = recommendationEngine,
            currencySettingsRepository = currencyRepo,
        )
    }

    @Test
    fun `initial state shows forecast with risk level`() = runTest(testDispatcher) {
        val budget = createBudget(id = 10L, amount = 200.0)
        val forecast = createForecast(
            budgetId = budget.id,
            predictedSpending = 140.0,
            predictedRemaining = 60.0,
            riskLevel = ForecastRiskLevel.HIGH
        )
        coEvery { forecastingEngine.generateForecastResult(budget, 30) } returns BudgetForecastResult.Available(forecast)

        viewModel.uiState.test {
            val initial = awaitItem()
            assertNull(initial.forecast)

            viewModel.generateForecast(budget)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertEquals(ForecastRiskLevel.HIGH, loaded.forecast?.riskLevel)
            assertEquals(140.0, loaded.forecast!!.predictedSpending, 0.0)
            assertNull(loaded.error)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { forecastingEngine.generateForecastResult(budget, 30) }
        verify(exactly = 1) { recommendationEngine.generateRecommendations(any(), any(), any()) }
    }

    @Test
    fun `period change triggers forecast recalculation`() = runTest(testDispatcher) {
        val budget = createBudget(id = 20L, amount = 300.0)
        val forecast30Days = createForecast(
            budgetId = budget.id,
            predictedSpending = 100.0,
            predictedRemaining = 200.0,
            riskLevel = ForecastRiskLevel.LOW
        )
        val forecast60Days = createForecast(
            budgetId = budget.id,
            predictedSpending = 220.0,
            predictedRemaining = 80.0,
            riskLevel = ForecastRiskLevel.MEDIUM
        )

        coEvery { forecastingEngine.generateForecastResult(budget, 30) } returns BudgetForecastResult.Available(forecast30Days)
        coEvery { forecastingEngine.generateForecastResult(budget, 60) } returns BudgetForecastResult.Available(forecast60Days)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.generateForecast(budget, forecastPeriodDays = 30)
            advanceUntilIdle()

            val loading30 = awaitItem()
            assertTrue(loading30.isLoading)

            val loaded30 = awaitItem()
            assertEquals(100.0, loaded30.forecast!!.predictedSpending, 0.0)

            viewModel.generateForecast(budget, forecastPeriodDays = 60)
            advanceUntilIdle()

            val loading60 = awaitItem()
            assertTrue(loading60.isLoading)

            val loaded60 = awaitItem()
            assertFalse(loaded60.isLoading)
            assertEquals(220.0, loaded60.forecast!!.predictedSpending, 0.0)
            assertEquals(ForecastRiskLevel.MEDIUM, loaded60.forecast?.riskLevel)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { forecastingEngine.generateForecastResult(budget, 30) }
        coVerify(exactly = 1) { forecastingEngine.generateForecastResult(budget, 60) }
    }

    @Test
    fun `ui contract remains stable when engine returns active period forecast window`() = runTest(testDispatcher) {
        val budget = createBudget(id = 25L, amount = 300.0)
        val activeWindowForecast = createForecast(
            budgetId = budget.id,
            predictedSpending = 75.0,
            predictedRemaining = 180.0,
            riskLevel = ForecastRiskLevel.MEDIUM,
            overspendProbability = 0.4
        ).copy(
            targetPeriodStart = 1_712_505_600_000L,
            targetPeriodEnd = 1_713_888_000_000L
        )
        coEvery { forecastingEngine.generateForecastResult(budget, 30) } returns BudgetForecastResult.Available(activeWindowForecast)

        viewModel.uiState.test {
            awaitItem()

            viewModel.generateForecast(budget)
            advanceUntilIdle()

            awaitItem()
            val loaded = awaitItem()

            assertFalse(loaded.isLoading)
            assertEquals(activeWindowForecast.targetPeriodStart, loaded.forecast!!.targetPeriodStart)
            assertEquals(activeWindowForecast.targetPeriodEnd, loaded.forecast!!.targetPeriodEnd)
            assertEquals(activeWindowForecast.overspendProbability, loaded.forecast!!.overspendProbability, 0.0)
            assertEquals(120.0, loaded.budget!!.amount - loaded.forecast!!.predictedRemaining, 0.0)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { forecastingEngine.generateForecastResult(budget, 30) }
        verify(exactly = 1) {
            recommendationEngine.generateRecommendations(any(), any(), any())
        }
    }

    @Test
    fun `error in engine sets error state`() = runTest(testDispatcher) {
        val budget = createBudget(id = 30L, amount = 120.0)
        coEvery { forecastingEngine.generateForecastResult(budget, 30) } throws IllegalStateException("engine failure")

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.generateForecast(budget)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val error = awaitItem()
            assertFalse(error.isLoading)
            assertTrue(error.error?.contains("Failed to generate forecast: engine failure") == true)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshForecast retries same budget and period after initial failure`() = runTest(testDispatcher) {
        val budget = createBudget(id = 31L, amount = 120.0)
        val recoveredForecast = createForecast(budgetId = budget.id, predictedSpending = 95.0)
        coEvery { forecastingEngine.generateForecastResult(budget, 45) } throws IllegalStateException("engine failure") andThen BudgetForecastResult.Available(recoveredForecast)

        viewModel.generateForecast(budget, forecastPeriodDays = 45)
        advanceUntilIdle()

        assertEquals(budget, viewModel.uiState.value.budget)
        assertTrue(viewModel.uiState.value.error?.contains("engine failure") == true)

        viewModel.refreshForecast()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(95.0, viewModel.uiState.value.forecast!!.predictedSpending, 0.0)
        coVerify(exactly = 2) { forecastingEngine.generateForecastResult(budget, 45) }
    }

    @Test
    fun `empty budget forecast shows no data state`() = runTest(testDispatcher) {
        val emptyBudget = createBudget(id = 40L, amount = 0.0)
        val noDataForecast = createForecast(
            budgetId = emptyBudget.id,
            predictedSpending = 0.0,
            predictedRemaining = 0.0,
            confidenceScore = 0.0,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.0
        )
        coEvery { forecastingEngine.generateForecastResult(emptyBudget, 30) } returns BudgetForecastResult.Available(noDataForecast)
        every { recommendationEngine.generateRecommendations(any(), any(), any()) } returns emptyList<BudgetRecommendation>()

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.generateForecast(emptyBudget)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val noData = awaitItem()
            assertFalse(noData.isLoading)
            assertEquals(0.0, noData.budget!!.amount, 0.0)
            assertEquals(0.0, noData.forecast!!.predictedSpending, 0.0)
            assertEquals(0.0, noData.forecast!!.predictedRemaining, 0.0)
            assertTrue(noData.recommendations.isEmpty())
            assertNull(noData.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createBudget(
        id: Long = 1L,
        amount: Double = 100.0
    ): Budget {
        return Budget(
            id = id,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = 1_700_000_000_000L
        )
    }

    private fun createForecast(
        budgetId: Long = 1L,
        predictedSpending: Double = 50.0,
        predictedRemaining: Double = 50.0,
        confidenceScore: Double = 0.8,
        riskLevel: ForecastRiskLevel = ForecastRiskLevel.MEDIUM,
        overspendProbability: Double = 0.3
    ): BudgetForecast {
        return BudgetForecast(
            id = 1L,
            budgetId = budgetId,
            forecastDate = 1_700_000_000_000L,
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_700_259_200_000L,
            predictedSpending = predictedSpending,
            predictedRemaining = predictedRemaining,
            confidenceScore = confidenceScore,
            riskLevel = riskLevel,
            overspendProbability = overspendProbability
        )
    }
}