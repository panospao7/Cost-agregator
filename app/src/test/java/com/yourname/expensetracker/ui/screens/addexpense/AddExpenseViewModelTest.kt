package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: AddExpenseViewModel

    @Before
    override fun setup() {
        super.setup()
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
        assertEquals("Enter a valid amount", viewModel.state.value.amountError)
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
}