package com.yourname.expensetracker.ui.screens.addexpense

import android.database.sqlite.SQLiteException
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: AddExpenseViewModel

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

        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        viewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = currencyRepo,
        )
    }

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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
                recurringNote = any()
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
                recurringNote = any()
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } throws t
    }

    private fun stubAddManualExpenseReturns(result: Result<Long>) {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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

        assertEquals(
            UiText.StringResource(R.string.add_expense_error_amount_too_large),
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
        val loadingViewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = loadingCurrencyRepo,
        )
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
}