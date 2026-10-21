package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * P2-PR1: Tests that currency conversion failure clears stale baseAmount.
 *
 * Fixes: NEW-P2-007
 *
 * RP-11 11a (P2-005): every targeted update method returns a typed outcome from
 * the transaction — [Updated] writes the lifecycle event and dispatches
 * post-commit work, [NoChange] is a silent idempotent no-op, [NotFound] is a
 * typed missing-row failure with no event and no dispatch.
 *
 * B6-16: the per-method changed / unchanged / missing / collision scenarios and
 * the CA-P-02-003 idem-key scenarios are tables. Every table row rebuilds ALL
 * mocks ([buildHarness]) so verify counts never leak between rows, and every row
 * failure is prefixed with the row name ([inRow]).
 */
class TransactionLifecycleCoordinatorUpdateTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var coordinator: TransactionLifecycleCoordinator

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        // The scoped block opens a Room transaction; a relaxed database mock
        // would never execute it. withTransaction compiles to the TOP-LEVEL
        // static facade androidx.room.RoomDatabaseKt.withTransaction (Room
        // 2.7.2), so the stub only intercepts with mockkStatic — without it,
        // the real Room body runs against the relaxed AppDatabase mock and
        // suspends forever (same measured hang family as
        // TransactionLifecycleCoordinatorTest; see
        // docs/testing/test-sweep-outcomes-2026-09-18.md §Hangs). The recorded
        // call args are positional with the RECEIVER as arg 0 and the block as
        // arg 1, so the block is secondArg (stubbed per database in buildHarness).
        mockkStatic("androidx.room.RoomDatabaseKt")
        buildHarness()
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    /**
     * Builds a fresh set of mocks and a fresh coordinator. Called from [setup]
     * and again at the start of every table row.
     */
    private fun buildHarness() {
        database = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        transactionEventDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        currencyConverter = mockk(relaxed = true)
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
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        currencySettingsRepository = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        planner = mockk(relaxed = true)
        runner = mockk(relaxed = true)
        coEvery { planner.planUpdated(any(), any(), any(), any()) } returns PostCommitActionBatch.empty("test")

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = diagnosticEventWriter
        )
    }

    /** Prefixes any row failure with the row name; cancellation is never swallowed or rewrapped. */
    private inline fun <T> inRow(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError("[$name] ${e.message}", e)
        }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun expense(
        id: Long = 1L,
        categoryId: Long? = 1L,
        dedupeKey: String? = "test-key"
    ) = Expense(
        id = id,
        amount = 50.0,
        currency = "EUR",
        merchant = "Test",
        merchantKey = "test",
        transactionType = TransactionType.PURCHASE,
        date = now,
        categoryId = categoryId,
        dedupeKey = dedupeKey
    )

    private fun stubNoCollisions() {
        // 8 positional matchers: the DAO method has 8 params (windowMs has a
        // default in the middle) — a 7-any() stub misses the recorded call.
        // RP-11 FIX 2: collision preflights use the blocking-consistent
        // findBlockingDuplicateIdCurrencyAware (not-mine-INCLUSIVE), so stub
        // BOTH families — the resolver stub keeps create-path tests working.
        coEvery {
            expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
    }

    private fun stubBlockingCollision() {
        // 8 positional matchers (see stubNoCollisions note): collision found on
        // the blocking-consistent (not-mine-INCLUSIVE) collision lookup.
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 99L
    }

    // ── NEW-P2-007 (pre-existing): conversion failure clears stale baseAmount ──

    /**
     * NEW-P2-007: When currency conversion fails during updateExpense,
     * stale baseAmount/baseCurrency/exchangeRateUsed must be cleared to null.
     */
    @Test
    fun `updateExpense clears baseAmount when conversion fails`() = runTest(timeout = 60.seconds) {
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

        // Conversion fails. updateExpense calls the String-typed convertAsOf
        // overload (expense.currency is String; homeCurrency() returns "EUR"),
        // NOT the CurrencyCode convenience overload — stubbing that one leaves
        // the String overload relaxed, which fabricates a zero ConversionResult
        // and the coordinator takes the success branch instead of clearing.
        coEvery {
            currencyConverter.convertAsOf(any<Double>(), "USD", "EUR", any<Long>())
        } throws RuntimeException("Network error")

        // Capture the expense that gets persisted
        val updatedSlot = slot<Expense>()
        coEvery { expenseDao.update(capture(updatedSlot)) } returns Unit
        coEvery { transactionEventDao.insert(any()) } returns 1L

        // Update with same currency (USD) but different amount
        val updatedExpense = existingExpense.copy(amount = 75.0)
        coordinator.updateExpense(updatedExpense, reason = "amount change")

        // Verify baseAmount was cleared (not left as stale 45.0)
        val persisted = updatedSlot.captured
        assertMoneyExact(0.0, persisted.baseAmount, "baseAmount should be 0.0 after conversion failure")
        assertTrue("baseCurrency should be empty after conversion failure, was '${persisted.baseCurrency}'", persisted.baseCurrency.isEmpty())
        assertTrue("exchangeRateUsed should be 0.0 after conversion failure, was ${persisted.exchangeRateUsed}", persisted.exchangeRateUsed == 0.0)
    }

    // ── P2-005: changed -> writes exactly one event; dispatches per kind ─────

    /**
     * One row per targeted update method (plus P2-007 correlation carry-through
     * for the transfer edits). [planKind] == null means the method intentionally
     * never dispatches post-commit work (location edits).
     */
    private class ChangedRow(
        val name: String,
        val barrierOperation: String,
        val existing: Expense,
        val stubCollisions: Boolean,
        val planKind: TransactionUpdateKind?,
        /** When false the planned correlation id is not asserted (any value). */
        val assertPlanCorrelation: Boolean,
        val planCorrelation: String?,
        /** null = the event correlation id is not asserted for this row. */
        val eventCorrelation: String?,
        val verifyWrite: suspend () -> Unit = {},
        val call: suspend () -> Unit
    )

    @Test
    fun `P2-005 changed update writes exactly one event and plans per kind with caller correlation`() =
        runTest(timeout = 60.seconds) {
            val rows = listOf(
                ChangedRow(
                    name = "updateCategory",
                    barrierOperation = "TransactionLifecycleCoordinator.updateCategory",
                    existing = expense(categoryId = 1L),
                    stubCollisions = false,
                    planKind = TransactionUpdateKind.CATEGORY_ONLY,
                    assertPlanCorrelation = false, planCorrelation = null, eventCorrelation = null,
                    call = { coordinator.updateCategory(1L, newCategoryId = 2L) }
                ),
                ChangedRow(
                    name = "updateMerchant",
                    barrierOperation = "TransactionLifecycleCoordinator.updateMerchant",
                    existing = expense(),
                    stubCollisions = true,
                    planKind = TransactionUpdateKind.MERCHANT,
                    assertPlanCorrelation = false, planCorrelation = null, eventCorrelation = null,
                    call = { coordinator.updateMerchant(1L, newMerchant = "New Merchant") }
                ),
                ChangedRow(
                    name = "updateType",
                    barrierOperation = "TransactionLifecycleCoordinator.updateType",
                    existing = expense(),
                    stubCollisions = true,
                    planKind = TransactionUpdateKind.TYPE,
                    assertPlanCorrelation = false, planCorrelation = null, eventCorrelation = null,
                    call = { coordinator.updateType(1L, newType = TransactionType.TRANSFER) }
                ),
                ChangedRow(
                    name = "updateTransferDetails without correlation id plans null correlation",
                    barrierOperation = "TransactionLifecycleCoordinator.updateTransferDetails",
                    existing = expense(),
                    stubCollisions = false,
                    planKind = TransactionUpdateKind.TRANSFER_DETAILS,
                    assertPlanCorrelation = true, planCorrelation = null, eventCorrelation = null,
                    call = { coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1") }
                ),
                ChangedRow(
                    name = "updateTypeAndTransferDetails without correlation id plans null correlation",
                    barrierOperation = "TransactionLifecycleCoordinator.updateTypeAndTransferDetails",
                    existing = expense(),
                    stubCollisions = true,
                    planKind = TransactionUpdateKind.FULL,
                    assertPlanCorrelation = true, planCorrelation = null, eventCorrelation = null,
                    call = {
                        coordinator.updateTypeAndTransferDetails(
                            1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1"
                        )
                    }
                ),
                // P2-007: one correlation id must appear in BOTH records — the UPDATED
                // lifecycle event and the planned post-commit batch.
                ChangedRow(
                    name = "updateTransferDetails correlation id appears in event and dispatch record",
                    barrierOperation = "TransactionLifecycleCoordinator.updateTransferDetails",
                    existing = expense(),
                    stubCollisions = false,
                    planKind = TransactionUpdateKind.TRANSFER_DETAILS,
                    assertPlanCorrelation = true, planCorrelation = "corr-transfer-1",
                    eventCorrelation = "corr-transfer-1",
                    call = {
                        coordinator.updateTransferDetails(
                            1L, TransferDirection.OUTGOING, "Acc1",
                            correlationId = "corr-transfer-1"
                        )
                    }
                ),
                ChangedRow(
                    name = "updateTypeAndTransferDetails correlation id appears in event and dispatch record",
                    barrierOperation = "TransactionLifecycleCoordinator.updateTypeAndTransferDetails",
                    existing = expense(),
                    stubCollisions = true,
                    planKind = TransactionUpdateKind.FULL,
                    assertPlanCorrelation = true, planCorrelation = "corr-type-transfer-1",
                    eventCorrelation = "corr-type-transfer-1",
                    call = {
                        coordinator.updateTypeAndTransferDetails(
                            1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1",
                            correlationId = "corr-type-transfer-1"
                        )
                    }
                ),
                // Side effects intentionally skipped for location-only updates.
                ChangedRow(
                    name = "updateLocation writes event and never dispatches",
                    barrierOperation = "TransactionLifecycleCoordinator.updateLocation",
                    existing = expense(),
                    stubCollisions = false,
                    planKind = null,
                    assertPlanCorrelation = false, planCorrelation = null, eventCorrelation = null,
                    call = { coordinator.updateLocation(1L, latitude = 37.9, longitude = 23.7) }
                ),
                ChangedRow(
                    name = "clearLocation resets backfill attempts even when location fields are empty",
                    barrierOperation = "TransactionLifecycleCoordinator.clearLocation",
                    existing = expense().copy(backfillAttempts = 2),
                    stubCollisions = false,
                    planKind = null,
                    assertPlanCorrelation = false, planCorrelation = null, eventCorrelation = null,
                    verifyWrite = { coVerify(exactly = 1) { expenseDao.clearLocation(1L) } },
                    call = { coordinator.clearLocation(1L) }
                )
            )
            assertEquals("changed matrix size guard", 9, rows.size)

            for (row in rows) {
                buildHarness()
                inRow(row.name) {
                    coEvery { expenseDao.getById(1L) } returns row.existing
                    if (row.stubCollisions) stubNoCollisions()
                    val eventSlot = slot<TransactionEvent>()
                    coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

                    row.call()

                    verify(exactly = 1) { writeBarrier.checkWritesAllowed(row.barrierOperation) }
                    coVerify(exactly = 1) { transactionEventDao.insert(any()) }
                    row.verifyWrite()
                    val kind = row.planKind
                    if (kind == null) {
                        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
                    } else if (row.assertPlanCorrelation) {
                        coVerify(exactly = 1) { planner.planUpdated(1L, any(), row.planCorrelation, kind) }
                        coVerify(exactly = 1) { planner.planUpdated(any(), any(), any(), any()) }
                    } else {
                        coVerify(exactly = 1) { planner.planUpdated(1L, any(), any(), kind) }
                        coVerify(exactly = 1) { planner.planUpdated(any(), any(), any(), any()) }
                    }
                    val expectedEventCorrelation = row.eventCorrelation
                    if (expectedEventCorrelation != null) {
                        assertEquals(
                            "Caller correlation id must be recorded on the UPDATED event",
                            expectedEventCorrelation,
                            eventSlot.captured.correlationId
                        )
                    }
                }
            }
        }

    @Test
    fun `clearLocation changed writes auditable event and clears columns`() = runTest(timeout = 60.seconds) {
        val located = expense().copy(
            latitude = 37.9,
            longitude = 23.7,
            locationSource = "USER_MANUAL",
            placeId = "p1",
            resolvedAddress = "addr"
        )
        val eventSlot = slot<TransactionEvent>()
        coEvery { expenseDao.getById(1L) } returns located
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

        coordinator.clearLocation(
            expenseId = 1L,
            source = "USER_MANUAL",
            reason = "User cleared location",
            correlationId = "corr-1"
        )

        coVerify(exactly = 1) { expenseDao.clearLocation(1L) }
        assertEquals(LifecycleEventType.UPDATED.name, eventSlot.captured.eventType)
        assertEquals("USER_MANUAL", eventSlot.captured.source)
        assertEquals("User cleared location", eventSlot.captured.reason)
        assertEquals("corr-1", eventSlot.captured.correlationId)
        assertTrue(eventSlot.captured.beforeSnapshot != null)
        assertTrue(eventSlot.captured.afterSnapshot != null)
    }

    // ── P2-005: unchanged -> silent no-op (no event, no dispatch, no DAO write) ──

    private class NoChangeRow(
        val name: String,
        /** Coordinator method name; the write barrier is checked as "TransactionLifecycleCoordinator.<method>". */
        val method: String,
        val existing: Expense,
        val verifyNoWrite: suspend () -> Unit,
        val call: suspend () -> Unit
    )

    @Test
    fun `P2-005 unchanged update is silent no-op with no event no dispatch and no DAO write`() =
        runTest(timeout = 60.seconds) {
            val located = expense().copy(
                latitude = 37.9, longitude = 23.7,
                placeId = "p1", resolvedAddress = "addr"
            )
            val withTransfer = expense().copy(
                transferDirection = TransferDirection.OUTGOING,
                transferAccountName = "Acc1"
            )
            val canonicalKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount = 50.0, merchant = "Test", date = now, currency = "EUR",
                transactionType = TransactionType.PURCHASE
            )
            val unchangedTypeAndTransfer = expense(dedupeKey = canonicalKey).copy(
                transferDirection = TransferDirection.OUTGOING,
                transferAccountName = "Acc1"
            )
            val rows = listOf(
                NoChangeRow(
                    name = "updateCategory same category",
                    method = "updateCategory",
                    existing = expense(categoryId = 5L),
                    verifyNoWrite = { coVerify(exactly = 0) { expenseDao.updateCategoryNullable(any(), any()) } },
                    call = { coordinator.updateCategory(1L, newCategoryId = 5L) }
                ),
                NoChangeRow(
                    name = "updateLocation same coordinates and place",
                    method = "updateLocation",
                    existing = located,
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateLocation(any(), any(), any(), any(), any(), any()) }
                    },
                    call = {
                        coordinator.updateLocation(
                            1L, latitude = 37.9, longitude = 23.7, placeId = "p1", resolvedAddress = "addr"
                        )
                    }
                ),
                NoChangeRow(
                    name = "clearLocation already clear",
                    method = "clearLocation",
                    existing = expense(),
                    verifyNoWrite = { coVerify(exactly = 0) { expenseDao.clearLocation(any()) } },
                    call = { coordinator.clearLocation(1L) }
                ),
                NoChangeRow(
                    name = "updateMerchant same merchant",
                    method = "updateMerchant",
                    existing = expense(),
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
                    },
                    call = { coordinator.updateMerchant(1L, newMerchant = "Test") }
                ),
                NoChangeRow(
                    name = "updateType same type",
                    method = "updateType",
                    existing = expense(),
                    verifyNoWrite = { coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) } },
                    call = { coordinator.updateType(1L, newType = TransactionType.PURCHASE) }
                ),
                NoChangeRow(
                    name = "updateTransferDetails same direction and account",
                    method = "updateTransferDetails",
                    existing = withTransfer,
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateTransferDirection(any(), any()) }
                        coVerify(exactly = 0) { expenseDao.updateTransferAccountName(any(), any()) }
                    },
                    call = { coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1") }
                ),
                NoChangeRow(
                    name = "updateTypeAndTransferDetails same type, transfer and canonical key",
                    method = "updateTypeAndTransferDetails",
                    existing = unchangedTypeAndTransfer,
                    verifyNoWrite = { coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) } },
                    call = {
                        coordinator.updateTypeAndTransferDetails(
                            1L, TransactionType.PURCHASE, TransferDirection.OUTGOING, "Acc1"
                        )
                    }
                )
            )
            assertEquals("no-change matrix size guard", 7, rows.size)

            for (row in rows) {
                buildHarness()
                inRow(row.name) {
                    coEvery { expenseDao.getById(1L) } returns row.existing

                    row.call()

                    // B6 nit (47): the no-op path still goes through the write barrier exactly once.
                    verify(exactly = 1) { writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.${row.method}") }
                    coVerify(exactly = 0) { transactionEventDao.insert(any()) }
                    coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
                    row.verifyNoWrite()
                }
            }
        }

    // ── P2-005: missing row -> typed IllegalArgumentException ────────────────

    private class MissingRow(
        val name: String,
        val verifyNoWrite: suspend () -> Unit = {},
        val call: suspend () -> Unit
    )

    @Test
    fun `P2-005 missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        val rows = listOf(
            MissingRow(
                name = "updateCategory",
                call = { coordinator.updateCategory(1L, newCategoryId = 2L) }
            ),
            MissingRow(
                name = "updateLocation",
                call = { coordinator.updateLocation(1L, latitude = 37.9, longitude = 23.7) }
            ),
            MissingRow(
                name = "clearLocation",
                verifyNoWrite = { coVerify(exactly = 0) { expenseDao.clearLocation(any()) } },
                call = { coordinator.clearLocation(1L) }
            ),
            MissingRow(
                name = "updateMerchant",
                call = { coordinator.updateMerchant(1L, newMerchant = "New Merchant") }
            ),
            MissingRow(
                name = "updateType",
                call = { coordinator.updateType(1L, newType = TransactionType.TRANSFER) }
            ),
            MissingRow(
                name = "updateTransferDetails",
                call = { coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1") }
            ),
            MissingRow(
                name = "updateTypeAndTransferDetails",
                call = {
                    coordinator.updateTypeAndTransferDetails(
                        1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1"
                    )
                }
            )
        )
        assertEquals("missing-row matrix size guard", 7, rows.size)

        for (row in rows) {
            buildHarness()
            inRow(row.name) {
                coEvery { expenseDao.getById(1L) } returns null

                assertFailsWith<IllegalArgumentException> { row.call() }

                // B6 nit (47): the barrier is checked once even when the row is missing
                // (row.name is the coordinator method name for every MissingRow).
                verify(exactly = 1) { writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.${row.name}") }
                coVerify(exactly = 0) { transactionEventDao.insert(any()) }
                coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
                row.verifyNoWrite()
            }
        }
    }

    // ── P2-005 / RP-11 FIX 2: collisions abort with a typed exception ────────

    private class CollisionRow(
        val name: String,
        val existing: Expense,
        val verifyNoWrite: suspend () -> Unit,
        val call: suspend () -> Unit
    )

    /**
     * RP-11 FIX 2: a not-mine row occupying the target identity must abort the
     * rename with a typed DuplicateUpdateException — NOT slip past the
     * preflight and die on the raw dedupeKey unique-index constraint. Only the
     * blocking-consistent lookup sees the row (the fuzzy resolver keeps its
     * isNotMine = 0 exclusion, relaxed default null).
     *
     * P2-005: the dedupe-key collision check must run whenever the recomputed
     * key differs from the stored key — even if the transaction type itself is
     * unchanged (e.g. a legacy row whose stored key predates the type-suffixed
     * key format).
     */
    @Test
    fun `P2-005 collisions throw DuplicateUpdateException with no event no dispatch and no DAO write`() =
        runTest(timeout = 60.seconds) {
            val rows = listOf(
                CollisionRow(
                    name = "updateMerchant collision throws DuplicateUpdateException and dispatches nothing",
                    existing = expense(),
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
                    },
                    call = { coordinator.updateMerchant(1L, newMerchant = "Colliding Merchant") }
                ),
                CollisionRow(
                    name = "updateMerchant collision against not-mine row throws DuplicateUpdateException",
                    existing = expense(),
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
                    },
                    call = { coordinator.updateMerchant(1L, newMerchant = "NotMine Occupied Merchant") }
                ),
                CollisionRow(
                    // Type unchanged (PURCHASE -> PURCHASE) but stored key is stale/differs.
                    name = "updateTypeAndTransferDetails stale key collision throws even when type unchanged",
                    existing = expense(dedupeKey = "stale-legacy-key"),
                    verifyNoWrite = {
                        coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) }
                    },
                    call = {
                        coordinator.updateTypeAndTransferDetails(
                            1L, TransactionType.PURCHASE, TransferDirection.OUTGOING, "Acc1"
                        )
                    }
                )
            )
            assertEquals("collision matrix size guard", 3, rows.size)

            for (row in rows) {
                buildHarness()
                inRow(row.name) {
                    coEvery { expenseDao.getById(1L) } returns row.existing
                    stubBlockingCollision()

                    assertFailsWith<DuplicateUpdateException> { row.call() }

                    coVerify(exactly = 0) { transactionEventDao.insert(any()) }
                    coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
                    row.verifyNoWrite()
                }
            }
        }

    // ── CA-P-02-003: STRICT_EXTERNAL_ID `idem:` keys survive key-field edits ──

    private val idemKey = "idem:NOTIFICATION_AUTO_ACCEPT:ext-123"

    private class UpdateExpenseKeyRow(
        val name: String,
        val existing: Expense,
        val edited: Expense,
        val expectedDedupeKey: String?
    )

    @Test
    fun `CA-P-02-003 updateExpense key-field edit preserves idem key and recomputes content key`() =
        runTest(timeout = 60.seconds) {
            val rows = listOf(
                UpdateExpenseKeyRow(
                    name = "idem key is preserved",
                    existing = expense(dedupeKey = idemKey),
                    edited = expense(dedupeKey = idemKey).copy(amount = 75.0, merchant = "Other"),
                    expectedDedupeKey = idemKey
                ),
                UpdateExpenseKeyRow(
                    name = "content key is recomputed",
                    existing = expense(),
                    edited = expense().copy(amount = 75.0),
                    expectedDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                        75.0, "Test", now, "EUR", TransactionType.PURCHASE
                    )
                )
            )
            assertEquals("updateExpense key matrix size guard", 2, rows.size)

            for (row in rows) {
                buildHarness()
                inRow(row.name) {
                    coEvery { expenseDao.getById(1L) } returns row.existing
                    stubNoCollisions()
                    val updatedSlot = slot<Expense>()
                    coEvery { expenseDao.update(capture(updatedSlot)) } returns Unit
                    coEvery { transactionEventDao.insert(any()) } returns 1L

                    coordinator.updateExpense(row.edited)

                    assertEquals(row.expectedDedupeKey, updatedSlot.captured.dedupeKey)
                }
            }
        }

    private class IdemDaoRow(
        val name: String,
        val call: suspend () -> Unit,
        val verifyWrite: suspend () -> Unit
    )

    @Test
    fun `CA-P-02-003 updateMerchant and updateType pass the preserved idem key to the DAO`() =
        runTest(timeout = 60.seconds) {
            val rows = listOf(
                IdemDaoRow(
                    name = "updateMerchant",
                    call = { coordinator.updateMerchant(1L, newMerchant = "New Merchant") },
                    verifyWrite = {
                        coVerify(exactly = 1) { expenseDao.updateMerchantAndKey(1L, "New Merchant", any(), idemKey) }
                    }
                ),
                IdemDaoRow(
                    name = "updateType",
                    call = { coordinator.updateType(1L, newType = TransactionType.TRANSFER) },
                    verifyWrite = {
                        coVerify(exactly = 1) {
                            expenseDao.updateTransactionType(1L, TransactionType.TRANSFER.name, idemKey)
                        }
                    }
                )
            )
            assertEquals("idem DAO matrix size guard", 2, rows.size)

            for (row in rows) {
                buildHarness()
                inRow(row.name) {
                    coEvery { expenseDao.getById(1L) } returns expense(dedupeKey = idemKey)
                    stubNoCollisions()
                    coEvery { transactionEventDao.insert(any()) } returns 1L

                    row.call()

                    row.verifyWrite()
                }
            }
        }

    @Test
    fun `CA-P-02-003 updateTypeAndTransferDetails type change on idem row is not a no-op`() =
        runTest(timeout = 60.seconds) {
            val row = expense(dedupeKey = idemKey).copy(
                transferDirection = TransferDirection.OUTGOING,
                transferAccountName = "Acc1"
            )
            coEvery { expenseDao.getById(1L) } returns row
            stubNoCollisions()
            coEvery { transactionEventDao.insert(any()) } returns 1L

            // Key is preserved, transfer metadata unchanged — only the type moves.
            coordinator.updateTypeAndTransferDetails(1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1")

            coVerify(exactly = 1) {
                expenseDao.findBlockingDuplicateIdCurrencyAware(
                    any(), any(), any(), any(), TransactionType.TRANSFER.name, any(), any(), any()
                )
            }
            coVerify(exactly = 1) { transactionEventDao.insert(any()) }
            coVerify(exactly = 1) { planner.planUpdated(1L, any(), null, TransactionUpdateKind.FULL) }
        }

    @Test
    fun `CA-P-02-003 bulkUpdateMerchant preserves idem key and recomputes content key`() =
        runTest(timeout = 60.seconds) {
            val idemRow = expense(id = 1L, dedupeKey = idemKey)
            val contentRow = expense(id = 2L)
            coEvery { expenseDao.getExpensesByMerchantKey(any()) } returns listOf(idemRow, contentRow)
            stubNoCollisions()
            coEvery { transactionEventDao.insert(any()) } returns 1L

            val result = coordinator.bulkUpdateMerchant("Test", "Renamed")

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { expenseDao.updateMerchantAndKey(1L, "Renamed", any(), idemKey) }
            coVerify(exactly = 1) {
                expenseDao.updateMerchantAndKey(
                    2L, "Renamed", any(),
                    DuplicateDetectionPolicy.generateDedupeKeyWithType(
                        50.0, "Renamed", now, "EUR", TransactionType.PURCHASE
                    )
                )
            }
        }

    // ── NEW-P2-016 (11c): home-currency resolution fails closed ─────────────

    /**
     * NEW-P2-016: Failed home-currency resolution (DataStore error/timeout)
     * must complete bounded, never invent a currency, and mark the conversion
     * fields with the existing sentinels instead of a fabricated base snapshot.
     */
    @Test
    fun `updateExpense with failed home currency resolution marks conversion fields unavailable`() =
        runTest(timeout = 60.seconds) {
            // Stale conversion snapshot from a previous successful conversion.
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
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Failed("Timeout reading home currency (5s)")
            // Amount changes → key changes → duplicate precheck runs (relaxed =
            // false); conversion must NEVER be attempted with an invented home.
            val updatedSlot = slot<Expense>()
            coEvery { expenseDao.update(capture(updatedSlot)) } returns Unit
            coEvery { transactionEventDao.insert(any()) } returns 1L

            coordinator.updateExpense(existingExpense.copy(amount = 75.0), reason = "amount change")

            // The update itself completed (bounded, non-blocking).
            val persisted = updatedSlot.captured
            assertMoneyExact(0.0, persisted.baseAmount, "baseAmount must be sentinel 0.0 after failed resolution")
            assertTrue(
                "baseCurrency must not invent a currency, was '${persisted.baseCurrency}'",
                persisted.baseCurrency.isEmpty()
            )
            assertTrue(
                "exchangeRateUsed must be sentinel 0.0, was ${persisted.exchangeRateUsed}",
                persisted.exchangeRateUsed == 0.0
            )
            // No conversion was fabricated against a substituted home currency.
            coVerify(exactly = 0) {
                currencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
            }
            // Exactly one bounded diagnostic with the controlled constant.
            coVerify(exactly = 1) {
                diagnosticEventWriter.emit(
                    match {
                        it.reasonCode == DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE &&
                            it.entityId == 1L &&
                            it.exception == null
                    }
                )
            }
            coVerify(exactly = 0) {
                diagnosticEventWriter.emit(match { it.reasonCode != DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE })
            }
        }

    /**
     * NEW-P2-016 happy-path pin: FirstRunDefault (no setting stored yet)
     * proceeds exactly like Resolved — same-currency row keeps its identity
     * base snapshot.
     */
    @Test
    fun `updateExpense with first-run default home currency pins identity base fields`() =
        runTest(timeout = 60.seconds) {
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.FirstRunDefault(CurrencyCode("EUR"))
            coEvery { expenseDao.getById(1L) } returns expense()
            val updatedSlot = slot<Expense>()
            coEvery { expenseDao.update(capture(updatedSlot)) } returns Unit
            coEvery { transactionEventDao.insert(any()) } returns 1L

            coordinator.updateExpense(expense().copy(amount = 75.0))

            val persisted = updatedSlot.captured
            assertMoneyExact(75.0, persisted.baseAmount, "same-currency base amount is the pass-through 75.0")
            assertEquals("EUR", persisted.baseCurrency)
            assertEquals(1.0, persisted.exchangeRateUsed, 0.0)
        }
}
