package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
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
        // The scoped block opens a Room transaction; a relaxed database mock
        // would never execute it. withTransaction compiles to the TOP-LEVEL
        // static facade androidx.room.RoomDatabaseKt.withTransaction (Room
        // 2.7.2), so the stub only intercepts with mockkStatic — without it,
        // the real Room body runs against the relaxed AppDatabase mock and
        // suspends forever (same measured hang family as
        // TransactionLifecycleCoordinatorTest; see
        // docs/testing/test-sweep-outcomes-2026-09-18.md §Hangs). The recorded
        // call args are positional with the RECEIVER as arg 0 and the block as
        // arg 1, so the block is secondArg.
        mockkStatic("androidx.room.RoomDatabaseKt")
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

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
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
        assertTrue("baseAmount should be 0.0 after conversion failure, was ${persisted.baseAmount}", persisted.baseAmount == 0.0)
        assertTrue("baseCurrency should be empty after conversion failure, was '${persisted.baseCurrency}'", persisted.baseCurrency.isEmpty())
        assertTrue("exchangeRateUsed should be 0.0 after conversion failure, was ${persisted.exchangeRateUsed}", persisted.exchangeRateUsed == 0.0)
    }

    // ── P2-005: updateCategory ───────────────────────────────────────────────

    @Test
    fun `updateCategory changed writes event and dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense(categoryId = 1L)
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateCategory(1L, newCategoryId = 2L)

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planUpdated(1L, any(), any(), TransactionUpdateKind.CATEGORY_ONLY) }
    }

    @Test
    fun `updateCategory unchanged is silent no-op with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense(categoryId = 5L)

        coordinator.updateCategory(1L, newCategoryId = 5L)

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateCategoryNullable(any(), any()) }
    }

    @Test
    fun `updateCategory missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateCategory(1L, newCategoryId = 2L)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    // ── P2-005: updateLocation ───────────────────────────────────────────────

    @Test
    fun `updateLocation changed writes event and never dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateLocation(1L, latitude = 37.9, longitude = 23.7)

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        // Side effects intentionally skipped for location-only updates.
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun `updateLocation unchanged is silent no-op with no event`() = runTest(timeout = 60.seconds) {
        val located = expense().copy(
            latitude = 37.9, longitude = 23.7,
            placeId = "p1", resolvedAddress = "addr"
        )
        coEvery { expenseDao.getById(1L) } returns located

        coordinator.updateLocation(1L, latitude = 37.9, longitude = 23.7, placeId = "p1", resolvedAddress = "addr")

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.updateLocation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateLocation missing row fails typed with no event`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateLocation(1L, latitude = 37.9, longitude = 23.7)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
    }

    // ── P2-005: updateMerchant ───────────────────────────────────────────────

    @Test
    fun `updateMerchant changed writes event and dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        stubNoCollisions()
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateMerchant(1L, newMerchant = "New Merchant")

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planUpdated(1L, any(), any(), TransactionUpdateKind.MERCHANT) }
    }

    @Test
    fun `updateMerchant unchanged is silent no-op with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()

        coordinator.updateMerchant(1L, newMerchant = "Test")

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
    }

    @Test
    fun `updateMerchant missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateMerchant(1L, newMerchant = "New Merchant")
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun `updateMerchant collision throws DuplicateUpdateException and dispatches nothing`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        // 8 positional matchers (see stubNoCollisions note): collision found on
        // the blocking-consistent (not-mine-INCLUSIVE) collision lookup.
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 99L

        assertFailsWith<DuplicateUpdateException> {
            coordinator.updateMerchant(1L, newMerchant = "Colliding Merchant")
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    /**
     * RP-11 FIX 2: a not-mine row occupying the target identity must abort the
     * rename with a typed DuplicateUpdateException — NOT slip past the
     * preflight and die on the raw dedupeKey unique-index constraint.
     */
    @Test
    fun `updateMerchant collision against not-mine row throws DuplicateUpdateException`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        // Only the blocking-consistent lookup sees the not-mine row; the fuzzy
        // resolver keeps its isNotMine = 0 exclusion (relaxed default null).
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 99L

        assertFailsWith<DuplicateUpdateException> {
            coordinator.updateMerchant(1L, newMerchant = "NotMine Occupied Merchant")
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
    }

    // ── P2-005: updateType ───────────────────────────────────────────────────

    @Test
    fun `updateType changed writes event and dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        stubNoCollisions()
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateType(1L, newType = TransactionType.TRANSFER)

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planUpdated(1L, any(), any(), TransactionUpdateKind.TYPE) }
    }

    @Test
    fun `updateType unchanged is silent no-op with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()

        coordinator.updateType(1L, newType = TransactionType.PURCHASE)

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) }
    }

    @Test
    fun `updateType missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateType(1L, newType = TransactionType.TRANSFER)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    // ── P2-005: updateTransferDetails ────────────────────────────────────────

    @Test
    fun `updateTransferDetails changed writes event and dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1")

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planUpdated(1L, any(), null, TransactionUpdateKind.TRANSFER_DETAILS) }
    }

    @Test
    fun `updateTransferDetails unchanged is silent no-op with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        val withTransfer = expense().copy(
            transferDirection = TransferDirection.OUTGOING,
            transferAccountName = "Acc1"
        )
        coEvery { expenseDao.getById(1L) } returns withTransfer

        coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1")

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateTransferDirection(any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateTransferAccountName(any(), any()) }
    }

    @Test
    fun `updateTransferDetails missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateTransferDetails(1L, TransferDirection.OUTGOING, "Acc1")
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    // ── P2-005: updateTypeAndTransferDetails ─────────────────────────────────

    @Test
    fun `updateTypeAndTransferDetails changed writes event and dispatches`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns expense()
        stubNoCollisions()
        coEvery { transactionEventDao.insert(any()) } returns 1L

        coordinator.updateTypeAndTransferDetails(1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1")

        coVerify(exactly = 1) { transactionEventDao.insert(any()) }
        coVerify(exactly = 1) { planner.planUpdated(1L, any(), null, TransactionUpdateKind.FULL) }
    }

    @Test
    fun `updateTypeAndTransferDetails unchanged is silent no-op with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        val canonicalKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount = 50.0, merchant = "Test", date = now, currency = "EUR",
            transactionType = TransactionType.PURCHASE
        )
        val unchanged = expense(dedupeKey = canonicalKey).copy(
            transferDirection = TransferDirection.OUTGOING,
            transferAccountName = "Acc1"
        )
        coEvery { expenseDao.getById(1L) } returns unchanged

        coordinator.updateTypeAndTransferDetails(1L, TransactionType.PURCHASE, TransferDirection.OUTGOING, "Acc1")

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) }
    }

    @Test
    fun `updateTypeAndTransferDetails missing row fails typed with no event and no dispatch`() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getById(1L) } returns null

        assertFailsWith<IllegalArgumentException> {
            coordinator.updateTypeAndTransferDetails(1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1")
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    /**
     * P2-005: the dedupe-key collision check must run whenever the recomputed
     * key differs from the stored key — even if the transaction type itself is
     * unchanged (e.g. a legacy row whose stored key predates the type-suffixed
     * key format).
     */
    @Test
    fun `updateTypeAndTransferDetails stale key collision throws even when type unchanged`() = runTest(timeout = 60.seconds) {
        // Type unchanged (PURCHASE → PURCHASE) but stored key is stale/differs.
        val staleKeyRow = expense(dedupeKey = "stale-legacy-key")
        coEvery { expenseDao.getById(1L) } returns staleKeyRow
        // 8 positional matchers (see stubNoCollisions note): collision found on
        // the blocking-consistent (not-mine-INCLUSIVE) collision lookup.
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 99L

        assertFailsWith<DuplicateUpdateException> {
            coordinator.updateTypeAndTransferDetails(
                1L, TransactionType.PURCHASE, TransferDirection.OUTGOING, "Acc1"
            )
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) }
    }

    // ── P2-007 (11c): transfer edits carry caller correlation ────────────────

    /**
     * P2-007: one correlation id must appear in BOTH records — the UPDATED
     * lifecycle event and the planned post-commit batch.
     */
    @Test
    fun `updateTransferDetails correlation id appears in event and dispatch record`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getById(1L) } returns expense()
            val eventSlot = slot<TransactionEvent>()
            coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

            coordinator.updateTransferDetails(
                1L, TransferDirection.OUTGOING, "Acc1",
                correlationId = "corr-transfer-1"
            )

            assertEquals(
                "Caller correlation id must be recorded on the UPDATED event",
                "corr-transfer-1",
                eventSlot.captured.correlationId
            )
            coVerify(exactly = 1) {
                planner.planUpdated(1L, any(), "corr-transfer-1", TransactionUpdateKind.TRANSFER_DETAILS)
            }
        }

    @Test
    fun `updateTypeAndTransferDetails correlation id appears in event and dispatch record`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getById(1L) } returns expense()
            stubNoCollisions()
            val eventSlot = slot<TransactionEvent>()
            coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

            coordinator.updateTypeAndTransferDetails(
                1L, TransactionType.TRANSFER, TransferDirection.OUTGOING, "Acc1",
                correlationId = "corr-type-transfer-1"
            )

            assertEquals(
                "Caller correlation id must be recorded on the UPDATED event",
                "corr-type-transfer-1",
                eventSlot.captured.correlationId
            )
            coVerify(exactly = 1) {
                planner.planUpdated(1L, any(), "corr-type-transfer-1", TransactionUpdateKind.FULL)
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
            assertTrue(
                "baseAmount must be sentinel 0.0 after failed resolution, was ${persisted.baseAmount}",
                persisted.baseAmount == 0.0
            )
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
            assertEquals(75.0, persisted.baseAmount, 0.0)
            assertEquals("EUR", persisted.baseCurrency)
            assertEquals(1.0, persisted.exchangeRateUsed, 0.0)
        }
}
