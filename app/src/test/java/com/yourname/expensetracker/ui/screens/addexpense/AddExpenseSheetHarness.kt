package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.TimeProvider
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * manual-entry-currency B2b: builds a REAL [AddExpenseViewModel] for the Robolectric Compose sheet tests.
 *
 * Only peripheral collaborators are doubles: the repositories behind the view model (mocks), the clock
 * (fixed), the rate store (the in-memory fake of the view model test, no rows, so a foreign currency has
 * no qualifying rate), the rates repository (counting fake) and the privacy repository (a state flow).
 * The preview use case, the eligibility evaluator, the converter and the view model are the real classes.
 *
 * No test dispatcher is installed: `viewModelScope` runs on the Robolectric main looper, and the
 * converter's dispatcher is [Dispatchers.Unconfined], so every evaluation completes inline on the test
 * thread and no virtual time is needed.
 *
 * `entryZone` of every `addManualExpense` call is recorded in [savedZones] (the observable for the
 * AE-34 sheet-session zone).
 */
internal class AddExpenseSheetHarness(home: String = "EUR") {

    val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    val timeProvider = mockk<TimeProvider>(relaxed = true)
    val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()
    val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    val privacyRepository = mockk<PrivacySettingsRepository>()

    val rateStore = AddExpenseRateStoreFake()
    val ratesRepository = AddExpenseRatesRepositoryFake()
    val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = false))
    )

    /** The `entryZone` argument of each `addManualExpense` call, in call order. */
    val savedZones = mutableListOf<ZoneId>()

    init {
        every { timeProvider.now() } returns FIXED_NOW_MS
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf(home)
        every { privacyRepository.observeLoadState() } returns privacyFlow
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent
        coEvery {
            manualExpenseRepository.addManualExpense(
                merchant = any(),
                amount = any(),
                currency = any(),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = any(),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any(),
                recurrenceFrequency = any(),
                recurringNote = any(),
                entryZone = capture(savedZones)
            )
        } returns Result.Success(1L)
    }

    private val createdViewModels = mutableListOf<AddExpenseViewModel>()

    /**
     * Cancels the `viewModelScope` of every view model this harness created (idempotent), so no coroutine stays
     * scheduled on the shared Robolectric main looper after the test class. Optional for existing callers.
     */
    fun close() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
    }

    fun createViewModel(): AddExpenseViewModel = createViewModelInternal().also { createdViewModels += it }

    private fun createViewModelInternal(): AddExpenseViewModel = AddExpenseViewModel(
        manualExpenseRepository = manualExpenseRepository,
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        timeProvider = timeProvider,
        currencySettingsRepository = currencySettingsRepository,
        quickAddParser = QuickAddParser(),
        quickAddLearnedCategoryLookup = quickAddLearnedCategoryLookup,
        userPresentRatePreview = UserPresentRatePreview(
            RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, Dispatchers.Unconfined))
        ),
        currencyRatesRepository = ratesRepository,
        privacySettingsRepository = privacyRepository
    )

    companion object {
        /** 2023-11-14T22:13:20Z. */
        const val FIXED_NOW_MS = 1_700_000_000_000L
    }
}
