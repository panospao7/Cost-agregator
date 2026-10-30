package com.yourname.expensetracker.ui.screens.addexpense

import android.database.sqlite.SQLiteException
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.transaction.RateRefusalCodes
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.currency.CurrencyAmountInvalidKind
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.quickadd.CategoryMatch
import com.yourname.expensetracker.domain.quickadd.CategorySource
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    // nl-quick-add slice C: the real parser (pure) and a mocked read-only learned-category lookup.
    private val quickAddParser = QuickAddParser()
    private val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()

    private lateinit var viewModel: AddExpenseViewModel
    private var categoriesJob: Job? = null
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    // INV-13 capture: every log line (any priority) with its throwable.
    private data class LoggedLine(val priority: Int, val message: String, val throwable: Throwable?)

    private val loggedLines = mutableListOf<LoggedLine>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedLines += LoggedLine(priority, message, t)
        }
    }

    private val amountInvalidText = UiText.StringResource(R.string.add_expense_error_amount_invalid)
    private val saveFailedText = UiText.StringResource(R.string.add_expense_error_save_failed)

    @After
    override fun tearDown() {
        try {
            categoriesJob?.cancel()
            TimeZone.setDefault(originalTimeZone)
            Timber.uproot(logTree)
        } finally {
            super.tearDown()
        }
    }

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(logTree)
        every { timeProvider.now() } returns 1_700_000_000_000L
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent

        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        // MEC B2a: the exchange-rate setting defaults to Loaded(OFF), so existing tests never refresh.
        every { privacyRepo.observeLoadState() } returns privacyFlow
        viewModel = createViewModel(currencyRepo)
    }

    // MEC B2a collaborators: the REAL preview use case over an in-memory rate store (no value-class
    // mock matchers), a counting rates repository, and a privacy repository that only reads the load state.
    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )
    private val privacyRepo = mockk<PrivacySettingsRepository>()
    private val installmentCoordinator = mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true)
    private val installmentDispatcher = mockk<InstallmentPostCommitDispatcher>(relaxed = true)

    private fun createViewModel(
        currencyRepo: CurrencySettingsRepository,
        privacy: PrivacySettingsRepository = privacyRepo
    ) = AddExpenseViewModel(
        manualExpenseRepository = manualExpenseRepository,
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        timeProvider = timeProvider,
        currencySettingsRepository = currencyRepo,
        quickAddParser = quickAddParser,
        quickAddLearnedCategoryLookup = quickAddLearnedCategoryLookup,
        userPresentRatePreview = UserPresentRatePreview(
            RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, testDispatcher)),
            rateStore
        ),
        currencyRatesRepository = ratesRepo,
        privacySettingsRepository = privacy,
        installmentPlanCoordinator = installmentCoordinator,
        installmentPostCommitDispatcher = installmentDispatcher
    )

    @Test
    fun `reset cancels pending merchant search and prevents stale suggestions`() = runTest(testDispatcher) {
        val delayedSuggestions = listOf(
            MerchantSuggestion(
                merchant = "Coffee House",
                categoryId = 1L,
                avgAmount = 3.5,
                txCount = 4
            )
        )

        coEvery { expenseRepository.searchMerchants("co") } coAnswers {
            delay(1_000)
            delayedSuggestions
        }

        viewModel.updateMerchant("co")
        advanceTimeBy(350)
        viewModel.reset()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.suggestions.isEmpty())
        assertFalse(state.showSuggestions)
        assertTrue(state.merchant.isBlank())
        coVerify(exactly = 1) { expenseRepository.searchMerchants("co") }
    }

    @Test
    fun `setInitialValuesIfBlank applies prefill only once when pristine`() {
        viewModel.setInitialValuesIfBlank(amount = "12.34", merchant = "Coffee")

        val first = viewModel.state.value
        assertEquals("12.34", first.amount)
        assertEquals("Coffee", first.merchant)

        viewModel.setInitialValuesIfBlank(amount = "99.99", merchant = "Late Merchant")

        val second = viewModel.state.value
        assertEquals("12.34", second.amount)
        assertEquals("Coffee", second.merchant)
    }

    @Test
    fun `setInitialValuesIfBlank does not overwrite user edits and re-enables after reset`() {
        viewModel.updateAmount("10.00")
        viewModel.updateMerchant("User Merchant")

        viewModel.setInitialValuesIfBlank(amount = "22.22", merchant = "Incoming Merchant")

        val edited = viewModel.state.value
        assertEquals("10.00", edited.amount)
        assertEquals("User Merchant", edited.merchant)

        viewModel.reset()
        viewModel.setInitialValuesIfBlank(amount = "22.22", merchant = "Incoming Merchant")

        val afterReset = viewModel.state.value
        assertEquals("22.22", afterReset.amount)
        assertEquals("Incoming Merchant", afterReset.merchant)
    }

    @Test
    fun `reset clears initialValuesApplied allowing new prefill on reopen`() {
        viewModel.setInitialValuesIfBlank(amount = "10.00", merchant = "First Merchant")
        assertEquals("10.00", viewModel.state.value.amount)
        assertEquals("First Merchant", viewModel.state.value.merchant)

        viewModel.reset()

        viewModel.setInitialValuesIfBlank(amount = "25.50", merchant = "Second Merchant")
        assertEquals("25.50", viewModel.state.value.amount)
        assertEquals("Second Merchant", viewModel.state.value.merchant)
    }

    // ---- R16 S2: locale-aware amount parsing (validation and save share one parser) ----

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun useGreek() = Locale.setDefault(Locale("el", "GR"))
    private fun useUs() = Locale.setDefault(Locale.US)

    private fun stubAddManualExpense() {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.Success(1L)
    }

    private fun verifySavedAmount(expected: Double, times: Int = 1) {
        coVerify(exactly = times) {
            manualExpenseRepository.addManualExpense(
                merchant = any(),
                amount = eq(expected),
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
                entryZone = any()
            )
        }
    }

    private fun saveWithAmount(typed: String) {
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.reset()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount(typed)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `el_GR comma decimal 12,50 saves 12_50`() {
        useGreek()
        saveWithAmount("12,50")
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(12.50)
    }

    @Test
    fun `el_GR dot grouping with comma decimal saves 1234_56`() {
        useGreek()
        saveWithAmount("1.234,56")
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(1234.56)
    }

    @Test
    fun `el_GR dot grouping without decimals saves 1234 not 1_234`() {
        useGreek()
        saveWithAmount("1.234")
        verifySavedAmount(1234.0)
    }

    @Test
    fun `en_US comma grouping saves 1234_56`() {
        useUs()
        saveWithAmount("1,234.56")
        verifySavedAmount(1234.56)
    }

    @Test
    fun `en_US comma decimal 12,50 saves 12_50 not 1250`() {
        useUs()
        saveWithAmount("12,50")
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(12.50)
    }

    @Test
    fun `mixed separators with invalid grouping are rejected with the existing error and never saved`() {
        useGreek()
        saveWithAmount("1,23.4")
        assertEquals(amountInvalidText, viewModel.state.value.amountError)
        verifySavedAmount(1.234, times = 0)
        coVerify(exactly = 0) {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `el_GR typed 1,234 is truncated by the sanitizer to 1,23 and never read as 1234`() {
        useGreek()
        viewModel.updateAmount("1,234")
        assertEquals("1,23", viewModel.state.value.amount)
    }

    @Test
    fun `clipboard el_GR 1,234 prefills 1234_00 not 1_23`() {
        useGreek()
        viewModel.setInitialValuesIfBlank(amount = "1,234", merchant = "Clip")
        assertEquals("1234.00", viewModel.state.value.amount)
    }

    @Test
    fun `clipboard en_US 1,234 prefills 1234_00`() {
        useUs()
        viewModel.setInitialValuesIfBlank(amount = "1,234", merchant = "Clip")
        assertEquals("1234.00", viewModel.state.value.amount)
    }

    @Test
    fun `clipboard canonical 12_50 prefills 12_50`() {
        useUs()
        viewModel.setInitialValuesIfBlank("12.50")
        assertEquals("12.50", viewModel.state.value.amount)
    }

    @Test
    fun `unparseable clipboard amount keeps the current amount`() {
        useGreek()
        viewModel.setInitialValuesIfBlank(amount = "abc", merchant = "Clip")
        assertEquals("", viewModel.state.value.amount)
    }

    @Test
    fun `existing amount loaded into the field round-trips through sanitize and save in el_GR and en_US`() {
        for (locale in listOf(Locale("el", "GR"), Locale.US)) {
            Locale.setDefault(locale)
            viewModel.reset()
            viewModel.selectSuggestion(
                MerchantSuggestion(merchant = "Edit", categoryId = null, avgAmount = 1234.5, txCount = 2)
            )
            assertEquals(locale.toString(), "1234.50", viewModel.state.value.amount)
            assertTrue(
                locale.toString(),
                com.yourname.expensetracker.ui.util.AmountInputSanitizer.isValid(viewModel.state.value.amount)
            )
        }
        useGreek()
        viewModel.reset()
        viewModel.selectSuggestion(
            MerchantSuggestion(merchant = "Edit", categoryId = null, avgAmount = 1234.5, txCount = 2)
        )
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
        verifySavedAmount(1234.5)
    }

    // ---- Save validation and reset (revived from the retired stress class; Locale pinned to US) ----

    private fun verifyNoManualExpenseSaved() {
        coVerify(exactly = 0) {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `save with empty merchant sets merchantError and never saves`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.StringResource(R.string.add_expense_error_merchant_required), state.merchantError)
        assertNull(state.amountError)
        assertNull(state.saveResult)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `save with empty amount sets amountError and never saves`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.updateMerchant("Test")
        viewModel.updateAmount("")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(amountInvalidText, state.amountError)
        assertNull(state.merchantError)
        assertNull(state.saveResult)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `save with valid merchant and amount returns Success and persists EUR amount once`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.reset()
        viewModel.updateMerchant("Valid Merchant")
        viewModel.updateAmount("50.00")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(SaveResult.Success, state.saveResult)
        assertFalse(state.isSaving)
        assertNull(state.merchantError)
        assertNull(state.amountError)
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = eq("Valid Merchant"),
                amount = eq(50.0),
                currency = eq("EUR"),
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
                entryZone = any()
            )
        }
    }

    @Test
    fun `reset clears merchant and amount but keeps loaded home currency and restarts date from clock`() {
        useUs()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.updateMerchant("Test")
        viewModel.updateAmount("10")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Test", viewModel.state.value.merchant)
        assertEquals("10", viewModel.state.value.amount)

        viewModel.reset()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.merchant)
        assertEquals("", state.amount)
        assertEquals("EUR", state.homeCurrency)
        assertEquals(1_700_000_000_000L, state.date)
    }

    // ---- nl-quick-add slice A: error hygiene (INV-13, INV-15; AE-51..AE-53) ----

    private fun stubAddManualExpenseThrows(t: Throwable) {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } throws t
    }

    private fun stubAddManualExpenseReturns(result: Result<Long>) {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns result
    }

    private fun fillValidFormAndSave() {
        useUs()
        testDispatcher.scheduler.advanceUntilIdle() // home currency load
        viewModel.reset()
        viewModel.updateMerchant("Valid Merchant")
        viewModel.updateAmount("50.00")
        testDispatcher.scheduler.advanceUntilIdle()
        loggedLines.clear()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `AE-52 suggestion search failure logs exactly the bounded code with no throwable and no text`() = runTest(testDispatcher) {
        coEvery { expenseRepository.searchMerchants("καφ") } throws
            SQLiteException("no such column near WHERE merchant LIKE 'καφ%' AND secret=42")

        viewModel.updateMerchant("καφ")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(listOf("MERCHANT_SUGGESTION_SEARCH_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
        assertTrue(viewModel.state.value.suggestions.isEmpty())
        assertFalse(viewModel.state.value.showSuggestions)
    }

    @Test
    fun `suggestion search cancellation is rethrown and not logged or treated as failure`() = runTest(testDispatcher) {
        coEvery { expenseRepository.searchMerchants("co") } throws CancellationException("cancelled")

        viewModel.updateMerchant("co")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `AE-53 save exception shows the generic resource clears isSaving and logs only the code`() {
        stubAddManualExpenseThrows(IllegalStateException("Created expense 42 was not found for Valid Merchant"))

        fillValidFormAndSave()

        val state = viewModel.state.value
        assertEquals(SaveResult.Error(saveFailedText), state.saveResult)
        assertEquals(saveFailedText, state.mutation.error)
        assertFalse(state.isSaving)
        assertFalse(state.mutation.isRunning)
        assertEquals(listOf("ADD_EXPENSE_SAVE_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
    }

    @Test
    fun `AE-53 save SQLiteException never surfaces SQL text`() {
        stubAddManualExpenseThrows(SQLiteException("UNIQUE constraint failed: expenses.dedupe_key (merchant=Valid Merchant)"))

        fillValidFormAndSave()

        assertEquals(SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
        assertEquals(listOf("ADD_EXPENSE_SAVE_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
    }

    @Test
    fun `AE-53 save cancellation propagates without an Error result and isSaving is cleared`() {
        stubAddManualExpenseThrows(CancellationException("scope cancelled"))

        fillValidFormAndSave()

        val state = viewModel.state.value
        assertNull(state.saveResult)
        assertNull(state.mutation.error)
        assertFalse(state.isSaving)
        assertFalse(state.mutation.isRunning)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `Result Error with a repository message maps to the bounded resource and never shows the message`() {
        stubAddManualExpenseReturns(
            Result.Error(IllegalStateException("db path /data/user/0/secret"), "Failed: UNIQUE constraint expenses.dedupe_key")
        )

        fillValidFormAndSave()

        val state = viewModel.state.value
        assertEquals(SaveResult.Error(saveFailedText), state.saveResult)
        assertEquals(saveFailedText, state.mutation.error)
        assertFalse(state.isSaving)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `Result Error without a message maps to the seam default resource`() {
        stubAddManualExpenseReturns(Result.Error())

        fillValidFormAndSave()

        assertEquals(SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `Result Loading maps to the generic save failed resource`() {
        stubAddManualExpenseReturns(Result.Loading)

        fillValidFormAndSave()

        assertEquals(SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `Result Duplicate keeps the Duplicate state and uses the existing duplicate resource for the mutation`() {
        stubAddManualExpenseReturns(Result.Duplicate)

        fillValidFormAndSave()

        val state = viewModel.state.value
        assertEquals(SaveResult.Duplicate, state.saveResult)
        assertEquals(UiText.StringResource(R.string.error_duplicate_transaction), state.mutation.error)
        assertFalse(state.isSaving)
    }

    @Test
    fun `validation messages are resource ids - amount too large`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("2000000.00")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        // B1: the cap comes from CurrencyAmountPolicy (EUR 1,000,000), formatted in the default locale (US pinned).
        assertEquals(
            UiText.StringResource(R.string.mec_amount_too_large, listOf("1,000,000")),
            viewModel.state.value.amountError
        )
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `validation messages are resource ids - future date`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        viewModel.updateDate(1_700_000_000_000L + 3L * 24 * 60 * 60 * 1000)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val expected = UiText.StringResource(R.string.add_expense_error_date_future)
        assertEquals(SaveResult.Error(expected), viewModel.state.value.saveResult)
        assertEquals(expected, viewModel.state.value.mutation.error)
        assertFalse(viewModel.state.value.isSaving)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `validation messages are resource ids - currency still loading`() {
        useUs()
        val loadingCurrencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { loadingCurrencyRepo.homeCurrency() } returns emptyFlow()
        val loadingViewModel = createViewModel(loadingCurrencyRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        loadingViewModel.updateMerchant("Shop")
        loadingViewModel.updateAmount("10.00")

        loadingViewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(loadingViewModel.state.value.homeCurrency)
        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.add_expense_error_currency_loading)),
            loadingViewModel.state.value.saveResult
        )
        verifyNoManualExpenseSaved()
    }

    // =====================================================================================
    // nl-quick-add slice C: Fill contract at VM level (INV-1..3, 7..13, 15..18; AE-1..5, 9, 20..31,
    // 41..43, 50, 51 Fill part; G2-11, G2-13). Context CTX-A: home EUR, Europe/Athens,
    // now 2026-10-16T14:30:15.250+03:00. Nothing here compiles against a real clock or locale.
    // =====================================================================================

    private val athens = ZoneId.of("Europe/Athens")

    private fun athensEpoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, nano: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, nano, athens).toInstant().toEpochMilli()

    private val ctxNow = athensEpoch(2026, 10, 16, 14, 30, 15, 250_000_000)
    private val ctxToday = athensEpoch(2026, 10, 16, 14, 30, 15)
    private val ctxYesterday = athensEpoch(2026, 10, 15, 14, 30, 15)

    private val ctxCategories = listOf(
        Category(id = 1L, name = "Groceries", icon = "G", color = "#000001"),
        Category(id = 2L, name = "Transport", icon = "T", color = "#000002"),
        Category(id = 3L, name = "Food", icon = "F", color = "#000003"),
        Category(id = 15L, name = "Home", icon = "H", color = "#000004"),
        Category(id = 16L, name = "Kids", icon = "K", color = "#000005"),
        Category(id = 17L, name = "Gifts", icon = "I", color = "#000006"),
        Category(id = 19L, name = "Legal & Gov", icon = "L", color = "#000007"),
        Category(id = 20L, name = "Uncategorized", icon = "U", color = "#000008")
    )

    private val st: AddExpenseState get() = viewModel.state.value

    private fun beginFill(pickerCategories: List<Category> = ctxCategories) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
        useUs()
        every { timeProvider.now() } returns ctxNow
        every { categoryRepository.allCategories } returns flowOf(pickerCategories)
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        viewModel = createViewModel(currencyRepo)
        // The picker list is a WhileSubscribed StateFlow: subscribe as the Sheet does.
        categoriesJob = CoroutineScope(testDispatcher).launch { viewModel.categories.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        loggedLines.clear()
    }

    private fun fillAndSettle(line: String) {
        viewModel.fill(line)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubLearned(merchant: String, categoryId: Long) {
        coEvery { quickAddLearnedCategoryLookup.lookup(match { it.text == merchant }, any()) } returns
            LearnedCategoryLookupResult.Found(CategoryMatch(categoryId, CategorySource.LEARNED))
    }

    private fun stubAddManualExpenseSuspending() {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers {
            delay(10_000)
            Result.Success(1L)
        }
    }

    private fun verifyOneSavedExpense(
        merchant: String,
        amount: Double,
        currency: String,
        categoryId: Long?,
        paymentMethod: PaymentMethod,
        date: Long
    ) {
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = eq(merchant),
                amount = eq(amount),
                currency = eq(currency),
                categoryId = if (categoryId == null) isNull() else eq(categoryId),
                transactionType = eq(TransactionType.PURCHASE),
                paymentMethod = eq(paymentMethod),
                date = eq(date),
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
                entryZone = any()
            )
        }
    }

    // ---- AE-1 .. AE-4: learned parts ----

    @Test
    fun `AE-1 fill with a learned row fills all fields marks the category LEARNED and logs FILL_FULL`() {
        beginFill()
        stubLearned("καφές", 3L)

        fillAndSettle("καφές 3,50 χθες")

        assertEquals("3.50", st.amount)
        assertEquals("καφές", st.merchant)
        assertEquals(ctxYesterday, st.date)
        assertEquals(PaymentMethod.CASH, st.paymentMethod)
        assertEquals(3L, st.selectedCategoryId)
        assertEquals(CategoryFillSource.LEARNED, st.categoryFillSource)
        assertTrue(st.fieldFlags.isEmpty())
        assertEquals(OutcomeCode.FILL_FULL, st.lastFillOutcome)
        assertEquals(FieldOrigin.FILL, st.fieldOrigins[QuickAddField.AMOUNT])
        assertEquals(FieldOrigin.FILL, st.fieldOrigins[QuickAddField.MERCHANT])
        assertEquals(FieldOrigin.FILL, st.fieldOrigins[QuickAddField.DATE])
        assertEquals(FieldOrigin.FILL, st.fieldOrigins[QuickAddField.CATEGORY])
        assertEquals(FieldOrigin.DEFAULT, st.fieldOrigins[QuickAddField.PAYMENT])
        assertEquals(listOf("FILL_FULL"), loggedLines.map { it.message })
        coVerify(exactly = 1) { quickAddLearnedCategoryLookup.lookup(any(), any()) }

        // Fill writes nothing: no repository write or read beyond the category picker flow (INV-1).
        verify(atLeast = 1) { categoryRepository.allCategories }
        confirmVerified(manualExpenseRepository, expenseRepository, categoryRepository)
    }

    @Test
    fun `AE-1 without a learned row the category stays null and everything else is identical`() {
        beginFill()

        fillAndSettle("καφές 3,50 χθες")

        assertEquals("3.50", st.amount)
        assertEquals("καφές", st.merchant)
        assertEquals(ctxYesterday, st.date)
        assertNull(st.selectedCategoryId)
        assertNull(st.categoryFillSource)
        assertEquals(OutcomeCode.FILL_FULL, st.lastFillOutcome)
    }

    @Test
    fun `AE-2 an exact category name wins and the learned lookup is not consulted`() {
        beginFill()
        val dateBefore = st.date

        fillAndSettle("groceries 42.30 lidl")

        assertEquals("42.30", st.amount)
        assertEquals("lidl", st.merchant)
        assertEquals(1L, st.selectedCategoryId)
        assertEquals(CategoryFillSource.EXACT_NAME, st.categoryFillSource)
        assertEquals(dateBefore, st.date)
        assertEquals(OutcomeCode.FILL_FULL, st.lastFillOutcome)
        coVerify(exactly = 0) { quickAddLearnedCategoryLookup.lookup(any(), any()) }
    }

    @Test
    fun `AE-3 taxi 15 euro today card fills CARD and the learned Transport category`() {
        beginFill()
        stubLearned("taxi", 2L)

        fillAndSettle("taxi 15€ σήμερα κάρτα")

        assertEquals("15.00", st.amount)
        assertEquals("taxi", st.merchant)
        assertEquals(ctxToday, st.date)
        assertEquals(PaymentMethod.CARD, st.paymentMethod)
        assertEquals(2L, st.selectedCategoryId)
        assertEquals(CategoryFillSource.LEARNED, st.categoryFillSource)
        assertEquals(OutcomeCode.FILL_FULL, st.lastFillOutcome)
    }

    @Test
    fun `AE-4 the lookup is keyed on the merchant text the same Fill wrote`() {
        beginFill()
        stubLearned("kafes", 3L)

        fillAndSettle("kafes 3.50")

        assertEquals("3.50", st.amount)
        assertEquals("kafes", st.merchant)
        assertEquals(3L, st.selectedCategoryId)
        coVerify(exactly = 1) {
            quickAddLearnedCategoryLookup.lookup(match { it.text == "kafes" }, any())
        }
    }

    // ---- AE-5 foreign + save tail ----

    @Test
    fun `AE-5 foreign amount is flagged blank and Save shows the invalid amount error and persists nothing`() {
        beginFill()
        stubAddManualExpense()

        fillAndSettle("dinner 20 usd")

        assertEquals("", st.amount)
        assertEquals(FlagReason.AMOUNT_FOREIGN_CURRENCY, st.fieldFlags[QuickAddField.AMOUNT])
        assertEquals("dinner", st.merchant)
        assertNull(st.selectedCategoryId)
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), st.lineHints)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals(listOf("FOREIGN_CURRENCY_BLOCKED"), loggedLines.map { it.message })

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(amountInvalidText, st.amountError)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `AE-5 tail typing 18 clears the flag and Save creates exactly one expense in the home currency`() {
        beginFill()
        stubAddManualExpense()
        fillAndSettle("dinner 20 usd")

        viewModel.updateAmount("18")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.AMOUNT])
        assertNull(st.fieldFlags[QuickAddField.AMOUNT])

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, st.saveResult)
        verifyOneSavedExpense("dinner", 18.0, "EUR", null, PaymentMethod.CASH, ctxNow)
    }

    // ---- AE-9, AE-20 .. AE-31 ----

    @Test
    fun `AE-9 a USER amount is kept and merchant and category are still filled`() {
        beginFill()
        stubLearned("καφές", 3L)
        viewModel.updateAmount("4.00")

        fillAndSettle("καφές 3,50")

        assertEquals("4.00", st.amount)
        assertNull(st.fieldFlags[QuickAddField.AMOUNT])
        assertEquals("καφές", st.merchant)
        assertEquals(3L, st.selectedCategoryId)
        assertEquals(OutcomeCode.FILL_PARTIAL, st.lastFillOutcome)
    }

    @Test
    fun `AE-20 a clipboard amount survives an amount-less line is replaced by a typed one and cleared by a foreign one`() {
        beginFill()
        viewModel.setInitialValuesIfBlank("12.40")
        assertEquals(FieldOrigin.PREFILL, st.fieldOrigins[QuickAddField.AMOUNT])
        fillAndSettle("lidl χθες")
        assertEquals("12.40", st.amount)
        assertEquals(FieldOrigin.PREFILL, st.fieldOrigins[QuickAddField.AMOUNT])
        assertEquals("lidl", st.merchant)

        viewModel.reset()
        viewModel.setInitialValuesIfBlank("12.40")
        fillAndSettle("lidl 9,99")
        assertEquals("9.99", st.amount)

        viewModel.reset()
        viewModel.setInitialValuesIfBlank("12.40")
        fillAndSettle("dinner 20 usd")
        assertEquals("", st.amount)
        assertEquals(FlagReason.AMOUNT_FOREIGN_CURRENCY, st.fieldFlags[QuickAddField.AMOUNT])
    }

    @Test
    fun `AE-21 a clipboard prefill arriving after a Fill is ignored`() {
        beginFill()
        fillAndSettle("χθες")

        viewModel.setInitialValuesIfBlank("5.00")

        assertEquals("", st.amount)
        assertEquals(FieldOrigin.DEFAULT, st.fieldOrigins[QuickAddField.AMOUNT])
    }

    @Test
    fun `AE-21 a prefill before the Fill marks PREFILL and a blank form counts as not edited`() {
        beginFill()

        viewModel.setInitialValuesIfBlank(amount = "12.40", merchant = "Clip")

        assertEquals(FieldOrigin.PREFILL, st.fieldOrigins[QuickAddField.AMOUNT])
        assertEquals(FieldOrigin.PREFILL, st.fieldOrigins[QuickAddField.MERCHANT])
        fillAndSettle("kafes 3.50")
        assertEquals("3.50", st.amount)
        assertEquals("kafes", st.merchant)
    }

    @Test
    fun `AE-22 a FILL payment not mentioned by the second Fill resets to CASH and DEFAULT`() {
        beginFill()
        fillAndSettle("taxi 15 κάρτα")
        assertEquals(PaymentMethod.CARD, st.paymentMethod)

        fillAndSettle("taxi 15")

        assertEquals(PaymentMethod.CASH, st.paymentMethod)
        assertEquals(FieldOrigin.DEFAULT, st.fieldOrigins[QuickAddField.PAYMENT])
    }

    @Test
    fun `AE-23 a merchant edited after Fill is kept by the next Fill`() {
        beginFill()
        fillAndSettle("taxi 15")

        viewModel.updateMerchant("Taxi Beat")
        fillAndSettle("uber 20 χθες")

        assertEquals("Taxi Beat", st.merchant)
        assertEquals("20.00", st.amount)
        assertEquals(ctxYesterday, st.date)
        coVerify(exactly = 0) { expenseRepository.searchMerchants(any()) }
    }

    @Test
    fun `AE-24 a user-cleared amount is fillable again`() {
        beginFill()
        viewModel.updateAmount("4")
        viewModel.updateAmount("")

        fillAndSettle("καφές 3,50")

        assertEquals("3.50", st.amount)
        assertEquals(FieldOrigin.FILL, st.fieldOrigins[QuickAddField.AMOUNT])
    }

    @Test
    fun `AE-25 a picked date is kept while amount and merchant are filled`() {
        beginFill()
        val picked = athensEpoch(2026, 10, 10, 12, 0, 0)
        viewModel.updateDate(picked)

        fillAndSettle("καφές 3,50 χθες")

        assertEquals(picked, st.date)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.DATE])
        assertEquals("3.50", st.amount)
        assertEquals("καφές", st.merchant)
    }

    @Test
    fun `AE-26 a suggestion tap after a foreign Fill keeps the amount blank and flagged`() {
        beginFill()
        fillAndSettle("dinner 20 usd")

        viewModel.selectSuggestion(
            MerchantSuggestion(merchant = "Dinner Place", categoryId = 3L, avgAmount = 25.0, txCount = 4)
        )

        assertEquals("Dinner Place", st.merchant)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.MERCHANT])
        assertEquals(3L, st.selectedCategoryId)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.CATEGORY])
        assertEquals("", st.amount)
        assertEquals(FlagReason.AMOUNT_FOREIGN_CURRENCY, st.fieldFlags[QuickAddField.AMOUNT])
    }

    @Test
    fun `a suggestion tap writes a blank unflagged amount as a USER edit`() {
        beginFill()

        viewModel.selectSuggestion(
            MerchantSuggestion(merchant = "Dinner Place", categoryId = null, avgAmount = 25.0, txCount = 4)
        )

        assertEquals("25.00", st.amount)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.AMOUNT])
        assertEquals(FieldOrigin.DEFAULT, st.fieldOrigins[QuickAddField.CATEGORY])
    }

    @Test
    fun `AE-27 Fill never searches merchants and hides open suggestions`() = runTest(testDispatcher) {
        beginFill()
        coEvery { expenseRepository.searchMerchants("ka") } returns
            listOf(MerchantSuggestion(merchant = "Kafes Place", categoryId = null, avgAmount = 3.5, txCount = 2))
        viewModel.updateMerchant("ka")
        advanceTimeBy(350)
        assertTrue(st.showSuggestions)
        coVerify(exactly = 1) { expenseRepository.searchMerchants("ka") }

        fillAndSettle("kafes 3.50")

        assertFalse(st.showSuggestions)
        assertTrue(st.suggestions.isEmpty())
        assertEquals("3.50", st.amount)
        coVerify(exactly = 1) { expenseRepository.searchMerchants(any()) }
    }

    @Test
    fun `AE-27 Fill cancels a pending debounced search before it runs`() = runTest(testDispatcher) {
        beginFill()
        viewModel.updateMerchant("ka")

        viewModel.fill("kafes 3.50")
        advanceUntilIdle()

        coVerify(exactly = 0) { expenseRepository.searchMerchants(any()) }
        assertFalse(st.showSuggestions)
    }

    @Test
    fun `AE-28 Fill has no effect while a save is running`() = runTest(testDispatcher) {
        beginFill()
        stubAddManualExpenseSuspending()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        advanceUntilIdle()
        viewModel.save()
        advanceTimeBy(100)
        assertTrue(st.isSaving)
        val before = st
        loggedLines.clear()

        viewModel.fill("kafes 3.50")
        advanceTimeBy(100)

        assertEquals(before, st)
        assertNull(st.lastFillOutcome)
        assertTrue(loggedLines.isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun `AE-28 Fill has no effect while the home currency is still loading and for a blank line`() {
        beginFill()
        val loadingRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { loadingRepo.homeCurrency() } returns emptyFlow()
        val loading = createViewModel(loadingRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        val before = loading.state.value

        loading.fill("kafes 3.50")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(before.homeCurrency)
        assertEquals(before, loading.state.value)

        val blankBefore = st
        viewModel.fill("   ")
        viewModel.fill("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(blankBefore, st)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `AE-29 a suspended lookup result never overwrites a category the user picked meanwhile`() = runTest(testDispatcher) {
        beginFill()
        val gate = CompletableDeferred<LearnedCategoryLookupResult>()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers { gate.await() }
        viewModel.fill("kafes 3.50")
        advanceUntilIdle()
        assertNull(st.lastFillOutcome)

        viewModel.selectCategory(2L)
        gate.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceUntilIdle()

        assertEquals(2L, st.selectedCategoryId)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.CATEGORY])
        assertNull(st.categoryFillSource)
        assertEquals("3.50", st.amount)
        assertEquals("kafes", st.merchant)
    }

    @Test
    fun `AE-29 a newer Fill supersedes a pending older one and only the newer result is applied`() = runTest(testDispatcher) {
        beginFill()
        val gateA = CompletableDeferred<LearnedCategoryLookupResult>()
        var calls = 0
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers {
            if (calls++ == 0) gateA.await() else LearnedCategoryLookupResult.Absent
        }
        viewModel.fill("kafes 3.50")
        advanceUntilIdle()

        viewModel.fill("taxi 9")
        advanceUntilIdle()
        gateA.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceUntilIdle()

        assertEquals("taxi", st.merchant)
        assertEquals("9.00", st.amount)
        assertNull(st.selectedCategoryId)
        assertEquals(listOf("FILL_FULL"), loggedLines.map { it.message })
    }

    @Test
    fun `reset cancels a pending Fill so a late result is never applied`() = runTest(testDispatcher) {
        beginFill()
        val gate = CompletableDeferred<LearnedCategoryLookupResult>()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers { gate.await() }
        viewModel.fill("kafes 3.50")
        advanceUntilIdle()

        viewModel.reset()
        gate.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceUntilIdle()

        assertEquals("", st.amount)
        assertEquals("", st.merchant)
        assertNull(st.lastFillOutcome)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `AE-28 a Fill waiting on its lookup is skipped when a save is running at apply time`() = runTest(testDispatcher) {
        beginFill()
        stubAddManualExpenseSuspending()
        val gate = CompletableDeferred<LearnedCategoryLookupResult>()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers { gate.await() }

        viewModel.fill("kafes 3.50")
        advanceTimeBy(100)
        assertNull(st.lastFillOutcome)

        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        viewModel.save()
        advanceTimeBy(100)
        assertTrue(st.isSaving)
        val before = st
        loggedLines.clear()

        gate.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceTimeBy(100)

        assertEquals(before, st)
        assertNull(st.lastFillOutcome)
        assertEquals("Shop", st.merchant)
        assertEquals("10.00", st.amount)
        assertTrue(loggedLines.isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun `INV-18 a lookup that ignores cancellation is not applied after reset`() = runTest(testDispatcher) {
        beginFill()
        val gate = CompletableDeferred<LearnedCategoryLookupResult>()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
        }
        viewModel.fill("kafes 3.50")
        advanceUntilIdle()

        viewModel.reset()
        gate.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceUntilIdle()

        assertEquals("", st.amount)
        assertEquals("", st.merchant)
        assertNull(st.selectedCategoryId)
        assertNull(st.lastFillOutcome)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `INV-18 a lookup that ignores cancellation is not applied after a newer Fill`() = runTest(testDispatcher) {
        beginFill()
        val gateA = CompletableDeferred<LearnedCategoryLookupResult>()
        var calls = 0
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } coAnswers {
            if (calls++ == 0) withContext(NonCancellable) { gateA.await() } else LearnedCategoryLookupResult.Absent
        }
        viewModel.fill("kafes 3.50")
        advanceUntilIdle()

        viewModel.fill("taxi 9")
        advanceUntilIdle()
        gateA.complete(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)))
        advanceUntilIdle()

        assertEquals("taxi", st.merchant)
        assertEquals("9.00", st.amount)
        assertNull(st.selectedCategoryId)
        assertEquals(listOf("FILL_FULL"), loggedLines.map { it.message })
    }

    @Test
    fun `AE-30 filling the same line twice gives an identical state and the same outcome`() {
        beginFill()
        stubLearned("καφές", 3L)

        fillAndSettle("καφές 3,50 χθες")
        val first = st
        fillAndSettle("καφές 3,50 χθες")

        assertEquals(first, st)
        assertEquals(listOf("FILL_FULL", "FILL_FULL"), loggedLines.map { it.message })
    }

    @Test
    fun `AE-31 Fill leaves notes recurring transaction type and home currency alone`() {
        beginFill()
        viewModel.updateNotes("x")
        viewModel.toggleRecurring()
        viewModel.selectTransactionType(TransactionType.TRANSFER)

        fillAndSettle("kafes 3.50 χθες κάρτα")

        assertEquals("x", st.notes)
        assertTrue(st.isRecurring)
        assertEquals(TransactionType.TRANSFER, st.transactionType)
        assertEquals("EUR", st.homeCurrency)
        assertEquals("3.50", st.amount)
    }

    @Test
    fun `Fill clears a stale save result and the errors of the fields it wrote`() {
        beginFill()
        stubAddManualExpenseReturns(Result.Error())

        // A stale save result is cleared.
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(st.saveResult is SaveResult.Error)
        fillAndSettle("kafes 3.50")
        assertNull(st.saveResult)

        // A merchant error is cleared when Fill writes the merchant.
        viewModel.reset()
        viewModel.updateAmount("10.00")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(st.merchantError != null)
        fillAndSettle("kafes 3.50")
        assertNull(st.merchantError)

        // An amount error is cleared when Fill writes the amount.
        viewModel.reset()
        viewModel.updateMerchant("Shop")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(st.amountError != null)
        fillAndSettle("kafes 3.50")
        assertNull(st.amountError)
    }

    // ---- AE-41 .. AE-43 ----

    @Test
    fun `AE-41 Fill then Save calls addManualExpense exactly once with the filled values in the home currency`() {
        beginFill()
        stubAddManualExpense()
        stubLearned("καφές", 3L)
        fillAndSettle("καφές 3,50 χθες")

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, st.saveResult)
        verifyOneSavedExpense("καφές", 3.5, "EUR", 3L, PaymentMethod.CASH, ctxYesterday)
    }

    @Test
    fun `AE-42 a Duplicate result is shown and the filled fields are kept`() {
        beginFill()
        stubAddManualExpenseReturns(Result.Duplicate)
        fillAndSettle("καφές 3,50 χθες")

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Duplicate, st.saveResult)
        assertFalse(st.isSaving)
        assertEquals("3.50", st.amount)
        assertEquals("καφές", st.merchant)
        assertEquals(ctxYesterday, st.date)
    }

    @Test
    fun `AE-43 the same line saved twice is a Duplicate the second time`() {
        beginFill()
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returnsMany listOf<Result<Long>>(Result.Success(1L), Result.Duplicate)
        fillAndSettle("καφές 3,50")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SaveResult.Success, st.saveResult)

        viewModel.reset()
        fillAndSettle("καφές 3,50")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Duplicate, st.saveResult)
        assertEquals("3.50", st.amount)
    }

    // ---- AE-50, AE-51 ----

    @Test
    fun `AE-50 state toString and every VM field are free of the typed line and consumed tokens`() {
        beginFill()
        val line = "taxi 15 κάρτα χθες"

        fillAndSettle(line)

        val rendered = st.toString()
        assertFalse(rendered.contains("κάρτα"))
        assertFalse(rendered.contains("χθες"))
        assertFalse(rendered.contains(line))
        // Type-based guard (owner-approvals item 63): no field may be able to hold the typed line.
        val forbiddenTypes = listOf<Class<*>>(
            String::class.java,
            CharSequence::class.java,
            com.yourname.expensetracker.domain.quickadd.QuickAddInput::class.java,
            com.yourname.expensetracker.domain.quickadd.QuickAddParseResult::class.java,
            com.yourname.expensetracker.domain.quickadd.MerchantText::class.java
        )
        // Item 71a: the VM-held summary snapshot is enums, sets and a Boolean only.
        assertEquals(
            QuickAddSummarySnapshot::class.java,
            AddExpenseState::class.java.getDeclaredField("fillSummary").type
        )
        for (field in QuickAddSummarySnapshot::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
            assertFalse(
                "snapshot field ${field.name} has the forbidden type ${field.type.simpleName}",
                forbiddenTypes.any { it.isAssignableFrom(field.type) }
            )
        }
        assertFalse(st.fillSummary.toString().contains("κάρτα"))
        // Static fields are the bounded log-code constants, not instance state.
        for (field in AddExpenseViewModel::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
            assertFalse(
                "field ${field.name} has the forbidden type ${field.type.simpleName}",
                forbiddenTypes.any { it.isAssignableFrom(field.type) }
            )
        }
        for (field in AddExpenseViewModel::class.java.declaredFields) {
            field.isAccessible = true
            val text = field.get(viewModel)?.toString().orEmpty()
            assertFalse("field ${field.name} holds the line", text.contains(line))
            assertFalse("field ${field.name} holds a consumed token", text.contains("κάρτα") || text.contains("χθες"))
        }
    }

    @Test
    fun `AE-51 a lookup failure logs only the bounded code and the outcome and keeps the category empty`() {
        beginFill()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } throws
            SQLiteException("no such column near 'καφές' secret=42")

        fillAndSettle("καφές 3,50")

        assertEquals(
            listOf("QUICK_ADD_CATEGORY_LOOKUP_FAILED", "FILL_FULL"),
            loggedLines.map { it.message }
        )
        assertTrue(loggedLines.all { it.throwable == null })
        assertNull(st.selectedCategoryId)
        assertEquals("3.50", st.amount)
        assertEquals("καφές", st.merchant)
    }

    @Test
    fun `AE-51 a Failed lookup result also logs only the bounded code`() {
        beginFill()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Failed

        fillAndSettle("καφές 3,50")

        assertEquals(
            listOf("QUICK_ADD_CATEGORY_LOOKUP_FAILED", "FILL_FULL"),
            loggedLines.map { it.message }
        )
        assertNull(st.selectedCategoryId)
    }

    @Test
    fun `a lookup cancellation is rethrown logs nothing and applies nothing`() {
        beginFill()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } throws CancellationException("cancelled")

        fillAndSettle("καφές 3,50")

        assertTrue(loggedLines.isEmpty())
        assertEquals("", st.amount)
        assertNull(st.lastFillOutcome)
    }

    @Test
    fun `AE-51 Fill plus search failure plus save failure only ever log allowed constants without throwables or line text`() = runTest(testDispatcher) {
        beginFill()
        val line = "καφές 3,50 χθες"
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } throws IllegalStateException("db path $line")
        coEvery { expenseRepository.searchMerchants("Sh") } throws SQLiteException("LIKE '$line'")
        stubAddManualExpenseThrows(IllegalStateException("Created expense 42 was not found for $line"))

        viewModel.fill(line)
        advanceUntilIdle()
        viewModel.updateMerchant("Sh")
        advanceTimeBy(350)
        advanceUntilIdle()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()

        val allowed = setOf(
            "FILL_FULL", "FILL_PARTIAL", "FILL_NONE", "FOREIGN_CURRENCY_BLOCKED",
            "MERCHANT_SUGGESTION_SEARCH_FAILED", "ADD_EXPENSE_SAVE_FAILED", "QUICK_ADD_CATEGORY_LOOKUP_FAILED"
        )
        assertTrue(loggedLines.isNotEmpty())
        assertTrue(loggedLines.all { it.message in allowed })
        assertTrue(loggedLines.all { it.throwable == null })
        assertTrue(loggedLines.none { it.message.contains("καφ") || it.message.contains("3,50") || it.message.contains("χθες") })
        assertTrue(loggedLines.any { it.message == "QUICK_ADD_CATEGORY_LOOKUP_FAILED" })
        assertTrue(loggedLines.any { it.message == "MERCHANT_SUGGESTION_SEARCH_FAILED" })
        assertTrue(loggedLines.any { it.message == "ADD_EXPENSE_SAVE_FAILED" })
    }

    // ---- G2-11: category source and clearCategory ----

    @Test
    fun `G2-11 the category source follows the fill and is cleared by any user category edit`() {
        beginFill()
        fillAndSettle("groceries 42.30 lidl")
        assertEquals(CategoryFillSource.EXACT_NAME, st.categoryFillSource)

        viewModel.selectCategory(2L)

        assertNull(st.categoryFillSource)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.CATEGORY])
        assertEquals(2L, st.selectedCategoryId)
    }

    @Test
    fun `G2-11 clearCategory removes the category as a USER edit and no later Fill refills it`() {
        beginFill()
        stubLearned("kafes", 3L)
        fillAndSettle("kafes 3.50")
        assertEquals(3L, st.selectedCategoryId)
        assertEquals(CategoryFillSource.LEARNED, st.categoryFillSource)

        viewModel.clearCategory()

        assertNull(st.selectedCategoryId)
        assertNull(st.categoryFillSource)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.CATEGORY])

        fillAndSettle("kafes 4.00")
        assertNull(st.selectedCategoryId)
        fillAndSettle("groceries 5.00 lidl")
        assertNull(st.selectedCategoryId)
        assertNull(st.categoryFillSource)
        assertEquals("5.00", st.amount)
    }

    @Test
    fun `a suggestion with a category is a USER category edit and clears the category source`() {
        beginFill()
        fillAndSettle("groceries 42.30 lidl")

        viewModel.selectSuggestion(
            MerchantSuggestion(merchant = "Dinner Place", categoryId = 3L, avgAmount = 25.0, txCount = 4)
        )

        assertEquals(3L, st.selectedCategoryId)
        assertNull(st.categoryFillSource)
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.CATEGORY])
    }

    // ---- G2-13: the summary survives edits ----

    @Test
    fun `G2-13 lastFillOutcome and line hints survive later edits and only reset clears them`() {
        beginFill()
        fillAndSettle("dinner 20 usd")
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), st.lineHints)

        viewModel.updateAmount("18")
        viewModel.updateMerchant("Dinner Place")
        viewModel.selectCategory(3L)
        viewModel.clearCategory()
        viewModel.updateDate(ctxYesterday)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)

        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), st.lineHints)

        viewModel.reset()

        assertNull(st.lastFillOutcome)
        assertTrue(st.lineHints.isEmpty())
        assertTrue(st.fieldFlags.isEmpty())
        assertNull(st.categoryFillSource)
        assertTrue(st.fieldOrigins.values.all { it == FieldOrigin.DEFAULT })
    }

    @Test
    fun `item 71a the summary snapshot is computed in the Fill apply, survives edits, and reset clears it`() {
        beginFill()
        assertNull(st.fillSummary)

        fillAndSettle("dinner 20 usd")
        val atFill = st.fillSummary!!
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, atFill.outcome)
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), atFill.hints)
        assertEquals(st.fieldFlags.keys, atFill.flagged)
        assertTrue(QuickAddField.AMOUNT in atFill.flagged)
        assertEquals(
            QuickAddField.values().filter { st.fieldOrigins[it] == FieldOrigin.FILL && it !in st.fieldFlags }.toSet(),
            atFill.filled
        )
        assertTrue(atFill.filled.none { it in atFill.flagged })
        assertFalse(atFill.keptEdits)

        // User edits never rewrite the snapshot; the live origins and flags do change.
        viewModel.updateAmount("18")
        viewModel.updateMerchant("Dinner Place")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        assertEquals(atFill, st.fillSummary)
        assertTrue(st.fieldFlags.isEmpty())
        assertEquals(FieldOrigin.USER, st.fieldOrigins[QuickAddField.AMOUNT])

        viewModel.reset()
        assertNull(st.fillSummary)
    }

    @Test
    fun `item 71a a second Fill after a user edit replaces the snapshot and records the kept edit`() {
        beginFill()
        fillAndSettle("dinner 20 usd")
        val first = st.fillSummary!!
        assertFalse(first.keptEdits)

        viewModel.updateAmount("18")
        fillAndSettle("dinner 20 usd")

        val second = st.fillSummary!!
        assertNotEquals(first, second)
        assertTrue(second.keptEdits)
        assertFalse(QuickAddField.AMOUNT in second.filled)
        assertFalse(QuickAddField.AMOUNT in second.flagged)
    }

    @Test
    fun `item 71a edited then rotated - the retained VM state gives the same summary lines`() {
        beginFill()
        fillAndSettle("dinner 20 usd")
        val linesAtFill = QuickAddUiMapper.summaryLines(st.fillSummary, st.homeCurrency)
        assertTrue(linesAtFill.isNotEmpty())

        viewModel.updateAmount("18")
        viewModel.updateMerchant("Dinner Place")
        val linesAfterEdits = QuickAddUiMapper.summaryLines(st.fillSummary, st.homeCurrency)

        // Rotation recreates the UI but keeps the ViewModel: a fresh read of the retained state (no Sheet-side
        // holder involved) renders the same summary as before the rotation.
        val linesAfterRotation = QuickAddUiMapper.summaryLines(viewModel.state.value.fillSummary, st.homeCurrency)
        assertEquals(linesAtFill, linesAfterEdits)
        assertEquals(linesAfterEdits, linesAfterRotation)
    }

    @Test
    fun `origin transitions - every user gesture marks USER even when the value is unchanged`() {
        beginFill()

        viewModel.updateDate(st.date)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.updateMerchant("m")
        viewModel.updateAmount("1")
        viewModel.selectCategory(1L)

        assertTrue(QuickAddField.values().all { st.fieldOrigins[it] == FieldOrigin.USER })
    }

    @Test
    fun `a foreign hint is shown even when the amount is a USER edit and the amount stays untouched`() {
        beginFill()
        viewModel.updateAmount("18")

        fillAndSettle("dinner 20 usd")

        assertEquals("18", st.amount)
        assertNull(st.fieldFlags[QuickAddField.AMOUNT])
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), st.lineHints)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
    }

    @Test
    fun `NOTHING_RECOGNISED comes from the parser and a too-long line yields FILL_NONE with LINE_TOO_LONG`() {
        beginFill()

        fillAndSettle("!!! ;")
        assertEquals(OutcomeCode.FILL_NONE, st.lastFillOutcome)
        assertEquals(setOf(LineHint.NOTHING_RECOGNISED), st.lineHints)

        fillAndSettle("a".repeat(201))
        assertEquals(OutcomeCode.FILL_NONE, st.lastFillOutcome)
        assertEquals(setOf(LineHint.LINE_TOO_LONG), st.lineHints)
    }

    @Test
    fun `with an empty picker the lookup finds nothing to attach and the category stays null`() {
        beginFill(pickerCategories = emptyList())

        fillAndSettle("groceries 5")

        assertNull(st.selectedCategoryId)
        assertEquals("groceries", st.merchant)
        assertEquals("5.00", st.amount)
    }

    // =====================================================================================
    // MEC slice B1: per-currency precision/cap on save (INV-12..14), no rounding, entry zone
    // captured at sheet open (AE-34), typed refusal mapping (AE-14, AE-19, S4).
    // =====================================================================================

    private val zoneSlot = io.mockk.slot<ZoneId>()

    private fun setDeviceZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    /** Replaces the VM with one whose home currency is [code] and waits for the load. */
    private fun useHome(code: String) {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf(code)
        viewModel = createViewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(code, viewModel.state.value.homeCurrency)
    }

    private fun stubAddManualExpenseCapturingZone() {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                entryZone = capture(zoneSlot)
            )
        } returns Result.Success(1L)
    }

    /** Fresh form (reset), optionally opens the sheet (captures the zone), types and saves. */
    private fun typeAndSave(typed: String, openSheet: Boolean = false) {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        if (openSheet) viewModel.onSheetOpened()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount(typed)
        testDispatcher.scheduler.advanceUntilIdle()
        loggedLines.clear()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun noDecimalsText(code: String) = UiText.StringResource(R.string.mec_amount_no_decimals, listOf(code))

    @Test
    fun `B1 save passes the parsed amount unrounded with the session entry zone`() {
        useUs()
        setDeviceZone("Pacific/Auckland")
        stubAddManualExpenseCapturingZone()

        typeAndSave("19.99", openSheet = true)

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(19.99)
        assertEquals(ZoneId.of("Pacific/Auckland"), zoneSlot.captured)
    }

    // ---- AE-15 / INV-12 / INV-13: whole-unit currencies ----

    @Test
    fun `B1 AE-15 JPY home 1500,50 shows the no-decimals error keeps the text and saves nothing`() {
        useGreek()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("1500,50")

        val state = viewModel.state.value
        assertEquals(noDecimalsText("JPY"), state.amountError)
        assertEquals("1500,50", state.amount)
        assertNull(state.saveResult)
        assertFalse(state.isSaving)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `B1 AE-15 JPY home 1500 saves 1500`() {
        useUs()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("1500")

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertNull(viewModel.state.value.amountError)
        verifySavedAmount(1500.0)
    }

    @Test
    fun `B1 AE-15 JPY home 1500_00 trailing zeros count as whole and save 1500`() {
        useUs()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("1500.00")

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(1500.0)
    }

    @Test
    fun `B1 JPY home 12_50 is rejected inline`() {
        useUs()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("12.50")

        assertEquals(noDecimalsText("JPY"), viewModel.state.value.amountError)
        assertEquals("12.50", viewModel.state.value.amount)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `B1 EUR home with two decimals is not a precision error`() {
        useUs()
        stubAddManualExpense()

        typeAndSave("12.50")

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertNull(viewModel.state.value.amountError)
        verifySavedAmount(12.50)
    }

    @Test
    fun `B1 G2-9 en_US typed 12,345 is truncated to 12,34 and saved exactly`() {
        useUs()
        stubAddManualExpense()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.345")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("12.34", viewModel.state.value.amount)

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(12.34)
    }

    // ---- INV-14: per-currency cap ----

    @Test
    fun `B1 KRW home 2,000,000 passes the per-currency cap`() {
        useUs()
        useHome("KRW")
        stubAddManualExpense()

        typeAndSave("2000000")

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertNull(viewModel.state.value.amountError)
        verifySavedAmount(2_000_000.0)
    }

    @Test
    fun `B1 KRW home above one billion is too large with the formatted cap`() {
        useUs()
        useHome("KRW")
        stubAddManualExpense()

        typeAndSave("1000000001")

        assertEquals(
            UiText.StringResource(R.string.mec_amount_too_large, listOf("1,000,000,000")),
            viewModel.state.value.amountError
        )
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `B1 JPY home above 100,000,000 is too large and exactly the cap is accepted`() {
        useUs()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("100000001")
        assertEquals(
            UiText.StringResource(R.string.mec_amount_too_large, listOf("100,000,000")),
            viewModel.state.value.amountError
        )
        verifyNoManualExpenseSaved()

        typeAndSave("100000000")
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(100_000_000.0)
    }

    @Test
    fun `B1 EUR home cap boundary - exactly 1,000,000 saves and one cent above is too large`() {
        useUs()
        stubAddManualExpense()

        typeAndSave("1000000.01")
        assertEquals(
            UiText.StringResource(R.string.mec_amount_too_large, listOf("1,000,000")),
            viewModel.state.value.amountError
        )
        verifyNoManualExpenseSaved()

        typeAndSave("1000000.00")
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        verifySavedAmount(1_000_000.0)
    }

    // ---- AE-34: entry zone captured at sheet open ----

    @Test
    fun `B1 AE-34 the zone captured at sheet open survives a device zone change`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()

        setDeviceZone("America/Los_Angeles")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertEquals(ZoneId.of("Pacific/Auckland"), zoneSlot.captured)
    }

    @Test
    fun `B1 AE-34 without a sheet open the save falls back to the device zone at save time`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Asia/Tokyo")

        typeAndSave("10.00", openSheet = false)

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertEquals(ZoneId.of("Asia/Tokyo"), zoneSlot.captured)
    }

    @Test
    fun `B1 reset then reopen captures the new zone`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()

        setDeviceZone("Asia/Tokyo")
        viewModel.reset()
        viewModel.onSheetOpened()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()
        setDeviceZone("America/Los_Angeles")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ZoneId.of("Asia/Tokyo"), zoneSlot.captured)
    }

    @Test
    fun `B1 reset clears the captured zone so a stale zone is never reused`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()

        setDeviceZone("America/Los_Angeles")
        typeAndSave("10.00", openSheet = false) // typeAndSave resets first

        assertEquals(ZoneId.of("America/Los_Angeles"), zoneSlot.captured)
    }

    @Test
    fun `B1 F1 a second onSheetOpened in the same session keeps the first zone`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()

        setDeviceZone("Asia/Tokyo")
        viewModel.onSheetOpened() // e.g. recomposition or rotation re-runs the effect
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertEquals(ZoneId.of("Pacific/Auckland"), zoneSlot.captured)
    }

    @Test
    fun `B1 F1 onSheetLeft then onSheetOpened captures the new zone`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()

        viewModel.onSheetLeft()
        setDeviceZone("Asia/Tokyo")
        viewModel.onSheetOpened()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertEquals(ZoneId.of("Asia/Tokyo"), zoneSlot.captured)
    }

    @Test
    fun `B1 F1 onSheetLeft without reopen falls back to the device zone at save`() {
        useUs()
        stubAddManualExpenseCapturingZone()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()

        viewModel.onSheetLeft()
        setDeviceZone("Asia/Tokyo")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
        assertEquals(ZoneId.of("Asia/Tokyo"), zoneSlot.captured)
    }

    // ---- AE-14 / S4 / AE-19: bounded repository codes map to localised resources ----

    @Test
    fun `B1 AE-14 the home-unresolved refusal maps to mec_block_home_unresolved`() {
        stubAddManualExpenseReturns(
            Result.Error(message = com.yourname.expensetracker.domain.transaction.RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED)
        )

        fillValidFormAndSave()

        val expected = UiText.StringResource(R.string.mec_block_home_unresolved)
        val state = viewModel.state.value
        assertEquals(SaveResult.Error(expected), state.saveResult)
        assertEquals(expected, state.mutation.error)
        assertFalse(state.isSaving)
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `B1 S4 the recurring foreign-currency code maps to mec_repeat_home_only with the home code`() {
        useUs()
        useHome("JPY")
        stubAddManualExpenseReturns(
            Result.Error(message = ManualExpenseRepository.RECURRING_FOREIGN_CURRENCY_ERROR_CODE)
        )

        typeAndSave("1500")

        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_repeat_home_only, listOf("JPY"))),
            viewModel.state.value.saveResult
        )
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `B1 AE-19 the coordinator AMOUNT_PRECISION_EXCEEDED maps to the no-decimals resource for a whole-unit home`() {
        useUs()
        useHome("JPY")
        stubAddManualExpenseReturns(
            Result.Error(message = com.yourname.expensetracker.domain.transaction.RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED)
        )

        typeAndSave("1500")

        assertEquals(SaveResult.Error(noDecimalsText("JPY")), viewModel.state.value.saveResult)
        assertEquals(noDecimalsText("JPY"), viewModel.state.value.mutation.error)
    }

    @Test
    fun `B1 AE-19 the coordinator AMOUNT_PRECISION_EXCEEDED maps to the max-decimals resource for a two-digit home`() {
        stubAddManualExpenseReturns(
            Result.Error(message = com.yourname.expensetracker.domain.transaction.RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED)
        )

        fillValidFormAndSave()

        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_amount_max_decimals, listOf("EUR", 2))),
            viewModel.state.value.saveResult
        )
    }

    @Test
    fun `B1 AE-19 AMOUNT_PRECISION_EXCEEDED with unavailable digits falls back to save-failed`() {
        useUs()
        useHome("12") // not a parsable currency code: digits unavailable
        stubAddManualExpenseReturns(
            Result.Error(message = com.yourname.expensetracker.domain.transaction.RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED)
        )

        typeAndSave("10.00")

        assertEquals(SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
    }

    @Test
    fun `B1 any other repository code or text maps to save-failed and is never shown`() {
        // MEC B2a rewrite in place: AMOUNT_DIGITS_UNAVAILABLE and RATE_NOT_ELIGIBLE_STALE are now mapped
        // codes (see the "B2a refusal codes" tests below), so they left this "any other code" list.
        for (message in listOf(
            "first problem; second problem Pacific/Auckland",
            "SOME_UNMAPPED_CODE"
        )) {
            stubAddManualExpenseReturns(Result.Error(message = message))

            fillValidFormAndSave()

            assertEquals(message, SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
            assertFalse(message, viewModel.state.value.isSaving)
        }
    }

    // ---- AE-15 through quick-add Fill ----

    @Test
    fun `B1 Fill with a JPY home and a fractional amount is rejected at save`() {
        beginFill()
        useHome("JPY")
        categoriesJob?.cancel()
        categoriesJob = CoroutineScope(testDispatcher).launch { viewModel.categories.collect { } }
        stubAddManualExpense()

        fillAndSettle("coffee 3,50")
        assertEquals("3.50", st.amount)
        loggedLines.clear()

        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(noDecimalsText("JPY"), st.amountError)
        assertEquals("3.50", st.amount)
        assertNull(st.saveResult)
        verifyNoManualExpenseSaved()
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `B1 Fill with a JPY home and a whole amount saves in the home currency`() {
        beginFill()
        useHome("JPY")
        categoriesJob?.cancel()
        categoriesJob = CoroutineScope(testDispatcher).launch { viewModel.categories.collect { } }
        stubAddManualExpense()

        fillAndSettle("coffee 1500")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SaveResult.Success, st.saveResult)
        verifySavedAmount(1500.0)
    }

    // ---- INV-13 / AE-33: precision and cap errors, zone and amount never logged ----

    @Test
    fun `B1 precision and cap errors log nothing`() {
        useUs()
        useHome("JPY")
        stubAddManualExpense()

        typeAndSave("12.50")
        assertEquals(noDecimalsText("JPY"), viewModel.state.value.amountError)
        assertTrue(loggedLines.isEmpty())

        typeAndSave("100000001")
        assertTrue(viewModel.state.value.amountError is UiText.StringResource)
        assertTrue(loggedLines.isEmpty())

        useHome("EUR")
        typeAndSave("2000000.00")
        assertEquals(
            UiText.StringResource(R.string.mec_amount_too_large, listOf("1,000,000")),
            viewModel.state.value.amountError
        )
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `AE-53 AE-33 a failing save with a captured zone logs only the bounded code and no zone or amount`() {
        useUs()
        setDeviceZone("Pacific/Auckland")
        stubAddManualExpenseThrows(IllegalStateException("zone Pacific/Auckland amount 50.00"))

        typeAndSave("50.00", openSheet = true)

        assertEquals(SaveResult.Error(saveFailedText), viewModel.state.value.saveResult)
        assertEquals(listOf("ADD_EXPENSE_SAVE_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
        assertTrue(loggedLines.none { it.message.contains("Auckland") || it.message.contains("50") })
    }

    @Test
    fun `AE-33 G-1 the captured zone is never part of the observable state`() {
        useUs()
        setDeviceZone("Pacific/Auckland")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        viewModel.onSheetOpened()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")

        val rendered = viewModel.state.value.toString()
        assertFalse(rendered.contains("Auckland"))
        for (field in AddExpenseState::class.java.declaredFields) {
            assertFalse(
                "state field ${field.name} must not hold a zone",
                ZoneId::class.java.isAssignableFrom(field.type) || TimeZone::class.java.isAssignableFrom(field.type)
            )
        }
    }

    // =====================================================================================
    // MEC slice B2a: currency selection, rate preview region, refresh, open-setting request.
    // Oracle: domain-spec AEs 1-4, 6, 7, 11, 15-17, 22, 26, 27, 30, 31, 38 and the plan amendment
    // "B2 re-anchor and split". The preview use case is the REAL one over an in-memory rate store.
    // Synthetic rates only. Default context: home EUR, Europe/Athens, now Thu 2025-10-16 14:30.
    // =====================================================================================

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    /** Virtual time: runs everything due up to and including [ms] from now. */
    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun runNow() = testDispatcher.scheduler.runCurrent()

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun putRate(from: String, to: String, rate: Double, date: LocalDate, source: String = "ecb") {
        rateStore.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 0L,
            source = source,
            validDate = utcMidnight(date)
        )
    }

    private val region: CurrencyRegionUi get() = st.currencyRegion

    private val ratesOn = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))

    /** A fresh VM for [home], sheet opened in [zone] at [now]; the rates setting defaults to Loaded(OFF). */
    private fun openMec(
        home: String = "EUR",
        zone: ZoneId = athens,
        now: Long = at(zone, 2025, 10, 16, 14, 30),
        privacy: PrivacySettingsLoadState = PrivacySettingsLoadState.Loaded(PrivacySettings())
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        useUs()
        every { timeProvider.now() } returns now
        privacyFlow.value = privacy
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf(home)
        viewModel = createViewModel(repo)
        settle()
        viewModel.onSheetOpened()
        settle()
        loggedLines.clear()
    }

    /** USD selected, 20.00 entered, dated Wed 15 Oct 12:00 Athens, no stored rate: a blocked preview. */
    private fun openBlockedUsd(privacy: PrivacySettingsLoadState) {
        openMec(privacy = privacy)
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
    }

    private fun eligibleUsd(
        rateDate: LocalDate,
        formatted: String?,
        source: RateSourceLabel = RateSourceLabel.ECB
    ) = CurrencyRegionUi.Eligible(rateDate, source, formatted, amountUnavailable = false)

    // ---- defaults: Selected(home) on every open, options from the selection policy ----

    @Test
    fun `B2a a fresh open selects the home currency and shows no preview`() {
        openMec()

        assertEquals("EUR", st.selectedCurrency)
        assertFalse(st.isForeignSelected)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertFalse(st.repeatClearedByCurrency)
        assertTrue(rateStore.asOfCalls.isEmpty())
        assertTrue(ratesRepo.calls.isEmpty())
    }

    @Test
    fun `F-B2-A11Y-1 previewAnnounceKey increments on a currency and a date change and never on an amount keystroke`() {
        openMec()
        val opened = st.previewAnnounceKey

        viewModel.updateAmount("12")
        settle()
        viewModel.updateAmount("12.5")
        settle()
        assertEquals("keystrokes never bump the key", opened, st.previewAnnounceKey)

        viewModel.selectCurrency("USD")
        settle()
        assertEquals(opened + 1, st.previewAnnounceKey)

        viewModel.updateAmount("123")
        settle()
        assertEquals("a keystroke after the change does not bump it", opened + 1, st.previewAnnounceKey)

        viewModel.updateDate(at(athens, 2025, 10, 15))
        settle()
        assertEquals(opened + 2, st.previewAnnounceKey)

        viewModel.selectCurrency("EUR")
        settle()
        assertEquals(opened + 3, st.previewAnnounceKey)
    }

    @Test
    fun `F-B2-A11Y-1 previewAnnounceKey increments when the home currency changes`() {
        val homeFlow = MutableStateFlow<String>("EUR")
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns homeFlow
        viewModel = createViewModel(repo)
        settle()
        val loaded = st.previewAnnounceKey

        homeFlow.value = "USD"
        settle()

        assertEquals(loaded + 1, st.previewAnnounceKey)
    }

    @Test
    fun `F-B2-A11Y-1 an ignored currency pick does not bump previewAnnounceKey`() {
        openMec()
        val opened = st.previewAnnounceKey

        viewModel.selectCurrency("not-a-currency")
        settle()

        assertEquals(opened, st.previewAnnounceKey)
    }

    @Test
    fun `B2a while the home currency is loading nothing is selected and a pick is ignored`() {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns emptyFlow()
        viewModel = createViewModel(repo)
        settle()

        assertNull(st.homeCurrency)
        assertNull(st.selectedCurrency)
        assertTrue(st.currencyOptions.isEmpty())
        assertEquals(CurrencyRegionUi.Loading, region)

        viewModel.selectCurrency("USD")
        settle()

        assertNull(st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Loading, region)
    }

    @Test
    fun `B2a AE-27 options for a EUR home exclude BGN RUB and HRK and list home first then codes`() {
        openMec()

        val options = st.currencyOptions
        assertEquals("EUR", options.first())
        assertTrue(options.containsAll(listOf("USD", "GBP", "JPY")))
        assertFalse("BGN" in options)
        assertFalse("RUB" in options)
        assertFalse("HRK" in options)
        assertEquals(options.size, options.distinct().size)
        assertEquals(options.drop(1).sorted(), options.drop(1))
    }

    @Test
    fun `B2a AE-27 a BGN home keeps BGN as home option and a EUR pick is foreign`() {
        openMec(home = "BGN")

        assertEquals("BGN", st.currencyOptions.first())
        assertFalse("RUB" in st.currencyOptions)
        assertEquals("BGN", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)

        viewModel.selectCurrency("EUR")
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertTrue(st.isForeignSelected)
    }

    @Test
    fun `B2a AE-27 a non-selectable or unknown pick is ignored and the selection stays`() {
        openMec()

        viewModel.selectCurrency("BGN")
        viewModel.selectCurrency("RUB")
        viewModel.selectCurrency("ZZZ")
        viewModel.selectCurrency("")
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertTrue(rateStore.asOfCalls.isEmpty())
    }

    // ---- AE-1 / AE-38 ----

    @Test
    fun `B2a AE-1 USD 20_00 dated 15 Oct previews the ECB rate of 15 Oct and saves in USD`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(15))
        openMec()
        val dateMs = at(athens, 2025, 10, 15)

        viewModel.updateDate(dateMs)
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals("USD", st.selectedCurrency)
        assertTrue(st.isForeignSelected)
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(18.52, "EUR")), region)
        assertFalse(st.isCurrencySaveBlocked)
        // The lookup is bounded by the expense's local date at UTC midnight.
        assertEquals(utcMidnight(oct(15)), rateStore.asOfCalls.last().third)

        stubAddManualExpenseReturns(Result.Success(1L))
        viewModel.updateMerchant("Cafe X")
        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        verifyOneSavedExpense("Cafe X", 20.0, "USD", null, PaymentMethod.CASH, dateMs)
        assertTrue("no fetch at preview or save", ratesRepo.calls.isEmpty())
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `B2a AE-38 the preview text is formatMoney of the snapshot amount in the home currency`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        // 18.52 is the spec's stored baseAmount for AE-1.
        assertEquals(CurrencyFormatter.formatMoney(18.52, "EUR"), (region as CurrencyRegionUi.Eligible).formattedAmountInHome)
    }

    @Test
    fun `B2a AE-38 a tie rounds half even in a whole-unit home and the text matches that snapshot`() {
        putRate("USD", "JPY", 150.0, oct(15))
        openMec(home = "JPY")
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("10.75")
        settle()

        // 10.75 * 150 = 1612.5: HALF_EVEN gives 1612 (HALF_UP would show 1613).
        assertEquals(CurrencyFormatter.formatMoney(1612.0, "JPY"), (region as CurrencyRegionUi.Eligible).formattedAmountInHome)
    }

    @Test
    fun `B2a an eligible rate with no amount entered shows the date and no amount, never zero`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        settle()

        assertEquals(eligibleUsd(oct(15), null), region)
        assertFalse((region as CurrencyRegionUi.Eligible).amountUnavailable)
    }

    @Test
    fun `B2a a non-ECB source is labelled OTHER`() {
        putRate("USD", "EUR", 0.9, oct(15), source = "manual")
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("10.00")
        settle()

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(9.0, "EUR"), RateSourceLabel.OTHER), region)
    }

    // ---- AE-2 / AE-3 / AE-4 ----

    @Test
    fun `B2a AE-2 a Sunday entry uses the Friday rate`() {
        putRate("USD", "EUR", 0.9, oct(10))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 12))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals(eligibleUsd(oct(10), CurrencyFormatter.formatMoney(18.0, "EUR")), region)
    }

    @Test
    fun `B2a AE-3 an empty rate table blocks with NONE, disables Save and fetches nothing`() {
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        assertTrue(st.isCurrencySaveBlocked)
        assertTrue(ratesRepo.calls.isEmpty())
        assertTrue(rateStore.rows.isEmpty())
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `B2a AE-4 eight days old is STALE with the latest date and a later rate fixes only a later date`() {
        putRate("USD", "EUR", 0.9, oct(7))
        openMec()
        val fifteenth = at(athens, 2025, 10, 15)
        viewModel.updateDate(fifteenth)
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, oct(7), CurrencyBlockOrigin.PREVIEW), region)
        assertTrue(st.isCurrencySaveBlocked)

        putRate("USD", "EUR", 0.9, oct(16))
        viewModel.updateDate(fifteenth)
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, oct(7), CurrencyBlockOrigin.PREVIEW), region)

        viewModel.updateDate(at(athens, 2025, 10, 16, 9, 0))
        settle()
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(18.0, "EUR")), region)
    }

    @Test
    fun `B2a AE-4 boundary seven days is eligible and eight days is stale`() {
        putRate("USD", "EUR", 0.9, oct(8))
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("10.00")

        viewModel.updateDate(at(athens, 2025, 10, 15))
        settle()
        assertEquals(eligibleUsd(oct(8), CurrencyFormatter.formatMoney(9.0, "EUR")), region)

        viewModel.updateDate(at(athens, 2025, 10, 16, 9, 0))
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, oct(8), CurrencyBlockOrigin.PREVIEW), region)
    }

    // ---- AE-6 / AE-7 / AE-11: the entry zone decides the local date ----

    @Test
    fun `B2a AE-6 Athens 01_30 on 16 Oct uses the 16 Oct rate`() {
        putRate("GBP", "EUR", 34.48 / 30.0, oct(16))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 16, 1, 30))
        viewModel.selectCurrency("GBP")
        viewModel.updateAmount("30.00")
        settle()

        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(34.48, "EUR")), region)
        assertEquals(utcMidnight(oct(16)), rateStore.asOfCalls.last().third)
    }

    @Test
    fun `B2a AE-7 Athens 23_30 on 15 Oct has no rate when only 16 Oct is stored`() {
        putRate("GBP", "EUR", 34.48 / 30.0, oct(16))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15, 23, 30))
        viewModel.selectCurrency("GBP")
        viewModel.updateAmount("30.00")
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        assertEquals(utcMidnight(oct(15)), rateStore.asOfCalls.last().third)
    }

    @Test
    fun `B2a AE-11 New York 21_00 on 15 Oct uses the 15 Oct rate and today is the device-local date`() {
        val ny = ZoneId.of("America/New_York")
        putRate("USD", "EUR", 0.9, oct(15))
        putRate("USD", "EUR", 0.8, oct(16))
        // 2025-10-15 21:30 in New York is already 16 Oct in UTC.
        openMec(zone = ny, now = at(ny, 2025, 10, 15, 21, 30))
        viewModel.updateDate(at(ny, 2025, 10, 15, 21, 0))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("10.00")
        settle()

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(9.0, "EUR")), region)
        assertEquals(oct(15), st.ratesToday)
        assertEquals(utcMidnight(oct(15)), rateStore.asOfCalls.last().third)
    }

    // ---- INV-3 timing: Pending, Evaluating at 250 ms, latest wins ----

    @Test
    fun `B2a a slow evaluation is Pending for 250 ms then Evaluating then Eligible`() {
        putRate("USD", "EUR", 0.9, oct(15))
        rateStore.latencyMs = { _, _, _ -> 1_000L }
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")

        assertEquals(CurrencyRegionUi.Pending, region)
        runNow()
        assertEquals(CurrencyRegionUi.Pending, region)
        advance(249)
        assertEquals(CurrencyRegionUi.Pending, region)
        advance(1)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        assertFalse("Save stays enabled while Checking", st.isCurrencySaveBlocked)
        advance(749)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        advance(1)
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(18.0, "EUR")), region)
    }

    @Test
    fun `B2a a fast evaluation never shows Evaluating`() {
        putRate("USD", "EUR", 0.9, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        runNow()

        assertTrue(region is CurrencyRegionUi.Eligible)
        advance(1_000)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `B2a the latest input wins and a superseded slow result is never applied`() {
        putRate("USD", "EUR", 0.5, oct(15))
        putRate("USD", "EUR", 0.25, oct(16))
        val slowBound = utcMidnight(oct(15))
        rateStore.latencyMs = { _, _, atMillis -> if (atMillis == slowBound) 1_000L else 0L }
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(5.0, "EUR")), region)

        viewModel.updateDate(at(athens, 2025, 10, 15)) // slow
        runNow()
        advance(100)
        viewModel.updateDate(at(athens, 2025, 10, 16, 9, 0)) // fast, supersedes
        runNow()
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(5.0, "EUR")), region)

        advance(5_000)
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(5.0, "EUR")), region)
    }

    @Test
    fun `B2a a currency or date change drops the shown result to Pending, never a stale Eligible`() {
        putRate("USD", "EUR", 0.5, oct(15))
        putRate("GBP", "EUR", 2.0, oct(15))
        rateStore.latencyMs = { from, _, _ -> if (from == "GBP") 1_000L else 0L }
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(10.0, "EUR")), region)

        viewModel.selectCurrency("GBP")
        assertEquals(CurrencyRegionUi.Pending, region)
        runNow()
        advance(250)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        advance(750)
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(40.0, "EUR")), region)

        viewModel.updateDate(at(athens, 2025, 10, 14))
        assertEquals(CurrencyRegionUi.Pending, region)
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
    }

    @Test
    fun `B2a an amount edit drops the shown result to Pending, never the old amount's Eligible`() {
        putRate("USD", "EUR", 0.5, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(10.0, "EUR")), region)

        viewModel.updateAmount("40.00")
        assertEquals(CurrencyRegionUi.Pending, region)
        settle()

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(20.0, "EUR")), region)
    }

    @Test
    fun `B2a typing an amount re-evaluates silently without an Evaluating flash for a fast store`() {
        putRate("USD", "EUR", 0.5, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        viewModel.updateAmount("40.00")
        settle()

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(20.0, "EUR")), region)
    }

    @Test
    fun `B2a a home currency change re-evaluates and equal home and selection is Home`() {
        putRate("USD", "EUR", 0.9, oct(15))
        putRate("USD", "GBP", 0.8, oct(15))
        val homeFlow = MutableStateFlow("EUR")
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns homeFlow
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        useUs()
        every { timeProvider.now() } returns at(athens, 2025, 10, 16, 14, 30)
        viewModel = createViewModel(repo)
        settle()
        viewModel.onSheetOpened()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("10.00")
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(9.0, "EUR")), region)

        homeFlow.value = "GBP"
        settle()
        assertEquals("USD", st.selectedCurrency)
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(8.0, "GBP")), region)

        homeFlow.value = "USD"
        settle()
        assertEquals("USD", st.selectedCurrency)
        assertFalse(st.isForeignSelected)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    // ---- preview failures: bounded code only; cancellation is never swallowed ----

    @Test
    fun `B2a AE-30 an unreadable rate store fails closed and logs only the bounded code`() {
        rateStore.failure = IllegalStateException("USD 20.00 rate 1.08 Europe/Athens")
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        assertTrue(st.isCurrencySaveBlocked)
        assertEquals(listOf("RATE_PREVIEW_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
    }

    @Test
    fun `B2a a CancellationException from the preview is rethrown, not mapped to a blocked card`() {
        rateStore.failure = CancellationException("cancelled")
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        assertEquals(CurrencyRegionUi.Pending, region)
        assertTrue(loggedLines.isEmpty())
    }

    // ---- AE-15 / AE-16 / AE-17: per-currency amount rules through the region ----

    @Test
    fun `B2a AE-15 JPY 1500 previews and 1500,50 is invalid keeping the text`() {
        putRate("JPY", "EUR", 1.0 / 162.5, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("JPY")
        viewModel.updateAmount("1500")
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(9.23, "EUR")), region)

        useGreek()
        viewModel.updateAmount("1500,50")
        settle()

        assertEquals("1500,50", st.amount)
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertTrue(st.isCurrencySaveBlocked)
    }

    @Test
    fun `B2a AE-15 an invalid foreign amount never reads the rate store`() {
        putRate("JPY", "EUR", 1.0 / 162.5, oct(15))
        openMec()
        viewModel.selectCurrency("JPY")
        viewModel.updateAmount("1500.50")
        settle()

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertTrue(rateStore.asOfCalls.isEmpty())
    }

    @Test
    fun `B2a the per-currency cap shows AmountInvalid ABOVE_CAP`() {
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("2000000.00")
        settle()

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP), region)
        assertTrue(st.isCurrencySaveBlocked)
    }

    @Test
    fun `B2a AE-16 switching EUR 12_50 to JPY marks it invalid without truncating and EUR makes it valid again`() {
        openMec()
        viewModel.updateAmount("12.50")
        settle()
        assertNull(st.amountError)

        viewModel.selectCurrency("JPY")
        settle()

        assertEquals("12.50", st.amount)
        assertEquals(noDecimalsText("JPY"), st.amountError)
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertTrue(st.isCurrencySaveBlocked)

        viewModel.selectCurrency("EUR")
        settle()

        assertEquals("12.50", st.amount)
        assertNull(st.amountError)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `B2a AE-16 save with a JPY selection and 12_50 stores nothing and reports the no-decimals error`() {
        openMec()
        stubAddManualExpense()
        viewModel.selectCurrency("JPY")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        settle()

        viewModel.save()
        settle()

        assertEquals(noDecimalsText("JPY"), st.amountError)
        assertEquals("12.50", st.amount)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `B2a AE-17 JPY 1,500 is 1500 and valid while 1_500 is 1_5 and a precision error`() {
        putRate("JPY", "EUR", 1.0 / 162.5, oct(16))
        openMec()
        stubAddManualExpense()
        viewModel.selectCurrency("JPY")
        viewModel.updateMerchant("Shop")

        viewModel.updateAmount("1,500")
        settle()
        assertFalse(region is CurrencyRegionUi.AmountInvalid)
        viewModel.save()
        settle()
        verifySavedAmount(1500.0)

        viewModel.updateAmount("1.500")
        settle()
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        viewModel.save()
        settle()
        assertEquals(noDecimalsText("JPY"), st.amountError)
        verifySavedAmount(1500.0, times = 1)
    }

    // ---- AE-26: Repeat is home-currency only ----

    @Test
    fun `B2a AE-26 choosing a foreign currency turns Repeat off with a note and Repeat stays off`() {
        openMec()
        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertTrue(st.isRecurring)

        viewModel.selectCurrency("USD")
        settle()

        assertFalse(st.isRecurring)
        assertEquals(RecurrenceFrequency.MONTHLY, st.recurrenceFrequency)
        assertTrue(st.repeatClearedByCurrency)

        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertFalse("toggle is ignored while foreign", st.isRecurring)
        assertEquals(RecurrenceFrequency.MONTHLY, st.recurrenceFrequency)
    }

    @Test
    fun `B2a AE-26 no note when Repeat was off and Repeat works again after returning home`() {
        openMec()
        viewModel.selectCurrency("USD")
        settle()
        assertFalse(st.repeatClearedByCurrency)

        viewModel.selectCurrency("EUR")
        settle()
        viewModel.toggleRecurring()
        assertTrue(st.isRecurring)
    }

    @Test
    fun `B2a AE-26 a foreign save passes no recurrence and an EUR save keeps the monthly rule`() {
        putRate("USD", "EUR", 0.9, oct(16))
        openMec()
        stubAddManualExpenseReturns(Result.Success(1L))
        viewModel.toggleRecurring()
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        settle()

        viewModel.save()
        settle()
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = any(), amount = any(), currency = eq("USD"), categoryId = any(),
                transactionType = any(), paymentMethod = any(), date = any(), notes = any(),
                transferDirection = any(), transferAccountName = any(), isNotMine = any(),
                ownerName = any(), isSharedExpense = any(), sharedWithName = any(),
                mySharePercentage = any(), myShareAmount = any(), latitude = any(),
                longitude = any(), locationSource = any(), recurrenceFrequency = isNull(),
                recurringNote = any(), entryZone = any()
            )
        }

        viewModel.selectCurrency("EUR")
        viewModel.toggleRecurring()
        viewModel.clearSaveResult()
        viewModel.save()
        settle()
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = any(), amount = any(), currency = eq("EUR"), categoryId = any(),
                transactionType = any(), paymentMethod = any(), date = any(), notes = any(),
                transferDirection = any(), transferAccountName = any(), isNotMine = any(),
                ownerName = any(), isSharedExpense = any(), sharedWithName = any(),
                mySharePercentage = any(), myShareAmount = any(), latitude = any(),
                longitude = any(), locationSource = any(),
                recurrenceFrequency = eq(RecurrenceFrequency.MONTHLY),
                recurringNote = any(), entryZone = any()
            )
        }
    }

    @Test
    fun `B2a P1 the recurring-foreign code names the HOME currency while USD is selected`() {
        putRate("USD", "EUR", 0.9, oct(16))
        openMec()
        stubAddManualExpenseReturns(Result.Error(message = ManualExpenseRepository.RECURRING_FOREIGN_CURRENCY_ERROR_CODE))
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("10.00")
        settle()

        viewModel.save()
        settle()

        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_repeat_home_only, listOf("EUR"))),
            st.saveResult
        )
    }

    // ---- AE-22 / refusal codes after the tap ----

    private fun openEligibleUsdForm() {
        putRate("USD", "EUR", 0.9, oct(15))
        openMec()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(18.0, "EUR")), region)
    }

    private fun assertFormKept() {
        assertEquals("Shop", st.merchant)
        assertEquals("20.00", st.amount)
        assertEquals("USD", st.selectedCurrency)
        assertFalse(st.isSaving)
        assertNull(st.mutation.error)
    }

    @Test
    fun `B2a AE-3 AE-22 the rate vanishing before Save shows the NONE card from SAVE and keeps the form`() {
        openEligibleUsdForm()
        rateStore.rows.clear()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_NONE))

        viewModel.save()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), region)
        assertNull(st.saveResult)
        assertTrue(st.isCurrencySaveBlocked)
        assertFormKept()
        assertTrue(ratesRepo.calls.isEmpty())
    }

    @Test
    fun `B2a AE-22 a STALE refusal re-reads the latest rate date locally with origin SAVE`() {
        openEligibleUsdForm()
        rateStore.rows.clear()
        putRate("USD", "EUR", 0.9, oct(7))
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_STALE))

        viewModel.save()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, oct(7), CurrencyBlockOrigin.SAVE), region)
        assertNull(st.saveResult)
        assertFormKept()
        assertTrue(ratesRepo.calls.isEmpty())
    }

    @Test
    fun `B2a AE-22 a STALE refusal keeps the SAVE card when the local re-read disagrees and the next input clears it`() {
        // The coordinator refused as stale but the local preview still sees an eligible rate.
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_STALE))

        viewModel.save()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, null, CurrencyBlockOrigin.SAVE), region)
        assertTrue(st.isCurrencySaveBlocked)
        assertNull(st.saveResult)
        assertFormKept()

        // R-4 (rewritten in place): the amount-independent SAVE card is kept through the edit (no Pending
        // flicker, Save stays blocked); the re-read then clears it.
        viewModel.updateAmount("30.00")
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, null, CurrencyBlockOrigin.SAVE), region)
        assertTrue(st.isCurrencySaveBlocked)
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(27.0, "EUR")), region)
    }

    @Test
    fun `B2a AE-22 an UNSUPPORTED refusal shows the card from SAVE`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_UNSUPPORTED))

        viewModel.save()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, CurrencyBlockOrigin.SAVE), region)
        assertNull(st.saveResult)
        assertFormKept()
    }

    @Test
    fun `B2a AE-14 a HOME_UNRESOLVED refusal shows the card, keeps the error text and stays retryable`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED))

        viewModel.save()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.HOME_UNRESOLVED, null, CurrencyBlockOrigin.SAVE), region)
        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_block_home_unresolved)),
            st.saveResult
        )
        assertFalse("Save stays enabled for a retry", st.isCurrencySaveBlocked)
        assertEquals("Shop", st.merchant)
        assertEquals("20.00", st.amount)
        assertFalse(st.isSaving)
    }

    @Test
    fun `B2a AE-19 AMOUNT_DIGITS_UNAVAILABLE maps to the invalid-amount text and AMOUNT_PRECISION_EXCEEDED to the selected currency`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.AMOUNT_DIGITS_UNAVAILABLE))
        viewModel.save()
        settle()
        assertEquals(SaveResult.Error(amountInvalidText), st.saveResult)
        assertFalse(st.isSaving)

        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED))
        viewModel.save()
        settle()
        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_amount_max_decimals, listOf("USD", 2))),
            st.saveResult
        )
    }

    @Test
    fun `B2a an unmapped repository text maps to save-failed and is never shown`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = "USD 20.00 constraint failed Europe/Athens"))

        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(saveFailedText), st.saveResult)
        assertEquals(CurrencyRegionUi.Eligible(oct(15), RateSourceLabel.ECB, CurrencyFormatter.formatMoney(18.0, "EUR"), false), region)
    }

    @Test
    fun `B2a AE-31 a duplicate foreign save keeps the existing duplicate handling`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Duplicate)

        viewModel.save()
        settle()

        assertEquals(SaveResult.Duplicate, st.saveResult)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `B2a AE-22 a refresh storing the 15 Oct rate re-evaluates the preview`() {
        putRate("USD", "EUR", 0.9, oct(14))
        ratesRepo.outcome = {
            putRate("USD", "EUR", 0.8, oct(15))
            CurrencyRateRefreshOutcome.Refreshed(1)
        }
        openMec(privacy = ratesOn)
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(eligibleUsd(oct(14), CurrencyFormatter.formatMoney(18.0, "EUR")), region)

        viewModel.refreshRates()
        settle()

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(16.0, "EUR")), region)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals(listOf("EUR"), ratesRepo.calls)
    }

    // ---- rates setting mapping (R23) and no refresh when Off or Unknown ----

    @Test
    fun `B2a the rates setting maps Loaded and FirstRunDefault by flag`() {
        openMec(privacy = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)

        openMec(privacy = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = false)))
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)

        openMec(privacy = PrivacySettingsLoadState.FirstRunDefault(PrivacySettings()))
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)

        openMec(privacy = PrivacySettingsLoadState.FirstRunDefault(PrivacySettings(exchangeRateDownloadEnabled = true)))
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
    }

    @Test
    fun `B2a a corrupted fail-closed setting is Unknown even if its flag says on`() {
        openMec(
            privacy = PrivacySettingsLoadState.CorruptedFailClosed(
                PrivacySettings(exchangeRateDownloadEnabled = true),
                reason = "READ_FAILED"
            )
        )

        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
    }

    @Test
    fun `B2a the setting follows later changes and the VM never writes it`() {
        openMec()
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)

        privacyFlow.value = ratesOn
        settle()
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)

        privacyFlow.value = PrivacySettingsLoadState.CorruptedFailClosed(PrivacySettings(), "X")
        settle()
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        coVerify(exactly = 0) { privacyRepo.updateSettings(any()) }
    }

    @Test
    fun `B2a a setting read error is Unknown and logs only the bounded code`() {
        val failing = mockk<PrivacySettingsRepository>()
        every { failing.observeLoadState() } returns flow { throw IllegalStateException("datastore /data/user/0/x") }
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        viewModel = createViewModel(repo, failing)
        settle()

        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(listOf("RATES_SETTING_READ_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
    }

    @Test
    fun `B2a no refresh is ever started while the setting is Off`() {
        openBlockedUsd(PrivacySettingsLoadState.Loaded(PrivacySettings()))

        viewModel.refreshRates()
        settle()

        assertTrue(ratesRepo.calls.isEmpty())
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
    }

    @Test
    fun `B2a no refresh is ever started while the setting is Unknown`() {
        openBlockedUsd(PrivacySettingsLoadState.CorruptedFailClosed(PrivacySettings(exchangeRateDownloadEnabled = true), "X"))

        viewModel.refreshRates()
        settle()

        assertTrue(ratesRepo.calls.isEmpty())
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
    }

    @Test
    fun `B2a no refresh when On but the home currency is selected or the home is loading`() {
        openMec(privacy = ratesOn)
        viewModel.refreshRates()
        settle()
        assertTrue(ratesRepo.calls.isEmpty())

        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns emptyFlow()
        viewModel = createViewModel(repo)
        settle()
        viewModel.refreshRates()
        settle()
        assertTrue(ratesRepo.calls.isEmpty())
    }

    // ---- refresh outcomes (INV-20) ----

    @Test
    fun `B2a refresh passes the home code, shows RUNNING once and a still-blocked outcome`() {
        ratesRepo.outcome = {
            delay(500)
            CurrencyRateRefreshOutcome.Refreshed(0)
        }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        runNow()
        assertEquals(CurrencyRefreshStatus.RUNNING, st.rateRefresh)
        viewModel.refreshRates() // double tap
        runNow()
        assertEquals(1, ratesRepo.calls.size)

        advance(500)
        runNow()
        settle()

        assertEquals(listOf("EUR"), ratesRepo.calls)
        assertEquals(CurrencyRefreshStatus.DONE_STILL_BLOCKED, st.rateRefresh)
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
    }

    @Test
    fun `B2a a refresh that finds the rate ends Idle with an Eligible region`() {
        ratesRepo.outcome = {
            putRate("USD", "EUR", 0.9, oct(15))
            CurrencyRateRefreshOutcome.Refreshed(1)
        }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        settle()

        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(18.0, "EUR")), region)
    }

    @Test
    fun `B2a a refresh disabled by privacy shows the Off variant without a failure`() {
        ratesRepo.outcome = { CurrencyRateRefreshOutcome.DisabledByPrivacy }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        settle()

        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
    }

    @Test
    fun `B2a a privacy-check failure shows Unknown without a failure`() {
        ratesRepo.outcome = { CurrencyRateRefreshOutcome.PrivacyCheckFailed("PRIVACY_READ_FAILED") }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        settle()

        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
    }

    @Test
    fun `B2a an IOException is FAILED_OFFLINE and logs only the bounded code`() {
        ratesRepo.outcome = { throw IOException("unreachable host usd 20.00") }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        settle()

        assertEquals(CurrencyRefreshStatus.FAILED_OFFLINE, st.rateRefresh)
        assertEquals(listOf("RATE_REFRESH_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
    }

    @Test
    fun `B2a other refresh failures map to bounded statuses by exact token`() {
        openBlockedUsd(ratesOn)

        ratesRepo.outcome = { throw IllegalStateException("HOME_CURRENCY_QUOTE_UNAVAILABLE") }
        viewModel.refreshRates()
        settle()
        assertEquals(CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE, st.rateRefresh)

        ratesRepo.outcome = { throw IllegalStateException("INVALID_HOME_CURRENCY") }
        viewModel.refreshRates()
        settle()
        assertEquals(CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE, st.rateRefresh)

        ratesRepo.outcome = { throw IllegalStateException("HOME_CURRENCY_QUOTE_UNAVAILABLE for USD") }
        viewModel.refreshRates()
        settle()
        assertEquals(CurrencyRefreshStatus.FAILED, st.rateRefresh)
        assertTrue(loggedLines.all { it.message == "RATE_REFRESH_FAILED" && it.throwable == null })
    }

    @Test
    fun `B2a a CancellationException from the refresh is rethrown and records no failure`() {
        ratesRepo.outcome = { throw CancellationException("cancelled") }
        openBlockedUsd(ratesOn)

        viewModel.refreshRates()
        settle()

        assertFalse(
            st.rateRefresh in setOf(
                CurrencyRefreshStatus.FAILED,
                CurrencyRefreshStatus.FAILED_OFFLINE,
                CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE
            )
        )
        assertTrue(loggedLines.isEmpty())
    }

    @Test
    fun `B2a reset cancels an in-flight refresh and the next open starts clean on home`() {
        ratesRepo.outcome = {
            delay(1_000)
            putRate("USD", "EUR", 0.9, oct(15))
            CurrencyRateRefreshOutcome.Refreshed(1)
        }
        openBlockedUsd(ratesOn)
        viewModel.refreshRates()
        runNow()
        assertEquals(CurrencyRefreshStatus.RUNNING, st.rateRefresh)

        viewModel.reset()
        advance(5_000)
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertTrue(rateStore.rows.isEmpty())
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
    }

    // ---- D-11: open the exchange-rate setting ----

    @Test
    fun `B2a open-setting navigates at once when no amount was entered`() {
        openMec()

        viewModel.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)

        viewModel.consumeOpenSettingRequest()
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
    }

    @Test
    fun `B2a open-setting asks to confirm when an amount was entered and navigates only after confirm`() {
        openMec()
        viewModel.updateAmount("12.50")

        viewModel.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.CONFIRM_NEEDED, st.openSettingRequest)
        assertEquals("the draft is intact until the user confirms", "12.50", st.amount)

        viewModel.confirmOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        assertEquals("a confirmed navigate discards the amount", "", st.amount)
        assertEquals("a confirmed navigate discards the merchant", "", st.merchant)
        assertEquals("EUR", st.selectedCurrency)

        viewModel.consumeOpenSettingRequest()
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals("", st.amount)
        assertEquals("EUR", st.selectedCurrency)
    }

    @Test
    fun `C-1 confirmed open-setting from a foreign draft goes back to a blank home draft and navigates`() {
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        viewModel.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.CONFIRM_NEEDED, st.openSettingRequest)

        viewModel.confirmOpenExchangeRateSetting()

        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        assertEquals("", st.amount)
        assertEquals("", st.merchant)
        assertEquals("EUR", st.selectedCurrency)
    }

    @Test
    fun `C-1 confirm when the request is not CONFIRM_NEEDED leaves the state unchanged`() {
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        val before = st

        // request is NONE
        viewModel.confirmOpenExchangeRateSetting()
        assertEquals(before, st)
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals("12.50", st.amount)
        assertEquals("USD", st.selectedCurrency)

        // request is NAVIGATE (no amount entered path): confirm must not reset or re-request
        viewModel.reset()
        viewModel.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        val navigating = st
        viewModel.confirmOpenExchangeRateSetting()
        assertEquals(navigating, st)
    }

    @Test
    fun `B2a declining the open-setting confirmation clears the request and keeps the form`() {
        openMec()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        viewModel.requestOpenExchangeRateSetting()

        viewModel.consumeOpenSettingRequest()

        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals("Shop", st.merchant)
        assertEquals("12.50", st.amount)
    }

    @Test
    fun `B2a confirm without a pending request does nothing and the VM never enables the setting`() {
        openMec()

        viewModel.confirmOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)

        viewModel.updateAmount("1.00")
        viewModel.requestOpenExchangeRateSetting()
        viewModel.confirmOpenExchangeRateSetting()
        coVerify(exactly = 0) { privacyRepo.updateSettings(any()) }
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
    }

    // ---- 80c: Fill compares against the SELECTED currency and never changes it ----

    @Test
    fun `B2a 80c with USD selected 12 USD fills and the selection stays USD`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(16))
        openMec()
        viewModel.selectCurrency("USD")
        settle()

        viewModel.fill("coffee 12 USD")
        settle()

        assertEquals("12.00", st.amount)
        assertNull(st.fieldFlags[QuickAddField.AMOUNT])
        assertFalse(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED in st.lineHints)
        assertNotEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals("USD", st.selectedCurrency)
        assertEquals("EUR", st.homeCurrency)
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(11.11, "EUR")), region)
    }

    @Test
    fun `B2a 80c with USD selected 12 GBP is still foreign-blocked and the selection stays USD`() {
        openMec()
        viewModel.selectCurrency("USD")
        settle()
        loggedLines.clear()

        viewModel.fill("coffee 12 GBP")
        settle()

        assertEquals("", st.amount)
        assertEquals(FlagReason.AMOUNT_FOREIGN_CURRENCY, st.fieldFlags[QuickAddField.AMOUNT])
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals("USD", st.selectedCurrency)
        assertEquals(listOf("FOREIGN_CURRENCY_BLOCKED"), loggedLines.map { it.message })
    }

    @Test
    fun `B2a 80c with USD selected a home-code line is foreign to the selection and blocked`() {
        openMec()
        viewModel.selectCurrency("USD")
        settle()

        viewModel.fill("coffee 12 EUR")
        settle()

        assertEquals("", st.amount)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals("USD", st.selectedCurrency)
    }

    @Test
    fun `B2a 80c with the home currency selected 12 USD stays blocked and Fill never selects USD`() {
        openMec()

        viewModel.fill("coffee 12 USD")
        settle()

        assertEquals("", st.amount)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, st.lastFillOutcome)
        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    // ---- reset and new sessions start on home ----

    @Test
    fun `B2a reset returns to the home currency and clears the foreign session state`() {
        openMec(privacy = ratesOn)
        viewModel.toggleRecurring()
        viewModel.updateAmount("12.50")
        viewModel.selectCurrency("USD")
        viewModel.requestOpenExchangeRateSetting()
        settle()
        assertTrue(st.repeatClearedByCurrency)

        viewModel.reset()
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.repeatClearedByCurrency)
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals("", st.amount)
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
    }

    @Test
    fun `B2a a new sheet session starts on home but a configuration change keeps the choice`() {
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("12.50")
        settle()

        viewModel.onSheetOpened() // re-composition after a configuration change: zone already captured
        settle()
        assertEquals("USD", st.selectedCurrency)
        assertEquals("a configuration change keeps the amount with its currency", "12.50", st.amount)

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()
        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        // R-1(b): the USD amount is never re-denominated as EUR; the whole draft was discarded.
        assertEquals("", st.amount)
    }

    @Test
    fun `R-1b a USD draft then onSheetLeft then onSheetOpened gives a blank draft and home selected`() {
        openMec()
        viewModel.updateMerchant("Shop")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals("USD", st.selectedCurrency)

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals("", st.amount)
        assertEquals("", st.merchant)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-1c a home draft survives a new sheet session exactly as before`() {
        openMec()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        settle()

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals("12.50", st.amount)
        assertEquals("Shop", st.merchant)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `R-1c a draft switched back to home before the session ended is kept`() {
        openMec()
        viewModel.updateAmount("12.50")
        viewModel.selectCurrency("USD")
        viewModel.selectCurrency("EUR")
        settle()

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals("12.50", st.amount)
    }

    @Test
    fun `R-1b a new session after a foreign draft applies the incoming prefill on home`() {
        // Same order as the sheet: onSheetOpened (DisposableEffect) first, then the prefill (LaunchedEffect).
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Old")
        viewModel.updateAmount("20.00")
        settle()

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        viewModel.setInitialValuesIfBlank(amount = "7.50", merchant = "Cafe")
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals("7.50", st.amount)
        assertEquals("Cafe", st.merchant)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    // ---- R-2: a refusal cancels the in-flight preview, so a late result cannot overwrite the SAVE card ----

    private fun assertRefusalBeatsSlowPreview(code: String, variant: CurrencyBlockVariant) {
        openEligibleUsdForm()
        rateStore.latencyMs = { _, _, _ -> 1_000L }
        stubAddManualExpenseReturns(Result.Error(message = code))

        viewModel.updateAmount("30.00") // starts a slow evaluation of the new figure
        viewModel.save()
        runNow() // the evaluation is now suspended in the store; the refusal arrives
        val saveCard = CurrencyRegionUi.Blocked(variant, null, CurrencyBlockOrigin.SAVE)
        assertEquals(saveCard, region)
        val saveBlockedAtRefusal = st.isCurrencySaveBlocked

        advance(5_000) // the late result would be Eligible(0.9 * 30.00)
        settle()

        assertEquals("the late preview must not overwrite the SAVE card", saveCard, region)
        assertEquals("the late preview must not change Save", saveBlockedAtRefusal, st.isCurrencySaveBlocked)
        assertFalse(st.isSaving)
        assertEquals("30.00", st.amount)
    }

    @Test
    fun `R-2 a NONE refusal cancels a slow in-flight preview and Save stays blocked`() {
        assertRefusalBeatsSlowPreview(RateRefusalCodes.NOT_ELIGIBLE_NONE, CurrencyBlockVariant.NONE)
        assertTrue("Save stays disabled; a late Eligible must not re-enable it", st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-2 an UNSUPPORTED refusal cancels a slow in-flight preview and Save stays blocked`() {
        assertRefusalBeatsSlowPreview(RateRefusalCodes.NOT_ELIGIBLE_UNSUPPORTED, CurrencyBlockVariant.UNSUPPORTED)
        assertTrue("Save stays disabled; a late Eligible must not re-enable it", st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-2 a HOME_UNRESOLVED refusal cancels a slow in-flight preview and keeps its retry error`() {
        assertRefusalBeatsSlowPreview(RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED, CurrencyBlockVariant.HOME_UNRESOLVED)
        assertEquals(
            SaveResult.Error(UiText.StringResource(R.string.mec_block_home_unresolved)),
            st.saveResult
        )
        assertFalse("Save stays enabled for a retry", st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-2 control without a refusal the same slow preview does apply`() {
        openEligibleUsdForm()
        rateStore.latencyMs = { _, _, _ -> 1_000L }

        viewModel.updateAmount("30.00")
        runNow()
        advance(5_000)

        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(27.0, "EUR")), region)
    }

    // ---- R-3: a second Fill that writes the amount drops the shown figure to Pending ----

    @Test
    fun `R-3 a second Fill that writes AMOUNT moves the region through Pending, never the old figure`() {
        putRate("USD", "EUR", 0.5, oct(16))
        openMec()
        viewModel.selectCurrency("USD")
        viewModel.fill("coffee 12 USD")
        settle()
        assertEquals("12.00", st.amount)
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(6.0, "EUR")), region)

        rateStore.latencyMs = { _, _, _ -> 1_000L }
        viewModel.fill("coffee 20 USD")
        runNow()

        assertEquals("20.00", st.amount)
        assertEquals("the old converted figure must not stay visible", CurrencyRegionUi.Pending, region)
        advance(5_000)
        assertEquals(eligibleUsd(oct(16), CurrencyFormatter.formatMoney(10.0, "EUR")), region)
    }

    // ---- R-4: an amount-independent Blocked card is kept through amount edits ----

    @Test
    fun `R-4 an amount edit while Blocked NONE keeps the card and Save blocked, then re-reads`() {
        openBlockedUsd(PrivacySettingsLoadState.Loaded(PrivacySettings()))
        assertTrue(st.isCurrencySaveBlocked)

        viewModel.updateAmount("30.00")
        val blockedNone = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW)
        assertEquals("no Pending in between", blockedNone, region)
        assertTrue(st.isCurrencySaveBlocked)
        runNow()
        assertEquals(blockedNone, region)
        assertTrue(st.isCurrencySaveBlocked)

        // The re-read still happens: once a rate exists, the next edit leaves the card.
        putRate("USD", "EUR", 0.9, oct(15))
        viewModel.updateAmount("40.00")
        assertEquals(blockedNone, region)
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(36.0, "EUR")), region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-4 an amount edit while Blocked UNSUPPORTED or HOME_UNRESOLVED from SAVE keeps the card`() {
        openEligibleUsdForm()
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_UNSUPPORTED))
        viewModel.save()
        settle()
        val unsupported = CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, CurrencyBlockOrigin.SAVE)
        assertEquals(unsupported, region)

        viewModel.updateAmount("30.00")
        assertEquals(unsupported, region)
        assertTrue(st.isCurrencySaveBlocked)

        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED))
        viewModel.updateAmount("31.00")
        settle()
        viewModel.save()
        settle()
        val homeUnresolved = CurrencyRegionUi.Blocked(CurrencyBlockVariant.HOME_UNRESOLVED, null, CurrencyBlockOrigin.SAVE)
        assertEquals(homeUnresolved, region)

        viewModel.updateAmount("32.00")
        assertEquals(homeUnresolved, region)
        assertFalse("HOME_UNRESOLVED never blocks Save", st.isCurrencySaveBlocked)
    }

    @Test
    fun `R-4 control an amount edit while Eligible still drops to Pending`() {
        openEligibleUsdForm()

        viewModel.updateAmount("40.00")

        assertEquals(CurrencyRegionUi.Pending, region)
        settle()
        assertEquals(eligibleUsd(oct(15), CurrencyFormatter.formatMoney(36.0, "EUR")), region)
    }

    @Test
    fun `R-4 an edit that makes the amount invalid leaves the kept Blocked card for AmountInvalid`() {
        openBlockedUsd(PrivacySettingsLoadState.Loaded(PrivacySettings()))

        viewModel.updateAmount("0")

        assertTrue(region is CurrencyRegionUi.AmountInvalid)
        assertTrue(st.isCurrencySaveBlocked)
    }

    // ---- AE-30: nothing about amounts, currencies, rates or dates is ever logged ----

    @Test
    fun `B2a AE-30 a full foreign session logs nothing`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(15))
        ratesRepo.outcome = { CurrencyRateRefreshOutcome.Refreshed(1) }
        openMec(privacy = ratesOn)
        stubAddManualExpenseReturns(Result.Error(message = RateRefusalCodes.NOT_ELIGIBLE_NONE))

        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("20.00")
        settle()
        viewModel.selectCurrency("JPY")
        viewModel.selectCurrency("USD")
        settle()
        viewModel.refreshRates()
        settle()
        viewModel.requestOpenExchangeRateSetting()
        viewModel.save()
        settle()
        viewModel.reset()
        settle()

        assertTrue(loggedLines.joinToString { it.message }, loggedLines.isEmpty())
    }

    @Test
    fun `B2a the observable state never renders the captured zone name`() {
        putRate("USD", "EUR", 1.0 / 1.08, oct(15))
        openMec(zone = ZoneId.of("Pacific/Auckland"), now = at(ZoneId.of("Pacific/Auckland"), 2025, 10, 16, 14, 30))
        viewModel.updateDate(at(ZoneId.of("Pacific/Auckland"), 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()

        val rendered = st.toString()
        assertFalse(rendered.contains("Auckland"))
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    // ---- derived flags ----

    @Test
    fun `B2a isCurrencySaveBlocked is true only for a blocked rate, an invalid amount or an unset currency`() {
        fun blockedFor(region: CurrencyRegionUi) = AddExpenseState(currencyRegion = region).isCurrencySaveBlocked

        assertTrue(blockedFor(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW)))
        assertTrue(blockedFor(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, oct(1), CurrencyBlockOrigin.SAVE)))
        assertTrue(blockedFor(CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, CurrencyBlockOrigin.PREVIEW)))
        assertTrue(blockedFor(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP)))
        assertTrue(blockedFor(CurrencyRegionUi.Unset(com.yourname.expensetracker.domain.currency.CurrencyUnsetReason.NONE_PROPOSED)))

        assertFalse(blockedFor(CurrencyRegionUi.Blocked(CurrencyBlockVariant.HOME_UNRESOLVED, null, CurrencyBlockOrigin.SAVE)))
        assertFalse(blockedFor(CurrencyRegionUi.Evaluating))
        assertFalse(blockedFor(CurrencyRegionUi.Pending))
        assertFalse(blockedFor(CurrencyRegionUi.Loading))
        assertFalse(blockedFor(CurrencyRegionUi.Home))
        assertFalse(blockedFor(CurrencyRegionUi.ReadOnly))
        assertFalse(blockedFor(eligibleUsd(oct(1), null)))
    }

    @Test
    fun `B2a isForeignSelected needs both currencies known and different`() {
        assertFalse(AddExpenseState(homeCurrency = null, selectedCurrency = "USD").isForeignSelected)
        assertFalse(AddExpenseState(homeCurrency = "EUR", selectedCurrency = null).isForeignSelected)
        assertFalse(AddExpenseState(homeCurrency = "EUR", selectedCurrency = "EUR").isForeignSelected)
        assertTrue(AddExpenseState(homeCurrency = "EUR", selectedCurrency = "USD").isForeignSelected)
    }
}

/** In-memory rate store for the add-expense VM tests (no mocks): optional per-call virtual latency and failure. */
internal class AddExpenseRateStoreFake : ExchangeRateStore {
    val rows = mutableListOf<DomainExchangeRate>()
    val asOfCalls = mutableListOf<Triple<String, String, Long>>()

    /** Virtual milliseconds one `getRateAsOf` takes, by (from, to, atMillis). */
    var latencyMs: (String, String, Long) -> Long = { _, _, _ -> 0L }
    var failure: Throwable? = null

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? = null

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? = null

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        asOfCalls += Triple(fromCurrency, toCurrency, atMillis)
        val wait = latencyMs(fromCurrency, toCurrency, atMillis)
        if (wait > 0L) delay(wait)
        failure?.let { throw it }
        return rows
            .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            .filter { row -> row.validDate?.let { it <= atMillis } ?: false }
            .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        rows += rate
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        rows += rates
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())

    override suspend fun getLatestRate(): DomainExchangeRate? = null

    override suspend fun deleteOldRates(olderThan: Long) = Unit
}

/** Counts user-started refreshes; the outcome is scripted per test. */
internal class AddExpenseRatesRepositoryFake : CurrencyRatesRepository {
    val calls = mutableListOf<String>()
    var outcome: suspend (String) -> CurrencyRateRefreshOutcome = { CurrencyRateRefreshOutcome.Refreshed(0) }

    override suspend fun refresh(homeCurrency: String): CurrencyRateRefreshOutcome {
        calls += homeCurrency
        return outcome(homeCurrency)
    }

    val historyCalls = mutableListOf<String>()
    var historyOutcome: com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome =
        com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome.DisabledByPrivacy

    override suspend fun downloadRecentHistory(
        homeCurrency: String
    ): com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome {
        historyCalls += homeCurrency
        return historyOutcome
    }
}