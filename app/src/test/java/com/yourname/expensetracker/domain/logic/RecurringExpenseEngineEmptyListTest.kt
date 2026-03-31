package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RecurringExpenseEngine bug fix:
 * - Empty dates list crash fix
 */
class RecurringExpenseEngineEmptyListTest {

    private lateinit var engine: RecurringExpenseEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var timeProvider: TimeProvider

    @Before
    fun setup() {
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        timeProvider = mockk()
        engine = RecurringExpenseEngine(
            expenseRepository,
            recurringExpenseRepository,
            timeProvider
        )
        
        every { timeProvider.now() } returns System.currentTimeMillis()
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()
    }

    @Test
    fun `getPatterns handles empty expenses list gracefully`() = runTest {
        val expenses = emptyList<Expense>()
        
        val patterns = engine.getPatterns(expenses)
        
        assertTrue("Should return empty list for no expenses", patterns.isEmpty())
    }

    @Test
    fun `getPatterns handles single expense without crash`() = runTest {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            createExpense(now, 50.0, "Coffee Shop")
        )
        
        val patterns = engine.getPatterns(expenses)
        
        // Single expense (< 3 occurrences) should not create pattern
        assertTrue("Should return empty list for single expense", patterns.isEmpty())
    }

    @Test
    fun `getPatterns handles expenses filtered out by staleness check`() = runTest {
        // Expenses older than 6 months should be filtered out
        val nineMonthsAgo = System.currentTimeMillis() - (270L * 24 * 60 * 60 * 1000)
        val expenses = listOf(
            createExpense(nineMonthsAgo, 50.0, "Old Subscription"),
            createExpense(nineMonthsAgo + 30L * 24 * 60 * 60 * 1000, 50.0, "Old Subscription"),
            createExpense(nineMonthsAgo + 60L * 24 * 60 * 60 * 1000, 50.0, "Old Subscription")
        )
        
        val patterns = engine.getPatterns(expenses)
        
        // All expenses are older than 6 months, so should be filtered out
        assertTrue("Should filter out old expenses", patterns.isEmpty())
    }

    private fun createExpense(date: Long, amount: Double, merchant: String): Expense {
        return Expense(
            id = 0,
            amount = amount,
            currency = "EUR",
            merchant = merchant,
            merchantKey = merchant.lowercase().replace(" ", ""),
            transactionType = TransactionType.PURCHASE,
            date = date,
            categoryId = null,
            createdAt = date,
            paymentMethod = com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD,
            isManualEntry = false,
            isNotMine = false,
            isSharedExpense = false
        )
    }
}
