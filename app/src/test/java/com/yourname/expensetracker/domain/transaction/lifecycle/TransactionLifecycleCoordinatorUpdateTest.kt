package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-PR1: Tests that currency conversion failure clears stale baseAmount.
 *
 * Fixes: NEW-P2-007
 */
class TransactionLifecycleCoordinatorUpdateTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var coordinator: TransactionLifecycleCoordinator

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        transactionEventDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        currencyConverter = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        // Mock withTransaction to execute block directly
        mockkStatic("androidx.room.RoomDatabaseKt")
        val dbBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(dbBlock)) } coAnswers {
            dbBlock.captured.invoke()
        }

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = mockk<PostCommitActionRunner>(relaxed = true),
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    /**
     * NEW-P2-007: When currency conversion fails during updateExpense,
     * stale baseAmount/baseCurrency/exchangeRateUsed must be cleared to null.
     */
    @Test
    fun `updateExpense clears baseAmount when conversion fails`() = runTest {
        // Existing expense has stale conversion data from a previous successful conversion
        val existingExpense = Expense(
            id = 1L,
            amount = 50.0,
            currency = "USD",
            merchant = "Test",
            merchantKey = "test",
            transactionType = TransactionType.PURCHASE,
            date = now,
            baseAmount = 45.0,       // stale
            baseCurrency = "EUR",    // stale
            exchangeRateUsed = 0.9,  // stale
            dedupeKey = "test-key"
        )
        coEvery { expenseDao.getById(1L) } returns existingExpense

        // Conversion fails (returns null from runCatching)
        coEvery { currencyConverter.convertAsOf(any(), any(), any(), any()) } throws RuntimeException("Network error")

        // Capture the expense that gets persisted
        val updatedSlot = slot<Expense>()
        coEvery { expenseDao.update(capture(updatedSlot)) } returns Unit
        coEvery { transactionEventDao.insert(any()) } returns 1L

        // Update with same currency (USD) but different amount
        val updatedExpense = existingExpense.copy(amount = 75.0)
        coordinator.updateExpense(updatedExpense, reason = "amount change")

        // Verify baseAmount was cleared (not left as stale 45.0)
        val persisted = updatedSlot.captured
        assertTrue("baseAmount should be 0.0 after conversion failure, was ${persisted.baseAmount}", persisted.baseAmount == 0.0)
        assertTrue("baseCurrency should be empty after conversion failure, was '${persisted.baseCurrency}'", persisted.baseCurrency.isEmpty())
        assertTrue("exchangeRateUsed should be 0.0 after conversion failure, was ${persisted.exchangeRateUsed}", persisted.exchangeRateUsed == 0.0)
    }
}
