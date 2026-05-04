package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
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
import org.junit.Before
import org.junit.Test

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

        viewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = mockk(),
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
}