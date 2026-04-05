package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetectDuplicateExpenseUseCaseTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var userCorrectionRepository: UserCorrectionRepository
    private lateinit var crossSourceDeduplication: CrossSourceDeduplication
    private lateinit var useCase: DetectDuplicateExpenseUseCase

    @Before
    fun initUseCase() {
        expenseRepository = mockk(relaxed = true)
        userCorrectionRepository = mockk(relaxed = true)
        crossSourceDeduplication = mockk(relaxed = true)
        useCase = DetectDuplicateExpenseUseCase(
            expenseRepository = expenseRepository,
            userCorrectionRepository = userCorrectionRepository,
            crossSourceDeduplication = crossSourceDeduplication
        )
    }

    @Test
    fun `duplicate detected same merchant amount date`() = runTest {
        val date = 1_710_000_000_000L
        val existing = expense(id = 99L, merchant = "Coffee Shop", amount = 4.50, date = date)

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(existing)
        coEvery {
            crossSourceDeduplication.findExpenseDuplicate(
                amount = 4.50,
                merchant = "Coffee Shop",
                date = date,
                expenses = any(),
                timeWindowMs = any()
            )
        } returns existing

        val result = useCase(
            amount = 4.50,
            merchant = "Coffee Shop",
            date = date,
            source = DetectDuplicateExpenseUseCase.DuplicateDetectionSource.MANUAL_ENTRY
        )

        assertTrue(result is DuplicateCheckResult.Duplicate)
        assertEquals(99L, (result as DuplicateCheckResult.Duplicate).existingExpense.id)
    }

    @Test
    fun `no duplicate returns empty`() = runTest {
        val date = 1_710_000_500_000L
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery {
            crossSourceDeduplication.findExpenseDuplicate(
                amount = any(),
                merchant = any(),
                date = any(),
                expenses = any(),
                timeWindowMs = any()
            )
        } returns null

        val result = useCase(amount = 13.99, merchant = "Bakery", date = date)

        assertTrue(result is DuplicateCheckResult.None)
    }

    @Test
    fun `time window boundary check`() = runTest {
        val date = 1_710_001_000_000L
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { crossSourceDeduplication.findExpenseDuplicate(any(), any(), any(), any(), any()) } returns null

        useCase(
            amount = 10.0,
            merchant = "Boundary",
            date = date,
            source = DetectDuplicateExpenseUseCase.DuplicateDetectionSource.NOTIFICATION
        )

        val expectedWindow = 5 * 60 * 1000L
        val expectedStart = date - expectedWindow
        val expectedEndExclusive = date + expectedWindow + 1
        coVerify(exactly = 1) { expenseRepository.getExpensesBetween(expectedStart, expectedEndExclusive) }
    }

    @Test
    fun `empty expense list no duplicates`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { crossSourceDeduplication.findExpenseDuplicate(any(), any(), any(), any(), any()) } returns null

        val result = useCase(amount = 1.0, merchant = "", date = 1_700_000_000_000L)

        assertTrue(result is DuplicateCheckResult.None)
        coVerify(exactly = 1) { crossSourceDeduplication.findExpenseDuplicate(any(), any(), any(), emptyList(), any()) }
    }

    private fun expense(id: Long, merchant: String, amount: Double, date: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = date
        )
    }
}
