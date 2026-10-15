package com.yourname.expensetracker.ui.screens.budget

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetAutopilotRecommendations
import com.yourname.expensetracker.domain.budget.BudgetRecommendationQuality
import com.yourname.expensetracker.domain.budget.CategoryBudgetRecommendation
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * R3 (RG06A-1 / RG06A-4): [BudgetViewModel] is the only stamping point for budget currency
 * provenance. Hand oracles (home currency = USD unless stated):
 *
 * - create / suggestion        -> currency USD, EXPLICIT_HOME_CURRENCY (entity default EUR/LEGACY dropped)
 * - edit EUR USER_CONFIRMED    -> EUR, USER_CONFIRMED (non-home, known provenance)
 * - edit legacy USD            -> USD, EXPLICIT_HOME_CURRENCY
 * - edit legacy EUR (non-home) -> EUR, LEGACY_DEFAULT unchanged (lead adjustment, D-05b open)
 * - mismatch / unsupported     -> no repository write, bounded error constant
 * - autopilot apply            -> copy keeps the existing stamp
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelCurrencyProvenanceTest : ViewModelTestUtils() {

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
        coEvery { budgetRepository.addBudget(any()) } returns Result.Success(1L)
        coEvery { budgetRepository.updateBudget(any()) } returns Result.Success(Unit)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")

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

    @Test
    fun `create stamps home currency and EXPLICIT_HOME_CURRENCY`() = runTest(testDispatcher) {
        viewModel.addBudget(budget(currency = "USD", assumption = "LEGACY_DEFAULT"), "USD")
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.addBudget(capture(written)) }
        assertEquals("USD", written.captured.currency)
        assertEquals("EXPLICIT_HOME_CURRENCY", written.captured.currencyAssumption)
        assertNull(currentError())
    }

    @Test
    fun `suggestion with entity default EUR is stamped with home currency`() = runTest(testDispatcher) {
        // Suggestion budgets historically kept the entity defaults ("EUR", LEGACY_DEFAULT).
        viewModel.addBudget(budget(currency = "EUR", assumption = "LEGACY_DEFAULT"), "USD")
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.addBudget(capture(written)) }
        assertEquals("USD", written.captured.currency)
        assertEquals("EXPLICIT_HOME_CURRENCY", written.captured.currencyAssumption)
    }

    @Test
    fun `edit of confirmed non-home EUR row keeps EUR and stamps USER_CONFIRMED`() = runTest(testDispatcher) {
        viewModel.updateBudget(budget(id = 5L, currency = "EUR", assumption = "USER_CONFIRMED"), "EUR")
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.updateBudget(capture(written)) }
        assertEquals("EUR", written.captured.currency)
        assertEquals("USER_CONFIRMED", written.captured.currencyAssumption)
        assertNull(currentError())
    }

    @Test
    fun `edit of row stamped for a previous home currency becomes USER_CONFIRMED when no longer home`() =
        runTest(testDispatcher) {
            viewModel.updateBudget(
                budget(id = 5L, currency = "EUR", assumption = "EXPLICIT_HOME_CURRENCY"),
                "EUR"
            )
            advanceUntilIdle()

            val written = slot<Budget>()
            coVerify(exactly = 1) { budgetRepository.updateBudget(capture(written)) }
            assertEquals("EUR", written.captured.currency)
            assertEquals("USER_CONFIRMED", written.captured.currencyAssumption)
        }

    @Test
    fun `edit of legacy home-currency row stamps EXPLICIT_HOME_CURRENCY`() = runTest(testDispatcher) {
        viewModel.updateBudget(budget(id = 5L, currency = "USD", assumption = "LEGACY_DEFAULT"), "USD")
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.updateBudget(capture(written)) }
        assertEquals("USD", written.captured.currency)
        assertEquals("EXPLICIT_HOME_CURRENCY", written.captured.currencyAssumption)
    }

    @Test
    fun `edit of legacy non-home row keeps its legacy assumption and currency`() = runTest(testDispatcher) {
        viewModel.updateBudget(budget(id = 5L, currency = "EUR", assumption = "LEGACY_DEFAULT"), "EUR")
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.updateBudget(capture(written)) }
        assertEquals("EUR", written.captured.currency)
        assertEquals("LEGACY_DEFAULT", written.captured.currencyAssumption)
    }

    @Test
    fun `edit with confirmed currency different from stored currency is rejected without write`() =
        runTest(testDispatcher) {
            viewModel.updateBudget(budget(id = 5L, currency = "EUR", assumption = "USER_CONFIRMED"), "USD")
            advanceUntilIdle()

            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
            assertEquals(BudgetViewModel.ERROR_BUDGET_CURRENCY_UNCONFIRMED, currentError())
        }

    @Test
    fun `create with confirmed currency different from home is rejected without write`() =
        runTest(testDispatcher) {
            viewModel.addBudget(budget(currency = "USD", assumption = "LEGACY_DEFAULT"), "EUR")
            advanceUntilIdle()

            coVerify(exactly = 0) { budgetRepository.addBudget(any()) }
            assertEquals(BudgetViewModel.ERROR_BUDGET_CURRENCY_UNCONFIRMED, currentError())
        }

    @Test
    fun `unsupported confirmed currency on edit is rejected without write`() = runTest(testDispatcher) {
        viewModel.updateBudget(budget(id = 5L, currency = "ZZZ", assumption = "USER_CONFIRMED"), "ZZZ")
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
        assertEquals(BudgetViewModel.ERROR_BUDGET_CURRENCY_UNCONFIRMED, currentError())
    }

    @Test
    fun `malformed confirmed currency on create is rejected without write`() = runTest(testDispatcher) {
        viewModel.addBudget(budget(currency = "USD", assumption = "LEGACY_DEFAULT"), "US")
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.addBudget(any()) }
        assertEquals(BudgetViewModel.ERROR_BUDGET_CURRENCY_UNCONFIRMED, currentError())
    }

    @Test
    fun `home currency lookup failure is rejected without write and reports bounded error`() =
        runTest(testDispatcher) {
            // The VM's own stateIn already captured the setUp flow; only the write-path lookup fails.
            every { currencySettingsRepository.homeCurrency() } returns
                flow<String> { throw IllegalStateException("home currency unavailable") }

            viewModel.addBudget(budget(currency = "USD", assumption = "LEGACY_DEFAULT"), "USD")
            advanceUntilIdle()

            coVerify(exactly = 0) { budgetRepository.addBudget(any()) }
            assertEquals(BudgetViewModel.ERROR_BUDGET_CURRENCY_UNCONFIRMED, currentError())
        }

    @Test
    fun `home currency lookup cancellation is not converted to the bounded error and does not write`() =
        runTest(testDispatcher) {
            every { currencySettingsRepository.homeCurrency() } returns
                flow<String> { throw CancellationException("cancelled") }

            viewModel.updateBudget(budget(id = 5L, currency = "USD", assumption = "LEGACY_DEFAULT"), "USD")
            advanceUntilIdle()

            coVerify(exactly = 0) { budgetRepository.updateBudget(any()) }
            coVerify(exactly = 0) { budgetRepository.addBudget(any()) }
            val state = viewModel.uiState.first { it.homeCurrency != null }
            assertNull(state.error)
        }

    @Test
    fun `autopilot apply keeps the existing currency stamp`() = runTest(testDispatcher) {
        val stamped = budget(id = 1L, currency = "EUR", assumption = "USER_CONFIRMED")
        val rec = CategoryBudgetRecommendation(
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
            displayCurrency = "EUR",
            sourceAmountToApply = MoneyAmount(120.0, CurrencyCode("EUR")),
            quality = BudgetRecommendationQuality.COMPLETE,
            isActionable = true
        )
        coEvery { autopilotEngine.generateRecommendations() } returns BudgetAutopilotRecommendations(
            categoryRecommendations = listOf(rec),
            totalCurrentBudget = 100.0,
            totalRecommendedBudget = 120.0,
            overallDelta = 0.0,
            confidence = 0.8,
            generatedAt = 0L,
            displayCurrency = "EUR"
        )
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(stamped)

        viewModel.generateAutopilotRecommendations()
        advanceUntilIdle()
        viewModel.applyAutopilotRecommendation(rec)
        advanceUntilIdle()

        val written = slot<Budget>()
        coVerify(exactly = 1) { budgetRepository.updateBudget(capture(written)) }
        assertEquals(120.0, written.captured.amount, 0.0001)
        assertEquals("EUR", written.captured.currency)
        assertEquals("USER_CONFIRMED", written.captured.currencyAssumption)
    }

    private suspend fun currentError(): String? = viewModel.uiState.first { !it.isLoading }.error

    private fun budget(
        id: Long = 0L,
        currency: String,
        assumption: String
    ): Budget = Budget(
        id = id,
        categoryId = 1L,
        amount = 100.0,
        period = BudgetPeriod.MONTHLY,
        startDate = 1_700_000_000_000L,
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.9f,
        currency = currency,
        currencyAssumption = assumption
    )
}
