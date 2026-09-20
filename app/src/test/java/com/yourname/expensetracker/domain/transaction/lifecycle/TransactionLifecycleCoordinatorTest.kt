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
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
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
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
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
        diagnosticEventWriter = mockk(relaxed = true)

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
            diagnosticEventWriter = diagnosticEventWriter
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
        // RP-11 FIX 2: updateType's collision preflight uses the
        // blocking-consistent findBlockingDuplicateIdCurrencyAware.
        coEvery { expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
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

    // ── P2-001: delete missing-row semantics ─────────────────────────────────

    @Test
    fun `deleteExpense present row succeeds with event and planner dispatch`() = runTest(timeout = 60.seconds) {
        val expense = Expense(
            id = 1L, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(1L) } returns expense
        coEvery { planner.planDeleted(1L, any(), any()) } returns nonEmptyBatch()

        val result = coordinator.deleteExpense(expense)

        assertTrue("Expected success, got $result", result.isSuccess)
        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planDeleted(1L, any(), any()) }
        coVerify(exactly = 1) { expenseDao.delete(any()) }
    }

    @Test
    fun `deleteExpense missing row returns typed failure with no event and no planner call`() = runTest(timeout = 60.seconds) {
        val expense = Expense(
            id = 404L, amount = 10.0, merchant = "Test",
            transactionType = TransactionType.PURCHASE, date = now,
            currency = "EUR", dedupeKey = "old-dk", merchantKey = "mk"
        )
        coEvery { expenseDao.getById(404L) } returns null

        val result = coordinator.deleteExpense(expense)

        assertTrue("Expected failure, got $result", result.isFailure)
        assertTrue(
            "Expected IllegalArgumentException cause, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalArgumentException
        )
        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planDeleted(any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.delete(any()) }
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
        // RP-11 FIX 2: updateType's collision preflight uses the
        // blocking-consistent findBlockingDuplicateIdCurrencyAware.
        coEvery { expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery { planner.planUpdated(any(), any(), any(), TransactionUpdateKind.TYPE) } returns nonEmptyBatch()
        coEvery { runner.run(any()) } throws RuntimeException("Best-effort failure")

        coordinator.updateType(expenseId, TransactionType.TRANSFER)

        coVerify(exactly = 1) { expenseDao.updateTransactionType(expenseId, TransactionType.TRANSFER.name, any()) }
    }

    // ── NEW-P2-016 (11c): home-currency resolution fails closed ─────────────

    private fun request(currency: String = "EUR") = CreateExpenseRequest(
        merchant = "Test",
        amount = 10.0,
        currency = currency,
        date = now,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.MANUAL_ENTRY
    )

    /**
     * NEW-P2-016: a Failed home-currency resolution (DataStore error/timeout)
     * must complete bounded, never invent a currency, and persist the expense
     * with the existing sentinel conversion fields — never a fabricated base.
     */
    @Test
    fun `createExpense with failed home currency resolution marks conversion fields unavailable`() =
        runTest(timeout = 60.seconds) {
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Failed("Timeout reading home currency (5s)")
            val insertedSlot = slot<Expense>()
            coEvery { expenseDao.insertAtomic(capture(insertedSlot)) } returns 42L

            val result = coordinator.createExpense(request(currency = "USD"))

            // The create itself completes bounded with a controlled outcome.
            assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
            // No invented currency / fabricated base snapshot.
            val inserted = insertedSlot.captured
            assertTrue(
                "baseAmount must be sentinel 0.0, was ${inserted.baseAmount}",
                inserted.baseAmount == 0.0
            )
            assertTrue(
                "baseCurrency must not invent a currency, was '${inserted.baseCurrency}'",
                inserted.baseCurrency.isEmpty()
            )
            assertTrue(
                "exchangeRateUsed must be sentinel 0.0, was ${inserted.exchangeRateUsed}",
                inserted.exchangeRateUsed == 0.0
            )
            // No conversion was attempted against a substituted home currency.
            coVerify(exactly = 0) {
                currencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
            }
            // Exactly one bounded diagnostic with the controlled constant.
            coVerify(exactly = 1) {
                diagnosticEventWriter.emit(
                    match {
                        it.reasonCode == DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE &&
                            it.exception == null
                    }
                )
            }
            coVerify(exactly = 0) {
                diagnosticEventWriter.emit(match { it.reasonCode != DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE })
            }
        }

    /**
     * NEW-P2-016: a never-emitting home-currency flow must complete in bounded
     * time (the typed resolver contract's timeout path) with the same
     * fail-closed sentinel marking — never hang, never fabricate.
     */
    @Test
    fun `createExpense with never-emitting home currency settings completes bounded and fails closed`() =
        runTest(timeout = 60.seconds) {
            val neverEmittingCoordinator = TransactionLifecycleCoordinator(
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
                currencySettingsRepository = NeverEmittingHomeCurrencySettingsRepository(),
                sourceLinkWriter = mockk(relaxed = true),
                transactionValidator = transactionValidator,
                diagnosticEventWriter = diagnosticEventWriter
            )
            val insertedSlot = slot<Expense>()
            coEvery { expenseDao.insertAtomic(capture(insertedSlot)) } returns 42L

            val result = neverEmittingCoordinator.createExpense(request(currency = "USD"))

            assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
            val inserted = insertedSlot.captured
            assertTrue("baseAmount must be sentinel 0.0", inserted.baseAmount == 0.0)
            assertTrue("baseCurrency must be empty", inserted.baseCurrency.isEmpty())
            assertTrue("exchangeRateUsed must be sentinel 0.0", inserted.exchangeRateUsed == 0.0)
            coVerify(exactly = 1) {
                diagnosticEventWriter.emit(
                    match { it.reasonCode == DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE }
                )
            }
        }

    /**
     * NEW-P2-016 happy-path pin: Resolved home currency converts a foreign
     * currency row with the resolved home as the base snapshot.
     */
    @Test
    fun `createExpense with resolved home currency pins converted base snapshot`() =
        runTest(timeout = 60.seconds) {
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            coEvery {
                currencyConverter.convertAsOf(any<Double>(), "USD", "EUR", any<Long>())
            } returns com.yourname.expensetracker.domain.currency.ConversionResult(
                originalAmount = 10.0,
                originalCurrency = "USD",
                convertedAmount = 9.2,
                targetCurrency = "EUR",
                rateUsed = 0.92,
                timestamp = now
            )
            val insertedSlot = slot<Expense>()
            coEvery { expenseDao.insertAtomic(capture(insertedSlot)) } returns 42L

            val result = coordinator.createExpense(request(currency = "USD"))

            assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
            val inserted = insertedSlot.captured
            assertTrue("baseAmount should be converted", inserted.baseAmount == 9.2)
            assertTrue("baseCurrency should be the resolved home", inserted.baseCurrency == "EUR")
            assertTrue("rateUsed should be pinned", inserted.exchangeRateUsed == 0.92)
            coVerify(exactly = 0) { diagnosticEventWriter.emit(any()) }
        }

    /**
     * NEW-P2-016 happy-path pin: FirstRunDefault (no setting stored yet)
     * proceeds exactly like Resolved — same-currency row keeps its identity
     * base snapshot without touching the converter.
     */
    @Test
    fun `createExpense with first-run default home currency pins identity base snapshot`() =
        runTest(timeout = 60.seconds) {
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.FirstRunDefault(CurrencyCode("EUR"))
            val insertedSlot = slot<Expense>()
            coEvery { expenseDao.insertAtomic(capture(insertedSlot)) } returns 42L

            val result = coordinator.createExpense(request(currency = "EUR"))

            assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
            val inserted = insertedSlot.captured
            assertTrue("same-currency base amount is the amount", inserted.baseAmount == 10.0)
            assertTrue("baseCurrency is the first-run default", inserted.baseCurrency == "EUR")
            assertTrue("identity rate is 1.0", inserted.exchangeRateUsed == 1.0)
            coVerify(exactly = 0) {
                currencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
            }
            coVerify(exactly = 0) { diagnosticEventWriter.emit(any()) }
        }
}

/**
 * NEW-P2-016 test fake: settings repository whose [homeCurrency] flow never
 * emits and never completes. The interface's default [resolveHomeCurrency]
 * wraps the read in a bounded timeout, so the coordinator must complete
 * with a [HomeCurrencyResolution.Failed] rather than hanging.
 */
private class NeverEmittingHomeCurrencySettingsRepository : CurrencySettingsRepository {
    override fun homeCurrency(): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow { awaitCancellation() }

    override suspend fun setHomeCurrency(currencyCode: String) = Unit

    override fun lastRateUpdate(): kotlinx.coroutines.flow.Flow<Long> =
        kotlinx.coroutines.flow.flow { awaitCancellation() }

    override suspend fun setLastRateUpdate(timestamp: Long) = Unit

    override suspend fun areRatesStale(thresholdMs: Long): Boolean = false

    override fun emergencyBuffer(): kotlinx.coroutines.flow.Flow<Double> =
        kotlinx.coroutines.flow.flow { awaitCancellation() }

    override suspend fun setEmergencyBuffer(amount: Double) = Unit

    override suspend fun clear() = Unit
}

