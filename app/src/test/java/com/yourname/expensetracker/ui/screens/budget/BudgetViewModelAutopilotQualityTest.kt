package com.yourname.expensetracker.ui.screens.budget

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetAutopilotRecommendations
import com.yourname.expensetracker.domain.budget.BudgetRecommendationQuality
import com.yourname.expensetracker.domain.budget.CategoryBudgetRecommendation
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * RP-08 slice C3 — ViewModel-level contract tests for the autopilot
 * recommendation quality (P6-004 low-history/UI contract, plan lines 134-165):
 *
 * (a) LOW_HISTORY recommendation is NOT applicable — an apply attempt yields
 *     the typed [BudgetViewModel.ERROR_AUTOPILOT_NOT_ACTIONABLE] error and no
 *     budget write happens (fail-closed at the mutation boundary, landed in C2).
 * (b) PARTIAL_DATA recommendation remains applicable but carries its quality
 *     label through the UI state.
 * (c) COMPLETE actionable recommendation is unaffected — normal Apply behavior.
 *
 * Harness follows the BudgetViewModelStressTest style (mockk + InstantTaskExecutorRule
 * + StandardTestDispatcher); state observation reads the real UI state via
 * `uiState.first {}` (no turbine dependency in this class).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelAutopilotQualityTest : ViewModelTestUtils() {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val autopilotEngine = mockk<BudgetAutopilotEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

    private lateinit var viewModel: BudgetViewModel

    @Before
    override fun setup() {
        super.setup()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { budgetRepository.getSuggestions() } returns emptyList()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        viewModel = BudgetViewModel(
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            offsetEngine = offsetEngine,
            autopilotEngine = autopilotEngine,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository,
            database = mockk()
        )
    }

    @After
    override fun tearDown() {
        // The mixed apply-all test mocks Room's static transaction facade;
        // unmock it so the static stub never leaks into other test classes.
        unmockkStatic("androidx.room.RoomDatabaseKt")
        super.tearDown()
    }

    @Test
    fun `low history recommendation is not applicable - apply attempt yields typed error and no budget write`() =
        runTest(testDispatcher) {
            val rec = recommendation(quality = BudgetRecommendationQuality.LOW_HISTORY, isActionable = false)
            stubGeneratedRecommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            val withRecs = currentUiState()
            assertEquals(
                BudgetRecommendationQuality.LOW_HISTORY,
                withRecs.autopilotRecommendations.single().quality
            )
            assertFalse(withRecs.autopilotRecommendations.single().isActionable)

            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            val after = currentUiState()
            assertEquals(BudgetViewModel.ERROR_AUTOPILOT_NOT_ACTIONABLE, after.autopilotError)
            // Fail-closed: no budget write for LOW_HISTORY.
            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `apply all with only low history recommendations yields typed error and no budget write`() =
        runTest(testDispatcher) {
            val rec = recommendation(quality = BudgetRecommendationQuality.LOW_HISTORY, isActionable = false)
            stubGeneratedRecommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            viewModel.applyAllAutopilotRecommendations()
            advanceUntilIdle()

            val after = currentUiState()
            assertEquals(BudgetViewModel.ERROR_AUTOPILOT_NOT_ACTIONABLE, after.autopilotError)
            // Fail-closed before any repository access / transaction.
            coVerify(exactly = 0) { budgetRepository.getActiveBudgets() }
            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `partial data recommendation remains applicable and is labeled in ui state`() =
        runTest(testDispatcher) {
            stubSuccessfulApply()
            val rec = recommendation(quality = BudgetRecommendationQuality.PARTIAL_DATA, isActionable = true)
            stubGeneratedRecommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            val withRecs = currentUiState()
            assertEquals(
                BudgetRecommendationQuality.PARTIAL_DATA,
                withRecs.autopilotRecommendations.single().quality
            )
            // Labeled but still applicable.
            assertTrue(withRecs.autopilotRecommendations.single().isActionable)

            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            val after = currentUiState()
            assertNull(after.autopilotError)
            coVerify(exactly = 1) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `complete actionable recommendation applies normally`() =
        runTest(testDispatcher) {
            stubSuccessfulApply()
            val rec = recommendation(quality = BudgetRecommendationQuality.COMPLETE, isActionable = true)
            stubGeneratedRecommendations(rec)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            val withRecs = currentUiState()
            assertEquals(
                BudgetRecommendationQuality.COMPLETE,
                withRecs.autopilotRecommendations.single().quality
            )

            viewModel.applyAutopilotRecommendation(rec)
            advanceUntilIdle()

            val after = currentUiState()
            assertNull(after.autopilotError)
            coVerify(exactly = 1) { budgetRepository.updateBudget(any()) }
        }

    @Test
    fun `apply all with mixed actionable and LOW_HISTORY writes only actionable budgets`() =
        runTest(testDispatcher) {
            // RP-08 (P6-004): Apply All must never write non-actionable
            // (LOW_HISTORY) recommendations — fail closed, filter them out.
            mockkStatic("androidx.room.RoomDatabaseKt")
            val database = mockk<com.yourname.expensetracker.data.database.AppDatabase>(relaxed = true)
            coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
                secondArg<suspend () -> Any>().invoke()
            }
            // Rebuild the ViewModel with the transactional database handle.
            viewModel = BudgetViewModel(
                budgetRepository = budgetRepository,
                categoryRepository = categoryRepository,
                offsetEngine = offsetEngine,
                autopilotEngine = autopilotEngine,
                timeProvider = timeProvider,
                currencySettingsRepository = currencySettingsRepository,
                database = database
            )

            val actionableRec = recommendation(
                quality = BudgetRecommendationQuality.COMPLETE,
                isActionable = true
            ).copy(budgetId = 1L, recommendedBudget = 110.0)
            val lowHistoryRec = recommendation(
                quality = BudgetRecommendationQuality.LOW_HISTORY,
                isActionable = false
            ).copy(budgetId = 2L, recommendedBudget = 100.0)
            stubGeneratedRecommendations(actionableRec, lowHistoryRec)

            val actionableBudget = activeBudget().copy(id = 1L, amount = 100.0)
            val lowHistoryBudget = activeBudget().copy(id = 2L, amount = 100.0)
            coEvery { budgetRepository.getActiveBudgets() } returns
                listOf(actionableBudget, lowHistoryBudget)

            viewModel.generateAutopilotRecommendations()
            advanceUntilIdle()

            viewModel.applyAllAutopilotRecommendations()
            advanceUntilIdle()

            // Exactly ONE write: the actionable recommendation's amount, applied
            // to the actionable budget. The LOW_HISTORY budget is untouched.
            val slot = slot<Budget>()
            coVerify(exactly = 1) { budgetRepository.updateBudgetOrThrow(capture(slot)) }
            assertEquals(1L, slot.captured.id)
            assertEquals(110.0, slot.captured.amount, 0.0001)
            // The LOW_HISTORY budget never received a write.
            coVerify(exactly = 0) {
                budgetRepository.updateBudgetOrThrow(match { it.id == 2L })
            }
            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
        }

    // ==================== helpers ====================

    private fun stubGeneratedRecommendations(
        vararg recs: CategoryBudgetRecommendation
    ) {
        coEvery { autopilotEngine.generateRecommendations() } returns BudgetAutopilotRecommendations(
            categoryRecommendations = recs.toList(),
            totalCurrentBudget = recs.sumOf { it.currentBudget },
            totalRecommendedBudget = recs.sumOf { it.recommendedBudget },
            overallDelta = 0.0,
            confidence = recs.first().confidence,
            generatedAt = 0L
        )
    }

    private fun stubSuccessfulApply() {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(activeBudget())
        coEvery { budgetRepository.updateBudget(any()) } returns Result.Success(Unit)
    }

    /**
     * Reads the real (post-initial-value) UI state. The state flow uses
     * SharingStarted.WhileSubscribed, so re-subscribing via `first {}` makes
     * the combine chain recompute deterministically on the test dispatcher.
     */
    private suspend fun currentUiState() =
        viewModel.uiState.first { !it.isLoading }

    private fun activeBudget(): Budget = Budget(
        id = 1L,
        categoryId = 1L,
        amount = 100.0,
        period = BudgetPeriod.MONTHLY,
        startDate = 1_700_000_000_000L,
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.9f
    )

    private fun recommendation(
        quality: BudgetRecommendationQuality,
        isActionable: Boolean
    ): CategoryBudgetRecommendation = CategoryBudgetRecommendation(
        budgetId = 1L,
        categoryId = 1L,
        categoryName = "Groceries",
        currentBudget = 100.0,
        recommendedBudget = 120.0,
        delta = 20.0,
        deltaPercentage = 20.0,
        reason = "test reason",
        confidence = 0.8,
        trend = BudgetTrend.STABLE,
        quality = quality,
        isActionable = isActionable
    )
}
