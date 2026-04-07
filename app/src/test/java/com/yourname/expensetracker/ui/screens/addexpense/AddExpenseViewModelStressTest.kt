package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("Stress test: may hang in CI, run manually")
class AddExpenseViewModelStressTest : ViewModelTestUtils() {

    private lateinit var manualExpenseRepository: ManualExpenseRepository
    private lateinit var expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var timeProvider: TimeProvider

    private lateinit var viewModel: AddExpenseViewModel

    @Before
    override fun setup() {
        super.setup()
        manualExpenseRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { recurringExpenseRepository.addRecurringExpense(any(), any(), any(), any(), any(), any()) } returns 1L

        viewModel = AddExpenseViewModel(
            manualExpenseRepository,
            expenseRepository,
            categoryRepository,
            recurringExpenseRepository,
            timeProvider
        )
    }

    @Test
    fun `stress - initial state has empty merchant and amount`() = runTest {
        val state = viewModel.state.value
        assertEquals("", state.merchant)
        assertEquals("", state.amount)
    }

    @Test
    fun `stress - updateMerchant updates state`() = runTest {
        viewModel.updateMerchant("Coffee Shop")
        advanceUntilIdle()
        assertEquals("Coffee Shop", viewModel.state.value.merchant)
    }

    @Test
    fun `stress - updateAmount updates state`() = runTest {
        viewModel.updateAmount("25.50")
        advanceUntilIdle()
        assertEquals("25.50", viewModel.state.value.amount)
    }

    @Test
    fun `stress - save with empty merchant sets merchantError`() = runTest {
        viewModel.updateAmount("10.00")
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertEquals("Merchant name is required", viewModel.state.value.merchantError)
    }

    @Test
    fun `stress - save with invalid amount sets amountError`() = runTest {
        viewModel.updateMerchant("Test")
        viewModel.updateAmount("")
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertEquals("Enter a valid amount", viewModel.state.value.amountError)
    }

    @Test
    fun `stress - save with valid data calls repository`() = runTest {
        coEvery { manualExpenseRepository.addManualExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.Success(1L)
        viewModel.updateMerchant("Valid Merchant")
        viewModel.updateAmount("50.00")
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
    }

    @Test
    fun `stress - selectCategory updates state`() = runTest {
        viewModel.selectCategory(5L)
        advanceUntilIdle()
        assertEquals(5L, viewModel.state.value.selectedCategoryId)
    }

    @Test
    fun `stress - reset clears state`() = runTest {
        viewModel.updateMerchant("Test")
        viewModel.updateAmount("10")
        advanceUntilIdle()
        viewModel.reset()
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.merchant)
        assertEquals("", viewModel.state.value.amount)
    }
}
