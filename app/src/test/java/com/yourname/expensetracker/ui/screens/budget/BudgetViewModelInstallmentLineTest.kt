package com.yourname.expensetracker.ui.screens.budget

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.EntryMoney
import com.yourname.expensetracker.domain.installment.readmodel.EntrySnapshot
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentBudgetInputs
import com.yourname.expensetracker.domain.installment.readmodel.LinkedExpenseMoney
import com.yourname.expensetracker.domain.installment.readmodel.PlanSnapshot
import com.yourname.expensetracker.domain.installment.readmodel.PurchaseMoney
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * S12b (B-2, INV-1, AE-24): the Budget "Includes X in installments" line. One line per budget, in
 * the budget's own currency, only when non-zero, never a cross-currency sum, unavailable never
 * shown as zero, and nothing at all while the installments feature flag is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelInstallmentLineTest : ViewModelTestUtils() {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val autopilotEngine = mockk<BudgetAutopilotEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val installmentRepository = mockk<InstallmentPlanReadRepository>()

    private val periodStart = 1_700_000_000_000L
    private val periodEnd = 1_702_592_000_000L
    private val inPeriod = 1_701_000_000_000L

    @Before
    override fun setup() {
        super.setup()
        coEvery { budgetRepository.getSuggestions() } returns emptyList()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
    }

    @Test
    fun `50a same currency and no other counted currency shows the line`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        val eur = state.installmentLines.getValue(1L) as BudgetInstallmentLine.Included
        assertEquals(50.0, eur.amount.amount, 0.0001)
        assertEquals("EUR", eur.amount.currency)
        assertEquals(1, state.installmentLines.size)
    }

    @Test
    fun `50a a non-EUR budget with only its own currency installments shows its own line`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(2L, "USD", entry(2L, "USD", 2_000L))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(2L, "USD")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        val usd = state.installmentLines.getValue(2L) as BudgetInstallmentLine.Included
        assertEquals(20.0, usd.amount.amount, 0.0001)
        assertEquals("USD", usd.amount.currency)
    }

    @Test
    fun `50a counted installments in another currency make the line unavailable never understated`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L)),
            plan(2L, "USD", entry(2L, "USD", 2_000L))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR"), status(2L, "USD")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[1L])
        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[2L])
    }

    @Test
    fun `50a budget currency different from the card spent currency is unavailable`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR", spentCurrency = "USD")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[1L])
    }

    @Test
    fun `a cancellation from loadBudgetInputs propagates and is not mapped to unavailable`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } throws CancellationException("cancelled")
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")))

        var thrown: CancellationException? = null
        try {
            vm.loadInstallmentInputs()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull(thrown)
    }

    @Test
    fun `a non-cancellation failure of loadBudgetInputs is mapped to Failed`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } throws IllegalStateException("boom")
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")))

        assertEquals(BudgetViewModel.InstallmentInputsResult.Failed, vm.loadInstallmentInputs())
    }

    @Test
    fun `PE-C a statuses-only emission does not reload the installment inputs`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L))
        )
        val statusFlow = MutableStateFlow(listOf(status(1L, "EUR")))
        val vm = viewModel(enabled = true, statuses = statusFlow.value, statusFlow = statusFlow)
        val collector = launch { vm.uiState.collect { } }

        advanceUntilIdle()
        coVerify(exactly = 1) { installmentRepository.loadBudgetInputs() }

        // A budget status changes (a spend), no installment table changes.
        statusFlow.value = listOf(status(1L, "EUR"), status(2L, "EUR"))
        advanceUntilIdle()

        coVerify(exactly = 1) { installmentRepository.loadBudgetInputs() }
        val state = vm.uiState.value
        assertEquals(2, state.budgets.size)
        // The new status still gets its line, computed from the inputs already loaded.
        assertTrue(state.installmentLines[2L] is BudgetInstallmentLine.Included)
        collector.cancel()
    }

    @Test
    fun `PE-C an installment change reloads the inputs and updates the lines`() = runTest(testDispatcher) {
        var current = inputs(plan(1L, "EUR", entry(1L, "EUR", 5_000L)))
        coEvery { installmentRepository.loadBudgetInputs() } answers { current }
        val changes = MutableSharedFlow<Unit>(replay = 1)
        changes.tryEmit(Unit)
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")), changes = changes)
        val collector = launch { vm.uiState.collect { } }

        advanceUntilIdle()
        coVerify(exactly = 1) { installmentRepository.loadBudgetInputs() }
        val before = vm.uiState.value.installmentLines.getValue(1L) as BudgetInstallmentLine.Included
        assertEquals(50.0, before.amount.amount, 0.0001)

        current = inputs(plan(1L, "EUR", entry(1L, "EUR", 5_000L), entry(2L, "EUR", 2_500L)))
        changes.emit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { installmentRepository.loadBudgetInputs() }
        val after = vm.uiState.value.installmentLines.getValue(1L) as BudgetInstallmentLine.Included
        assertEquals(75.0, after.amount.amount, 0.0001)
        collector.cancel()
    }

    @Test
    fun `PE-C a failing change flow is unavailable for every budget never zero`() = runTest(testDispatcher) {
        val vm = viewModel(
            enabled = true,
            statuses = listOf(status(1L, "EUR"), status(2L, "USD")),
            changes = flow { throw IllegalStateException("boom") }
        )

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[1L])
        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[2L])
        coVerify(exactly = 0) { installmentRepository.loadBudgetInputs() }
    }

    @Test
    fun `PE-C the pure line function gives the same lines as the ui state on the same inputs`() = runTest(testDispatcher) {
        val loaded = inputs(plan(1L, "EUR", entry(1L, "EUR", 5_000L)))
        coEvery { installmentRepository.loadBudgetInputs() } returns loaded
        val statuses = listOf(status(1L, "EUR"), status(2L, "EUR", categoryId = 2L))
        val vm = viewModel(enabled = true, statuses = statuses)

        val state = vm.uiState.first { it.budgets.size == 2 }

        assertEquals(
            vm.installmentLinesFor(statuses, BudgetViewModel.InstallmentInputsResult.Loaded(loaded)),
            state.installmentLines
        )
        assertTrue(vm.installmentLinesFor(statuses, null).isEmpty())
    }

    @Test
    fun `the status own period and category reach budgetInclusion`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L)) // linked expense: category 1, dated inPeriod
        )
        val vm = viewModel(
            enabled = true,
            statuses = listOf(
                status(1L, "EUR"), // same category, period contains the charge: line
                status(2L, "EUR", categoryId = 2L), // different category: no line
                status(3L, "EUR", start = periodEnd, end = periodEnd + 1_000L) // period excludes the charge: no line
            )
        )

        val state = vm.uiState.first { it.budgets.size == 3 }

        assertTrue(state.installmentLines[1L] is BudgetInstallmentLine.Included)
        assertNull(state.installmentLines[2L])
        assertNull(state.installmentLines[3L])
        assertEquals(1, state.installmentLines.size)
    }

    @Test
    fun `no line when nothing is counted in the period`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L, date = periodEnd + 1L)),
            plan(2L, "EUR", entry(2L, "EUR", 5_000L, status = InstallmentEntryStatus.SCHEDULED, linked = false))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertNull(state.installmentLines[1L])
        assertTrue(state.installmentLines.isEmpty())
    }

    @Test
    fun `no line and no read while the feature flag is off`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L))
        )
        val vm = viewModel(enabled = false, statuses = listOf(status(1L, "EUR")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertTrue(state.installmentLines.isEmpty())
        coVerify(exactly = 0) { installmentRepository.loadBudgetInputs() }
        // PE-C: with the flag off the repository is not touched at all (no change subscription either).
        verify(exactly = 0) { installmentRepository.observeChanges() }
    }

    @Test
    fun `the flag defaults to the compile-time feature gate`() {
        val vm = viewModel(enabled = null, statuses = emptyList())

        assertEquals(com.yourname.expensetracker.ui.navigation.InstallmentsFeature.ENABLED, vm.installmentsEnabled)
    }

    @Test
    fun `an unreadable paid entry is unavailable and never zero`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } returns inputs(
            plan(1L, "EUR", entry(1L, "EUR", 5_000L, linked = false))
        )
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[1L])
    }

    @Test
    fun `a failed read is unavailable for every budget`() = runTest(testDispatcher) {
        coEvery { installmentRepository.loadBudgetInputs() } throws IllegalStateException("boom")
        val vm = viewModel(enabled = true, statuses = listOf(status(1L, "EUR"), status(2L, "USD")))

        val state = vm.uiState.first { it.budgets.isNotEmpty() }

        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[1L])
        assertEquals(BudgetInstallmentLine.Unavailable, state.installmentLines[2L])
    }

    // ------------------------------------------------------------------ builders

    private fun viewModel(
        enabled: Boolean?,
        statuses: List<BudgetStatus>,
        changes: Flow<Unit> = flowOf(Unit),
        statusFlow: Flow<List<BudgetStatus>> = flowOf(statuses)
    ): BudgetViewModel {
        every { budgetRepository.getBudgetStatuses() } returns statusFlow
        every { installmentRepository.observeChanges() } returns changes
        val vm = BudgetViewModel(
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            offsetEngine = offsetEngine,
            autopilotEngine = autopilotEngine,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository,
            database = mockk(),
            installmentReadRepository = installmentRepository
        )
        if (enabled != null) vm.installmentsEnabled = enabled
        return vm
    }

    private fun inputs(vararg plans: PlanSnapshot) = InstallmentBudgetInputs(plans.toList(), 0)

    private fun entry(
        id: Long,
        currency: String,
        minor: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.PAID,
        date: Long = inPeriod,
        linked: Boolean = true
    ) = EntrySnapshot(
        entryId = id,
        seq = id.toInt(),
        originalScheduledMinor = null,
        linkedExpenseId = if (linked) 100L + id else null,
        statusReason = null,
        money = EntryMoney(
            kind = InstallmentEntryKind.REGULAR,
            status = status,
            dueEpochDay = 20_000L,
            scheduledMinor = minor,
            linkedExpense = if (linked) LinkedExpenseMoney(currency, minor, minor, date, categoryId = 1L) else null
        )
    )

    private fun plan(id: Long, currency: String, vararg entries: EntrySnapshot): PlanSnapshot {
        val planCurrency = InstallmentCurrency.fromCodeOrNull(currency)!!
        return PlanSnapshot(
            planId = id,
            planUuid = "uuid-$id",
            categoryId = 1L,
            displayName = "Synthetic plan $id",
            lenderLabel = null,
            kind = InstallmentPlanKind.PURCHASE_ATTACHED,
            fulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
            status = InstallmentPlanStatus.ACTIVE,
            currency = planCurrency,
            purchaseExpenseId = 500L + id,
            purchase = PurchaseMoney(currency, 60_000L),
            terminalEpochDay = null,
            entries = entries.toList()
        )
    }

    private fun status(
        id: Long,
        currency: String,
        spentCurrency: String = currency,
        categoryId: Long? = 1L,
        start: Long = periodStart,
        end: Long = periodEnd
    ): BudgetStatus = BudgetStatus(
        budget = Budget(
            id = id,
            categoryId = categoryId,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = periodStart,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.9f,
            currency = currency,
            currencyAssumption = "EXPLICIT_HOME_CURRENCY"
        ),
        category = null,
        spentAmount = 80.0,
        remainingAmount = 20.0,
        percentUsed = 0.8f,
        healthStatus = BudgetHealthStatus.WARNING,
        periodStart = start,
        periodEnd = end,
        effectiveLimit = 100.0,
        adjustedSpendBreakdown = com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown(
            personalSpend = 80.0,
            sharedSpend = 0.0,
            reimbursedAmount = 0.0,
            netSharedLiability = 0.0,
            effectiveSpend = 80.0,
            pendingReimbursements = 0.0
        ),
        currency = spentCurrency
    )
}
