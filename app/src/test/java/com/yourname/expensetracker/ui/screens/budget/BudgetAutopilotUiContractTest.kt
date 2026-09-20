package com.yourname.expensetracker.ui.screens.budget

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.HomeCurrencyUnavailableException
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetAutopilotRecommendations
import com.yourname.expensetracker.domain.budget.BudgetRecommendationQuality
import com.yourname.expensetracker.domain.budget.BudgetTrend
import com.yourname.expensetracker.domain.budget.CategoryBudgetRecommendation
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * RP-08 slice C3 (P6-004 low-history UI contract): ViewModel-level tests for the
 * quality/isActionable display contract on the autopilot banner.
 *
 * Follows the [BudgetViewModelStressTest] harness style (mockk +
 * InstantTaskExecutorRule + StandardTestDispatcher) but is NOT ignored —
 * these tests must run in CI. The stress harness is a ledgered hang suspect,
 * so these assertions live in their own active test class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetAutopilotUiContractTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var offsetEngine: SharedExpenseBudgetOffsetEngine
    private lateinit var autopilotEngine: BudgetAutopilotEngine
    private lateinit var timeProvider: TimeProvider
    private lateinit var viewModel: BudgetViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        budgetRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        offsetEngine = mockk(relaxed = true)
        autopilotEngine = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { budgetRepository.getSuggestions() } returns emptyList()
        every { timeProvider.now() } returns 1_700_000_000_000L

        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")

        viewModel = BudgetViewModel(
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            offsetEngine = offsetEngine,
            autopilotEngine = autopilotEngine,
            timeProvider = timeProvider,
            currencySettingsRepository = currencyRepo,
            database = mockk()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * `uiState` is `stateIn(SharingStarted.WhileSubscribed(5000))` — reading
     * `.value` without a subscriber never starts upstream collection. Launch an
     * eager background collector so the StateFlow is active for assertions,
     * mirroring what the Compose screen does while visible.
     */
    private fun startUiStateCollector() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    private fun recommendation(
        budgetId: Long = 1L,
        categoryId: Long? = 10L,
        name: String = "Groceries",
        quality: BudgetRecommendationQuality,
        isActionable: Boolean
    ) = CategoryBudgetRecommendation(
        budgetId = budgetId,
        categoryId = categoryId,
        categoryName = name,
        currentBudget = 100.0,
        recommendedBudget = if (quality == BudgetRecommendationQuality.LOW_HISTORY) 100.0 else 110.0,
        delta = if (quality == BudgetRecommendationQuality.LOW_HISTORY) 0.0 else 10.0,
        deltaPercentage = if (quality == BudgetRecommendationQuality.LOW_HISTORY) 0.0 else 10.0,
        reason = "test",
        confidence = 0.9,
        trend = BudgetTrend.STABLE,
        quality = quality,
        isActionable = isActionable
    )

    private fun recommendations(vararg recs: CategoryBudgetRecommendation) =
        BudgetAutopilotRecommendations(
            categoryRecommendations = recs.toList(),
            totalCurrentBudget = recs.sumOf { it.currentBudget },
            totalRecommendedBudget = recs.sumOf { it.recommendedBudget },
            overallDelta = 0.0,
            confidence = 0.9,
            generatedAt = 1_700_000_000_000L
        )

    @Test
    fun `LOW_HISTORY recommendation is exposed with isActionable false and apply attempt fails closed`() =
        runTest(testDispatcher) {
            startUiStateCollector()
            val rec = recommendation(
                quality = BudgetRecommendationQuality.LOW_HISTORY,
                isActionable = false
            )
            coEvery { autopilotEngine.generateRecommendations() } returns recommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            assertEquals(listOf(rec), viewModel.uiState.value.autopilotRecommendations)
            assertFalse(viewModel.uiState.value.autopilotRecommendations.first().isActionable)
            assertEquals(
                BudgetRecommendationQuality.LOW_HISTORY,
                viewModel.uiState.value.autopilotRecommendations.first().quality
            )

            // Apply attempt on a non-actionable recommendation → typed sanitized
            // error, and NO budget write (fail closed before repository access).
            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            assertEquals(
                BudgetViewModel.ERROR_AUTOPILOT_NOT_ACTIONABLE,
                viewModel.uiState.value.autopilotError
            )
            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
            coVerify(exactly = 0) { budgetRepository.updateBudgetOrThrow(any()) }
        }

    @Test
    fun `PARTIAL_DATA recommendation remains applicable and labeled`() =
        runTest(testDispatcher) {
            startUiStateCollector()
            val rec = recommendation(
                quality = BudgetRecommendationQuality.PARTIAL_DATA,
                isActionable = true
            )
            val activeBudget = Budget(
                id = rec.budgetId,
                categoryId = rec.categoryId,
                amount = 100.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_700_000_000_000L
            )
            coEvery { autopilotEngine.generateRecommendations() } returns recommendations(rec)
            coEvery { budgetRepository.getActiveBudgets() } returns listOf(activeBudget)
            coEvery { budgetRepository.updateBudget(any()) } returns Result.Success(Unit)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            val exposed = viewModel.uiState.value.autopilotRecommendations.first()
            assertEquals(BudgetRecommendationQuality.PARTIAL_DATA, exposed.quality)
            assertTrue(exposed.isActionable)

            // PARTIAL_DATA stays applicable — the budget write succeeds.
            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.autopilotError)
            coVerify(exactly = 1) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `COMPLETE actionable recommendation is unaffected by quality gating`() =
        runTest(testDispatcher) {
            startUiStateCollector()
            val rec = recommendation(
                quality = BudgetRecommendationQuality.COMPLETE,
                isActionable = true
            )
            val activeBudget = Budget(
                id = rec.budgetId,
                categoryId = rec.categoryId,
                amount = 100.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_700_000_000_000L
            )
            coEvery { autopilotEngine.generateRecommendations() } returns recommendations(rec)
            coEvery { budgetRepository.getActiveBudgets() } returns listOf(activeBudget)
            coEvery { budgetRepository.updateBudget(any()) } returns Result.Success(Unit)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            val exposed = viewModel.uiState.value.autopilotRecommendations.first()
            assertEquals(BudgetRecommendationQuality.COMPLETE, exposed.quality)
            assertTrue(exposed.isActionable)

            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.autopilotError)
            coVerify(exactly = 1) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `apply all with only LOW_HISTORY recommendations fails closed with typed error`() =
        runTest(testDispatcher) {
            startUiStateCollector()
            val rec = recommendation(
                quality = BudgetRecommendationQuality.LOW_HISTORY,
                isActionable = false
            )
            coEvery { autopilotEngine.generateRecommendations() } returns recommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            viewModel.applyAllAutopilotRecommendations()
            advanceUntilIdle()

            assertEquals(
                BudgetViewModel.ERROR_AUTOPILOT_NOT_ACTIONABLE,
                viewModel.uiState.value.autopilotError
            )
            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
            coVerify(exactly = 0) { budgetRepository.updateBudgetOrThrow(any()) }
        }

    @Test
    fun `generate failure from repository history surfaces sanitized constant only`() =
        runTest(testDispatcher) {
            startUiStateCollector()
            coEvery { autopilotEngine.generateRecommendations() } throws
                HomeCurrencyUnavailableException("raw home-currency detail must not leak")

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            assertEquals(
                BudgetViewModel.ERROR_BUDGET_HISTORY_UNAVAILABLE,
                viewModel.uiState.value.autopilotError
            )
            assertTrue(viewModel.uiState.value.autopilotRecommendations.isEmpty())
            // The raw exception message must never reach the UI state.
            assertFalse(viewModel.uiState.value.autopilotError!!.contains("raw home-currency detail"))
        }
}
