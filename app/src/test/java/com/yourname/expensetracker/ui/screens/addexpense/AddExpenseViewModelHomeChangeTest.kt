package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * manual-entry-currency 1b S2b: the entry view model's reaction to a change of the home currency.
 * Oracle: domain-spec "## 1b" INV-1b-20 (a-c) with AE-1b-21 (a, b), AE-1b-22 and AE-1b-23.
 *
 * Home is driven by a replaying shared flow (a StateFlow would swallow a repeated value, and the
 * no-op case needs a re-emission). The preview use case, evaluator and converter are the real
 * classes over the in-memory rate store; the manual-expense repository is a mock whose save can be
 * held open. Synthetic amounts only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelHomeChangeTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()
    private val installmentCoordinator = mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true)
    private val installmentDispatcher = mockk<InstallmentPostCommitDispatcher>(relaxed = true)
    private val privacyRepo = mockk<PrivacySettingsRepository>()

    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )
    private val homeFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

    private lateinit var viewModel: AddExpenseViewModel
    private val athens = ZoneId.of("Europe/Athens")
    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    override fun setup() {
        super.setup()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        Locale.setDefault(Locale.US)
        every { timeProvider.now() } returns
            ZonedDateTime.of(2025, 10, 16, 14, 30, 0, 0, athens).toInstant().toEpochMilli()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { privacyRepo.observeLoadState() } returns privacyFlow
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent
        coEvery { anySave() } returns Result.Success(1L)
    }

    @After
    override fun tearDown() {
        try {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        } finally {
            super.tearDown()
        }
    }

    // ---- helpers ----

    private val st: AddExpenseState get() = viewModel.state.value
    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    private fun newViewModel(initialHome: String) {
        homeFlow.resetReplayCache()
        homeFlow.tryEmit(initialHome)
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns homeFlow
        viewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = repo,
            quickAddParser = QuickAddParser(),
            quickAddLearnedCategoryLookup = quickAddLearnedCategoryLookup,
            userPresentRatePreview = UserPresentRatePreview(
                RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, testDispatcher)),
                rateStore
            ),
            currencyRatesRepository = ratesRepo,
            privacySettingsRepository = privacyRepo,
            installmentPlanCoordinator = installmentCoordinator,
            installmentPostCommitDispatcher = installmentDispatcher
        )
        settle()
    }

    /** Sheet open, home [home], amount "12.50" typed on the home selection (no override). */
    private fun openWithHomeDraft(home: String = "EUR") {
        newViewModel(home)
        viewModel.onSheetOpened()
        settle()
        viewModel.updateAmount("12.50")
        settle()
    }

    private fun changeHome(to: String) {
        homeFlow.tryEmit(to)
        settle()
    }

    /** Matches any `addManualExpense` call (same 22-parameter shape the sibling tests stub). */
    private suspend fun MockKMatcherScope.anySave() = manualExpenseRepository.addManualExpense(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )

    // ---- INV-1b-20(a)/(c), AE-1b-21(a): a home-denominated draft is discarded ----

    @Test
    fun `AE-1b-21a home change with a home draft discards the amount, follows the new home and shows the note`() {
        openWithHomeDraft("EUR")
        assertEquals("12.50", st.amount)
        assertEquals("EUR", st.selectedCurrency)
        assertFalse(st.homeChangeClearedNote)

        changeHome("GBP")

        assertEquals("", st.amount)
        assertEquals("GBP", st.homeCurrency)
        assertEquals("GBP", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
    }

    @Test
    fun `AE-1b-21a home change never saves and never re-denominates the typed figure`() {
        openWithHomeDraft("EUR")

        changeHome("GBP")

        coVerify(exactly = 0) { anySave() }
        // No conversion of 12.50: the field is blank, not a converted figure.
        assertEquals("", st.amount)
        // A save attempt on the cleared draft is refused by validation, never sent.
        viewModel.updateMerchant("Shop")
        settle()
        viewModel.save()
        settle()
        coVerify(exactly = 0) { anySave() }
    }

    // ---- INV-1b-20(b), AE-1b-21(b): an explicit foreign selection keeps its amount ----

    @Test
    fun `AE-1b-21b explicit USD selection keeps its amount and selection when home changes, no note`() {
        newViewModel("EUR")
        viewModel.onSheetOpened()
        settle()
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("12.50")
        settle()
        assertEquals("USD", st.selectedCurrency)

        changeHome("GBP")

        assertEquals("GBP", st.homeCurrency)
        assertEquals("USD", st.selectedCurrency)
        assertEquals("12.50", st.amount)
        assertFalse(st.homeChangeClearedNote)
    }

    @Test
    fun `AE-1b-21d blank amount on home change follows the new home with no note`() {
        newViewModel("EUR")
        viewModel.onSheetOpened()
        settle()
        assertEquals("", st.amount)

        changeHome("GBP")

        assertEquals("GBP", st.selectedCurrency)
        assertEquals("", st.amount)
        assertFalse(st.homeChangeClearedNote)
    }

    // ---- INV-1b-20(c), AE-1b-22: a retained draft between sessions ----

    @Test
    fun `AE-1b-22 retained home draft is discarded when home changes while the sheet is closed`() {
        openWithHomeDraft("EUR")
        viewModel.onSheetLeft()
        settle()
        assertEquals("12.50", st.amount)

        changeHome("GBP")
        viewModel.onSheetOpened()
        settle()

        assertEquals("", st.amount)
        assertEquals("GBP", st.homeCurrency)
        assertEquals("GBP", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
        coVerify(exactly = 0) { anySave() }
    }

    @Test
    fun `AE-1b-22 control retained home draft is kept on reopen when home is unchanged`() {
        openWithHomeDraft("EUR")
        viewModel.onSheetLeft()
        settle()

        viewModel.onSheetOpened()
        settle()

        assertEquals("12.50", st.amount)
        assertEquals("EUR", st.selectedCurrency)
        assertFalse(st.homeChangeClearedNote)
    }

    // ---- INV-1b-20(c) SAVING, AE-1b-23 ----

    private class SavedRequest(var calls: Int = 0, var currency: String? = null, var amount: Double? = null)

    /** Holds the next save open until the returned gate completes; records the request it carried. */
    private fun holdSaveOpen(): Pair<CompletableDeferred<Result<Long>>, SavedRequest> {
        val gate = CompletableDeferred<Result<Long>>()
        val request = SavedRequest()
        coEvery { anySave() } coAnswers {
            request.calls += 1
            request.amount = arg<Double>(1)
            request.currency = arg<String>(2)
            gate.await()
        }
        return gate to request
    }

    private fun startSaveInFlight() {
        viewModel.updateMerchant("Shop")
        settle()
        viewModel.save()
        settle()
        assertTrue(st.isSaving)
    }

    @Test
    fun `AE-1b-23 home change mid-save discards nothing until the save finishes`() {
        val (gate, _) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()

        changeHome("GBP")

        assertTrue(st.isSaving)
        assertEquals("12.50", st.amount)
        assertFalse(st.homeChangeClearedNote)
        gate.complete(Result.Success(1L))
        settle()
    }

    @Test
    fun `AE-1b-23 saved expense keeps its original denomination and no discard happens on Success`() {
        val (gate, request) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()
        changeHome("GBP")

        gate.complete(Result.Success(1L))
        settle()

        assertEquals(1, request.calls)
        assertEquals("EUR", request.currency)
        assertEquals(12.5, request.amount ?: Double.NaN, 0.0)
        assertFalse(st.isSaving)
        assertEquals(SaveResult.Success, st.saveResult)
        assertEquals("12.50", st.amount)
        assertFalse(st.homeChangeClearedNote)
    }

    @Test
    fun `AE-1b-23 failed save discards the draft afterwards with the note`() {
        val (gate, request) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()
        changeHome("GBP")

        gate.complete(Result.Error(null, "synthetic"))
        settle()

        assertEquals(1, request.calls)
        assertEquals("EUR", request.currency)
        assertFalse(st.isSaving)
        assertEquals("", st.amount)
        assertEquals("GBP", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
    }

    // ---- INV-1b-20(c): every home-denominated money field counts as a draft ----

    @Test
    fun `INV-1b-20c installment fixed payment with a blank amount is discarded on home change`() {
        newViewModel("EUR")
        viewModel.onSheetOpened()
        settle()
        viewModel.setInstallmentsEnabled(true, enabled = true)
        settle()
        viewModel.setInstallmentFixedText("100")
        settle()
        assertEquals("", st.amount)
        assertTrue(st.installments.on)
        assertEquals("100", st.installments.fixedText)
        assertFalse(st.homeChangeClearedNote)

        changeHome("GBP")

        assertEquals("", st.installments.fixedText)
        assertEquals("", st.amount)
        assertEquals("GBP", st.homeCurrency)
        assertEquals("GBP", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
        coVerify(exactly = 0) { anySave() }
    }

    @Test
    fun `INV-1b-20c control installment fixed payment is kept when the home is re-emitted unchanged`() {
        newViewModel("EUR")
        viewModel.onSheetOpened()
        settle()
        viewModel.setInstallmentsEnabled(true, enabled = true)
        viewModel.setInstallmentFixedText("100")
        settle()

        changeHome("EUR")

        assertEquals("100", st.installments.fixedText)
        assertFalse(st.homeChangeClearedNote)
    }

    @Test
    fun `INV-1b-20c share amount with a blank amount is discarded on home change`() {
        newViewModel("EUR")
        viewModel.onSheetOpened()
        settle()
        viewModel.updateMyShareAmount("5")
        settle()
        assertEquals("", st.amount)
        assertEquals("5", st.myShareAmount)

        changeHome("GBP")

        assertEquals("", st.myShareAmount)
        assertEquals("", st.amount)
        assertEquals("GBP", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
        coVerify(exactly = 0) { anySave() }
    }

    // ---- INV-1b-20(c) SAVING: one waiter, no discard after success or on an already blank draft ----

    /** Counts clock reads; the discard resets the form, which reads the clock exactly once per discard. */
    private var clockReads = 0

    private fun countClockReads() {
        every { timeProvider.now() } answers {
            clockReads += 1
            ZonedDateTime.of(2025, 10, 16, 14, 30, 0, 0, athens).toInstant().toEpochMilli()
        }
    }

    /** Runs a failing save with [homes] home changes in flight; returns the clock reads made by the settle. */
    private fun clockReadsAfterFailedSaveWithHomeChanges(homes: List<String>): Int {
        countClockReads()
        val (gate, _) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()
        homes.forEach { changeHome(it) }
        assertEquals("12.50", st.amount)
        assertFalse(st.homeChangeClearedNote)

        clockReads = 0
        gate.complete(Result.Error(null, "synthetic"))
        settle()
        return clockReads
    }

    @Test
    fun `INV-1b-20c two home changes during one save discard exactly once after the save fails`() {
        val singleChangeReads = clockReadsAfterFailedSaveWithHomeChanges(listOf("GBP"))
        assertTrue(st.homeChangeClearedNote)
        assertTrue("one discard reads the clock at least once", singleChangeReads >= 1)

        val doubleChangeReads = clockReadsAfterFailedSaveWithHomeChanges(listOf("GBP", "USD"))

        assertEquals(singleChangeReads, doubleChangeReads)
        assertEquals("", st.amount)
        assertEquals("USD", st.homeCurrency)
        assertEquals("USD", st.selectedCurrency)
        assertTrue(st.homeChangeClearedNote)
        assertFalse(st.isSaving)
        coVerify(exactly = 2) { anySave() }
    }

    @Test
    fun `INV-1b-20c two home changes during one save that succeeds discard nothing and show no note`() {
        val (gate, request) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()
        changeHome("GBP")
        changeHome("USD")
        assertFalse(st.homeChangeClearedNote)

        gate.complete(Result.Success(1L))
        settle()

        assertEquals(1, request.calls)
        assertEquals("EUR", request.currency)
        assertEquals(SaveResult.Success, st.saveResult)
        assertEquals("12.50", st.amount)
        assertEquals("USD", st.homeCurrency)
        assertFalse(st.homeChangeClearedNote)
    }

    @Test
    fun `INV-1b-20c waiter resuming on an already blank draft shows no note`() {
        val (gate, request) = holdSaveOpen()
        openWithHomeDraft("EUR")
        startSaveInFlight()
        changeHome("GBP")
        // The user clears the figure while the save is still in flight; nothing is left to discard.
        viewModel.updateAmount("")
        settle()
        assertEquals("", st.amount)

        gate.complete(Result.Error(null, "synthetic"))
        settle()

        assertEquals(1, request.calls)
        assertFalse(st.isSaving)
        assertEquals("", st.amount)
        assertEquals("GBP", st.homeCurrency)
        assertFalse(st.homeChangeClearedNote)
    }

    // ---- the note is one-shot ----

    @Test
    fun `home change note clears on the next amount edit`() {
        openWithHomeDraft("EUR")
        changeHome("GBP")
        assertTrue(st.homeChangeClearedNote)

        viewModel.updateAmount("3")
        settle()

        assertFalse(st.homeChangeClearedNote)
        assertEquals("3", st.amount)
    }

    @Test
    fun `home change note clears on selectCurrency`() {
        openWithHomeDraft("EUR")
        changeHome("GBP")
        assertTrue(st.homeChangeClearedNote)

        viewModel.selectCurrency("USD")
        settle()

        assertFalse(st.homeChangeClearedNote)
        assertEquals("USD", st.selectedCurrency)
    }

    // ---- no-op ----

    @Test
    fun `re-emitting the same home is a no-op and keeps the draft`() {
        openWithHomeDraft("EUR")

        changeHome("EUR")
        changeHome("EUR")

        assertEquals("12.50", st.amount)
        assertEquals("EUR", st.selectedCurrency)
        assertFalse(st.homeChangeClearedNote)
    }
}
