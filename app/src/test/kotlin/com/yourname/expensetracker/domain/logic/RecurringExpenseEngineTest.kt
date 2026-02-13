package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class RecurringExpenseEngineTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var recurringExpenseDao: RecurringExpenseDao
    private lateinit var engine: RecurringExpenseEngine

    @Before
    fun setup() {
        expenseDao = mockk()
        recurringExpenseDao = mockk()
        // Default: No manual expenses
        coEvery { recurringExpenseDao.getAll() } returns emptyList()
        engine = RecurringExpenseEngine(expenseDao, recurringExpenseDao)
    }

    @Test
    fun `should detect perfect monthly subscription`() = runTest {
        // Arrange: Netflix on the 1st of every month
        val expenses = listOf(
            createExpense("Netflix", 15.0, "2026-01-01"),
            createExpense("Netflix", 15.0, "2026-02-01"), // 31 days
            createExpense("Netflix", 15.0, "2026-03-01"), // 28 days (non-leap year 2026)
            createExpense("Netflix", 15.0, "2026-04-01")  // 31 days
        )
        
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses

        // Act
        val patterns = engine.getPatterns()

        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Netflix", pattern.merchantName)
        assertEquals(15.0, pattern.averageAmount, 0.01)
        assertEquals(RecurrenceFrequency.MONTHLY, pattern.frequency)
    }

    @Test
    fun `should detect bi-weekly salary`() = runTest {
        // Arrange: Salary every 14 days
        val expenses = listOf(
            createExpense("Corp Inc", 2000.0, "2026-01-05"), // Fri
            createExpense("Corp Inc", 2000.0, "2026-01-19"), // Fri + 14
            createExpense("Corp Inc", 2000.0, "2026-02-02"), // Fri + 14
            createExpense("Corp Inc", 2000.0, "2026-02-16")  // Fri + 14
        )
        
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses

        // Act
        val patterns = engine.getPatterns()

        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Corp Inc", pattern.merchantName)
        assertEquals(RecurrenceFrequency.BIWEEKLY, pattern.frequency)
    }

    @Test
    fun `should ignore random coffee purchases`() = runTest {
        // Arrange: Random coffee dates
        val expenses = listOf(
            createExpense("Starbucks", 5.0, "2026-01-01"),
            createExpense("Starbucks", 5.0, "2026-01-02"), // 1 day
            createExpense("Starbucks", 6.5, "2026-01-08"), // 6 days
            createExpense("Starbucks", 4.5, "2026-01-20")  // 12 days
        )
        // Intervals: 1, 6, 12 -> Irregular
        
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses

        // Act
        val patterns = engine.getPatterns()

        // Assert
        assertTrue(patterns.isEmpty())
    }
    
    @Test
    fun `should ignore variable bills (high amount variance)`() = runTest {
        // Arrange: Electricity bill with huge variance
        val expenses = listOf(
            createExpense("Electric Co", 50.0, "2026-01-01"),
            createExpense("Electric Co", 150.0, "2026-02-01"), 
            createExpense("Electric Co", 80.0, "2026-03-01"), 
            createExpense("Electric Co", 200.0, "2026-04-01")  
        )
        
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses

        // Act
        val patterns = engine.getPatterns()

        // Assert
        // Mean = 120. StdDev ~ 67. Variance ~ 0.55 (> 0.2 threshold)
        assertTrue(patterns.isEmpty())
    }

    @Test
    fun `manual override should take precedence`() = runTest {
        // Arrange: detected pattern is Monthly, but Manual Overrides says Weekly
        val expenses = listOf(
            createExpense("Gym", 50.0, "2026-01-01"),
            createExpense("Gym", 50.0, "2026-02-01"),
            createExpense("Gym", 50.0, "2026-03-01")
        )
        
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        
        val manualOverride = ManualRecurringExpense(
            merchant = "Gym",
            amount = 50.0,
            frequency = RecurrenceFrequency.WEEKLY, // Override
            nextDate = 1000L,
            createdAt = 1000L
        )
        coEvery { recurringExpenseDao.getAll() } returns listOf(manualOverride)

        // Act
        val patterns = engine.getPatterns()

        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Gym", pattern.merchantName)
        assertEquals(RecurrenceFrequency.WEEKLY, pattern.frequency) // Should be WEEKLY, not MONTHLY
        assertEquals(1.0f, pattern.confidence, 0.0f) // Manual = 1.0 confidence
    }

    private fun createExpense(merchant: String, amount: Double, dateStr: String): Expense {
        // Simple parser for test dates
        val parts = dateStr.split("-")
        val calendar = Calendar.getInstance()
        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 12, 0)
        
        return Expense(
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = calendar.timeInMillis
        )
    }
}
