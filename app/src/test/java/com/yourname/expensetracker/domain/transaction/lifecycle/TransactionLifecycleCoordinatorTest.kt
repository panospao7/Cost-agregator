package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TransactionLifecycleCoordinator.createExpense].
 *
 * Validates the full lifecycle: validate → normalize → dedupe → insert → event logging.
 */
@Suppress("DEPRECATION_ERROR")
class TransactionLifecycleCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var sideEffectDispatcher: TransactionSideEffectDispatcher
    private lateinit var recurringLifecycleCoordinator: RecurringLifecycleCoordinator
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var coordinator: TransactionLifecycleCoordinator

    private val now = 1_712_000_000_000L // 2024-04-01ish

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        transactionEventDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        currencyConverter = mockk(relaxed = true)
        sideEffectDispatcher = mockk(relaxed = true)
        recurringLifecycleCoordinator = mockk(relaxed = true)
        restoreMaintenanceMode = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        // Allow writes (not in restore mode)
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        // Simulate successful insert - returns an ID
        coEvery { expenseDao.insertAtomic(any()) } returns 42L
        // Simulate successful event insert
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            sideEffectDispatcher = sideEffectDispatcher,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = writeBarrier,
            currencySettingsRepository = mockk(relaxed = true)
        )
    }

    @Test
    fun `createExpense with valid request returns Created`() = runTest {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0,
            currency = "EUR",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
        assertTrue("Expected expenseId=42", (result as CreateExpenseResult.Created).expenseId == 42L)

        // Verify expense was inserted
        coVerify(exactly = 1) { expenseDao.insertAtomic(any()) }
        // Verify event was logged
        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
    }

    @Test
    fun `createExpense with negative amount returns ValidationFailed`() = runTest {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = -5.0,
            currency = "EUR",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `createExpense with blank merchant returns ValidationFailed`() = runTest {
        val request = CreateExpenseRequest(
            merchant = "",
            amount = 10.0,
            currency = "EUR",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `createExpense with invalid currency returns ValidationFailed`() = runTest {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0,
            currency = "EURO",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }
}
