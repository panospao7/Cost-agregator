package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
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

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    private lateinit var engine: RecurringExpenseEngine

    @Before
    fun setup() {
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        timeProvider = mockk()
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()
        coEvery { timeProvider.now() } returns timestampOf(2026, Calendar.APRIL, 15)
        engine = RecurringExpenseEngine(expenseRepository, recurringExpenseRepository, timeProvider)
    }

    // Helper: convert test Expense objects to ExpenseSnapshot for the new production code path.
    private fun List<Expense>.toSnapshots(): List<ExpenseSnapshot> = map { it.toTestSnapshot() }

    private fun Expense.toTestSnapshot(): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        transferDirection = when (transferDirection) {
            TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
            TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
            null -> null
        },
        notes = notes
    )

    @Test
    fun `should detect perfect monthly subscription`() = runTest {
        val expenses = listOf(
            createExpense("Netflix", 15.0, "2026-01-01"),
            createExpense("Netflix", 15.0, "2026-02-01"),
            createExpense("Netflix", 15.0, "2026-03-01"),
            createExpense("Netflix", 15.0, "2026-04-01")
        )
        
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

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
        
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

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
        
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

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
        
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

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
        
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()
        
        val manualOverride = ManualRecurringExpense(
            merchant = "Gym",
            amount = 50.0,
            frequency = RecurrenceFrequency.WEEKLY, // Override
            nextDate = 1000L,
            createdAt = 1000L
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(manualOverride)

        // Act
        val patterns = engine.getPatterns()

        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Gym", pattern.merchantName)
        assertEquals(RecurrenceFrequency.WEEKLY, pattern.frequency) // Should be WEEKLY, not MONTHLY
        assertEquals(1.0f, pattern.confidence, 0.0f) // Manual = 1.0 confidence
    }

    @Test
    fun `exactly 3 occurrences minimum threshold`() = runTest {
        val expenses = listOf(
            createExpense("Test", 10.0, "2026-01-01"),
            createExpense("Test", 10.0, "2026-02-01"),
            createExpense("Test", 10.0, "2026-03-01")
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()
        
        val patterns = engine.getPatterns()
        assertTrue("Should detect with 3 occurrences", patterns.isNotEmpty())
    }

    @Test
    fun `monthly pattern tolerates variable month length`() = runTest {
        val expenses = listOf(
            createExpense("Rent", 800.0, "2026-01-31"),
            createExpense("Rent", 800.0, "2026-02-28"),
            createExpense("Rent", 800.0, "2026-03-31")
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

        val patterns = engine.getPatterns()
        assertEquals("Expected one recurring pattern", 1, patterns.size)
        assertEquals(RecurrenceFrequency.MONTHLY, patterns.first().frequency)
    }

    @Test
    fun `exactly 2 occurrences should not detect`() = runTest {
        val expenses = listOf(
            createExpense("Test", 10.0, "2026-01-01"),
            createExpense("Test", 10.0, "2026-02-01")
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()
        
        val patterns = engine.getPatterns()
        assertTrue("Should NOT detect with 2 occurrences", patterns.isEmpty())
    }

    @Test
    fun `merchant case variations grouped together`() = runTest {
        val expenses = listOf(
            createExpense("Netflix", 10.0, "2026-01-01"),
            createExpense("NETFLIX", 10.0, "2026-02-01"),
            createExpense("netflix", 10.0, "2026-03-01")
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()
        
        val patterns = engine.getPatterns()
        // If this fails, it means normalization is missing in RecurringExpenseEngine
        assertEquals("Should group case variations into 1 pattern", 1, patterns.size)
    }

    @Test
    fun `should ignore recurring candidates marked not mine`() = runTest {
        val expenses = listOf(
            createExpense("Streaming", 20.0, "2026-01-01", isNotMine = true),
            createExpense("Streaming", 20.0, "2026-02-01", isNotMine = true),
            createExpense("Streaming", 20.0, "2026-03-01", isNotMine = true)
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

        val patterns = engine.getPatterns()
        assertTrue("Not-mine expenses should not produce recurring patterns", patterns.isEmpty())
    }

    @Test
    fun `manual stale next date is rolled forward into the future`() = runTest {
        val staleManual = ManualRecurringExpense(
            id = 7L,
            merchant = "Gym",
            amount = 50.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = timestampOf(2026, Calendar.JANUARY, 10),
            createdAt = timestampOf(2025, Calendar.DECEMBER, 1)
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns emptyList()
        coEvery { recurringExpenseRepository.getAll() } returns listOf(staleManual)

        val patterns = engine.getPatterns()

        assertEquals(1, patterns.size)
        assertEquals(timestampOf(2026, Calendar.MAY, 10), patterns.first().nextExpectedDate)
    }

    @Test
    fun `detected stale next date is rolled forward into the future`() = runTest {
        val expenses = listOf(
            createExpense("Water", 30.0, "2025-11-01"),
            createExpense("Water", 30.0, "2025-12-01"),
            createExpense("Water", 30.0, "2026-01-01")
        )
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns expenses.toSnapshots()

        val patterns = engine.getPatterns()

        assertEquals(1, patterns.size)
        assertEquals(RecurrenceFrequency.MONTHLY, patterns.first().frequency)
        assertEquals(timestampOf(2026, Calendar.MAY, 1), patterns.first().nextExpectedDate)
    }


    private fun createExpense(
        merchant: String,
        amount: Double,
        dateStr: String,
        isNotMine: Boolean = false
    ): Expense {
        // Simple parser for test dates
        val parts = dateStr.split("-")
        val calendar = Calendar.getInstance()
        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 12, 0)
        
        return Expense(
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = calendar.timeInMillis,
            createdAt = System.currentTimeMillis(),
            isNotMine = isNotMine
        )
    }

    private fun timestampOf(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
