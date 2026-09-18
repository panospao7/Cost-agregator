package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionUpdateKind
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidationError
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import org.junit.After
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
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var runner: PostCommitActionRunner
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var transactionValidator: TransactionValidator
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
        // GR-14p: mutations are scoped in writeBarrier.runWrite; a relaxed mock
        // would neither run the block nor return its value (the coordinator
        // casts the result to Long), so pass the block through.
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        // The scoped block opens a Room transaction; a relaxed database mock
        // would never execute it. Pass the transaction block through (same
        // pattern as NotificationRepositoryDeleteAllNotificationsClockTest).
        //
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2), so the stub
        // only intercepts with mockkStatic — without it, the real Room body runs
        // against the relaxed AppDatabase mock and suspends forever (measured
        // hang: docs/testing/test-sweep-outcomes-2026-09-18.md, §Hangs).
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        currencySettingsRepository = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        // Allow writes (not in restore mode)
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        // Simulate successful insert - returns an ID
        coEvery { expenseDao.insertAtomic(any()) } returns 42L
        // Simulate successful event insert
        coEvery { transactionEventDao.insert(any()) } returns 1L

        runner = mockk(relaxed = true)
        planner = mockk(relaxed = true)
        // Relaxed mock: validateCreate returns an empty list (valid) unless a
        // test stubs errors — the validation tests below stub it explicitly.
        transactionValidator = mockk(relaxed = true)

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            sideEffectDispatcher = sideEffectDispatcher,
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = transactionValidator,
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `createExpense with valid request returns Created`() = runTest(timeout = 60.seconds) {
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
        // Verify event audit trail: production writes CREATE_ATTEMPTED before
        // validation and CREATED after the atomic insert
        // (TransactionLifecycleCoordinator.kt:293-339), so exactly 2 inserts.
        coVerify(exactly = 2) { transactionEventDao.insert(any()) }
    }

    @Test
    fun `createExpense with negative amount returns ValidationFailed`() = runTest(timeout = 60.seconds) {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = -5.0,
            currency = "EUR",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )
        every { transactionValidator.validateCreate(any()) } returns listOf(
            TransactionValidationError(code = "AMOUNT_INVALID", message = "amount must be positive", field = "amount")
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `createExpense with blank merchant returns ValidationFailed`() = runTest(timeout = 60.seconds) {
        val request = CreateExpenseRequest(
            merchant = "",
            amount = 10.0,
            currency = "EUR",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )
        every { transactionValidator.validateCreate(any()) } returns listOf(
            TransactionValidationError(code = "MERCHANT_BLANK", message = "merchant must not be blank", field = "merchant")
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `createExpense with invalid currency returns ValidationFailed`() = runTest(timeout = 60.seconds) {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0,
            currency = "EURO",
            date = now,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )
        every { transactionValidator.validateCreate(any()) } returns listOf(
            TransactionValidationError(code = "CURRENCY_INVALID", message = "currency must be a valid ISO code", field = "currency")
        )

        val result = coordinator.createExpense(request)

        assertTrue("Expected ValidationFailed, got $result", result is CreateExpenseResult.ValidationFailed)
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    // ── U-001 (RP-01): cancellation must propagate through conversion and best-effort event writes ──

    private fun usdCreateRequest() = CreateExpenseRequest(
        merchant = "Test",
        amount = 10.0,
        currency = "USD",
        date = now,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.MANUAL_ENTRY
    )

    @Test
    fun `createExpense converter cancellation propagates and nothing is committed`() = runTest(timeout = 60.seconds) {
        coEvery {
            currencyConverter.convertAsOf(
                amount = any<Double>(),
                fromCurrency = any<String>(),
                toCurrency = any<String>(),
                atMillis = any<Long>()
            )
        } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.createExpense(usdCreateRequest())
        }
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `createExpense event-write cancellation propagates before insert`() = runTest(timeout = 60.seconds) {
        coEvery { transactionEventDao.insert(any()) } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.createExpense(usdCreateRequest())
        }
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `updateExpense converter cancellation propagates before transaction`() = runTest(timeout = 60.seconds) {
        val existing = Expense(
            id = 1L, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "USD", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery {
            currencyConverter.convertAsOf(
                amount = any<Double>(),
                fromCurrency = any<String>(),
                toCurrency = any<String>(),
                atMillis = any<Long>()
            )
        } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.updateExpense(existing)
        }
        coVerify(exactly = 0) { expenseDao.getById(any()) }
    }

    /**
     * U-001: non-cancellation conversion failure must keep the null fallback.
     *
     * Un-ignored now that the fixture intercepts the top-level Room facade via
     * mockkStatic("androidx.room.RoomDatabaseKt") (the sweep-hang fix this file
     * documented as the RP-21 candidate); the transaction block executes, so
     * tests crossing `database.withTransaction` no longer hang.
     */
    @Test
    fun `createExpense converter non-cancellation failure keeps existing fallback`() = runTest(timeout = 60.seconds) {
        coEvery {
            currencyConverter.convertAsOf(
                amount = any<Double>(),
                fromCurrency = any<String>(),
                toCurrency = any<String>(),
                atMillis = any<Long>()
            )
        } throws IOException("offline")

        val result = coordinator.createExpense(usdCreateRequest())

        assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
        coVerify(exactly = 1) { expenseDao.insertAtomic(any()) }
    }

    /** CE subclass mirroring TimeoutCancellationException (whose constructor is internal). */
    private class TimeoutLikeCancellation(message: String) : CancellationException(message)

    @Test
    fun `createExpense timeout cancellation is not swallowed into fallback`() = runTest(timeout = 60.seconds) {
        coEvery {
            currencyConverter.convertAsOf(
                amount = any<Double>(),
                fromCurrency = any<String>(),
                toCurrency = any<String>(),
                atMillis = any<Long>()
            )
        } throws TimeoutLikeCancellation("timeout")

        assertFailsWith<TimeoutLikeCancellation> {
            coordinator.createExpense(usdCreateRequest())
        }
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    private fun nonEmptyBatch(): PostCommitActionBatch {
        val action = PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "expense",
            targetEntityId = 42L,
            source = "test",
            correlationId = "test",
            causationId = null,
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )
        return PostCommitActionBatch("test", listOf(action))
    }

    @Test
    fun `updateType runner cancellation rethrows`() = runTest(timeout = 60.seconds) {
        val expenseId = 1L
        val existingExpense = Expense(
            id = expenseId, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(expenseId) } returns existingExpense
        // 8 positional matchers: the DAO method has 8 params (windowMs has a
        // default in the middle, ExpenseDao.kt:852-861) — a 7-any() stub misses
        // the recorded call and the relaxed mock returns 0L (a phantom duplicate).
        coEvery { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery { planner.planUpdated(any(), any(), any(), TransactionUpdateKind.TYPE) } returns nonEmptyBatch()
        coEvery { runner.run(any()) } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.updateType(expenseId, TransactionType.TRANSFER)
        }
    }

    @Test
    fun `updateExpense runner cancellation rethrows`() = runTest(timeout = 60.seconds) {
        val existingExpense = Expense(
            id = 1L, amount = 10.0, merchant = "Original",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(1L) } returns existingExpense
        coEvery { planner.planUpdated(any(), any(), any(), TransactionUpdateKind.FULL) } returns nonEmptyBatch()
        coEvery { runner.run(any()) } throws CancellationException("Cancelled")

        val updatedExpense = existingExpense.copy(merchant = "Updated")
        assertFailsWith<CancellationException> {
            coordinator.updateExpense(updatedExpense)
        }
    }

    @Test
    fun `deleteExpense runner cancellation rethrows`() = runTest(timeout = 60.seconds) {
        val expenseId = 1L
        val expense = Expense(
            id = expenseId, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(expenseId) } returns expense
        coEvery { planner.planDeleted(any(), any(), any()) } returns nonEmptyBatch()
        coEvery { runner.run(any()) } throws CancellationException("Cancelled")

        // The delete commits inside the transaction; the post-commit runner's
        // CancellationException must rethrow (runBestEffortAfterCommit always
        // rethrows CE — PostCommitActionRunnerExtensions.kt), never be captured
        // into a failure result.
        assertFailsWith<CancellationException> {
            coordinator.deleteExpense(expense)
        }
        coVerify(exactly = 1) { expenseDao.delete(any()) }
    }

    @Test
    fun `updateType runner non-cancellation failure does not rollback committed update`() = runTest(timeout = 60.seconds) {
        val expenseId = 1L
        val existingExpense = Expense(
            id = expenseId, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(expenseId) } returns existingExpense
        // 8 positional matchers (see note above): 7-any() misses the recorded call.
        coEvery { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery { planner.planUpdated(any(), any(), any(), TransactionUpdateKind.TYPE) } returns nonEmptyBatch()
        coEvery { runner.run(any()) } throws RuntimeException("Best-effort failure")

        coordinator.updateType(expenseId, TransactionType.TRANSFER)

        coVerify(exactly = 1) { expenseDao.updateTransactionType(expenseId, TransactionType.TRANSFER.name, any()) }
    }
}
