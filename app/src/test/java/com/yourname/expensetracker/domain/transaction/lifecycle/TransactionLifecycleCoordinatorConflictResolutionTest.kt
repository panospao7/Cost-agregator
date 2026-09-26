package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
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
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.lifecycle.DuplicateUpdateException
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.InsertConflictCodes
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
import org.junit.Assert.assertNull
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * RP-11 11b: coordinator-side contract tests for insert-conflict resolution
 * (P2-002), the preflight-only dedup bypass contract (P2-004), and the atomic
 * all-or-nothing bulk merchant rename (P2-003).
 *
 * Pipeline-side expectations (NotificationProcessingPipeline InsertConflict
 * terminal transition) are pinned indirectly here through the typed
 * [CreateExpenseResult.InsertConflict] reason codes — the pipeline suites
 * themselves are known RP-21 hang suspects and are intentionally not edited.
 */
class TransactionLifecycleCoordinatorConflictResolutionTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner
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
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        currencySettingsRepository = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        planner = mockk(relaxed = true)
        runner = mockk(relaxed = true)
        coEvery { planner.planUpdated(any(), any(), any(), any()) } returns PostCommitActionBatch.empty("test")
        coEvery { planner.planCreated(any(), any(), any()) } returns PostCommitActionBatch.empty("test")

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
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun request(
        rawNotificationId: Long? = null,
        skipPreflight: Boolean = false,
        deduplicationMode: DeduplicationMode = DeduplicationMode.STANDARD,
        idempotencyKey: String? = null
    ) = CreateExpenseRequest(
        merchant = "Test",
        amount = 10.0,
        currency = "EUR",
        date = now,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT,
        rawNotificationId = rawNotificationId,
        deduplicationMode = deduplicationMode,
        skipPreflightDeduplication = skipPreflight,
        idempotencyKey = idempotencyKey
    )

    /** Dedupe key the coordinator derives for a STRICT request carrying "test-key". */
    private fun strictKey(): String = "idem:NOTIFICATION_AUTO_ACCEPT:test-key"

    private fun expense(rawNotificationId: Long? = null, dedupeKey: String? = canonicalKey()) = Expense(
        id = 0L,
        amount = 10.0,
        currency = "EUR",
        merchant = "Test",
        merchantKey = "test",
        transactionType = TransactionType.PURCHASE,
        date = now,
        rawNotificationId = rawNotificationId,
        dedupeKey = dedupeKey
    )

    /**
     * The coordinator rebuilds the dedupe key from the canonical policy
     * (TransactionLifecycleCoordinator.kt step 3) — the request fixture's fields
     * map to this exact key, so resolver stubs and capture assertions must use it.
     */
    private fun canonicalKey(): String =
        com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
            .generateDedupeKeyWithType(
                amount = 10.0,
                merchant = "Test",
                date = now,
                currency = "EUR",
                transactionType = TransactionType.PURCHASE
            )

    private fun stubInsertConflict(insertResult: Long = -1L) {
        coEvery { expenseDao.insertAtomic(any()) } returns insertResult
        coEvery {
            expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns false
    }

    // ── P2-002: resolution order rawNotificationId → dedupeKey → fuzzy ───────

    @Test
    fun `insert conflict with raw notification id resolves to that identity and reports code`() =
        runTest(timeout = 60.seconds) {
            stubInsertConflict()
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns 99L

            val result = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L))

            assertTrue("Expected DuplicateSkipped, got $result", result is CreateExpenseResult.DuplicateSkipped)
            assertEquals(99L, (result as CreateExpenseResult.DuplicateSkipped).existingExpenseId)
            coVerify(exactly = 1) { expenseDao.findIdByRawNotificationId(777L) }
            // dedupeKey fallback must not be consulted after raw-id resolution.
            coVerify(exactly = 0) { expenseDao.findIdByDedupeKey(any()) }
            // Fuzzy resolver must not run after exact resolution.
            coVerify(exactly = 0) { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
            // Resolved duplicates still audit CREATE_DUPLICATE_SKIPPED.
            coVerify {
                transactionEventDao.insert(match {
                    it.eventType == com.yourname.expensetracker.domain.transaction.LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name
                })
            }
        }

    @Test
    fun `insert conflict falls back to dedupe key when raw id misses`() = runTest(timeout = 60.seconds) {
        stubInsertConflict()
        coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
        coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns 55L

        val result = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L))

        assertTrue("Expected DuplicateSkipped, got $result", result is CreateExpenseResult.DuplicateSkipped)
        assertEquals(55L, (result as CreateExpenseResult.DuplicateSkipped).existingExpenseId)
        // Exact raw-id lookup ran and missed; dedupe key resolved.
        coVerify(exactly = 1) { expenseDao.findIdByRawNotificationId(777L) }
        coVerify(exactly = 1) { expenseDao.findIdByDedupeKey(canonicalKey()) }
    }

    @Test
    fun `insert conflict unresolved in STRICT mode reports unresolved code and never fuzzy matches`() =
        runTest(timeout = 60.seconds) {
            stubInsertConflict()
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
            // In STRICT mode the attempted key is the strict identity key, not the
            // canonical policy key — stub exactly what the resolver will query.
            coEvery { expenseDao.findIdByDedupeKey(strictKey()) } returns null

            // STRICT_EXTERNAL_ID requires an identity key to pass validation —
            // without it the create fails typed ValidationFailed before insert.
            val result = coordinator.createExpenseStandaloneV2(
                request(rawNotificationId = 777L, deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID)
                    .copy(idempotencyKey = "test-key")
            )

            assertTrue("Expected InsertConflict, got $result", result is CreateExpenseResult.InsertConflict)
            assertEquals(InsertConflictCodes.UNRESOLVED, (result as CreateExpenseResult.InsertConflict).reasonCode)
            // STRICT_EXTERNAL_ID never runs the bounded fuzzy resolver.
            coVerify(exactly = 0) { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `insert conflict unresolved in SKIP_FOR_DEBUG_RESTORE never fuzzy matches`() = runTest(timeout = 60.seconds) {
        stubInsertConflict()
        coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
        coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null

        val result = coordinator.createExpenseStandaloneV2(
            request(rawNotificationId = 777L, deduplicationMode = DeduplicationMode.SKIP_FOR_DEBUG_RESTORE)
        )

        assertTrue("Expected InsertConflict, got $result", result is CreateExpenseResult.InsertConflict)
        assertEquals(InsertConflictCodes.UNRESOLVED, (result as CreateExpenseResult.InsertConflict).reasonCode)
        coVerify(exactly = 0) { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `insert conflict with no source id in STANDARD mode may resolve via fuzzy window`() = runTest(timeout = 60.seconds) {
        stubInsertConflict()
        coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
        coEvery {
            expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 31L

        // A manual create legitimately has no notification identity. Notification
        // auto-accept requires provenance and must fail before conflict resolution.
        val result = coordinator.createExpenseStandaloneV2(
            request(rawNotificationId = null).copy(source = ExpenseSource.MANUAL_ENTRY)
        )

        assertTrue("Expected DuplicateSkipped, got $result", result is CreateExpenseResult.DuplicateSkipped)
        assertEquals(31L, (result as CreateExpenseResult.DuplicateSkipped).existingExpenseId)
        coVerify(exactly = 0) { expenseDao.findIdByRawNotificationId(any()) }
        coVerify(exactly = 1) { expenseDao.findIdByDedupeKey(canonicalKey()) }
        coVerify(exactly = 1) { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `insert conflict fully unresolved returns typed conflict with unresolved code and no guessed id`() =
        runTest(timeout = 60.seconds) {
            stubInsertConflict()
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
            coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
            coEvery {
                expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns null

            val result = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L))

            assertTrue("Expected InsertConflict, got $result", result is CreateExpenseResult.InsertConflict)
            val conflict = result as CreateExpenseResult.InsertConflict
            assertEquals(InsertConflictCodes.UNRESOLVED, conflict.reasonCode)
            // The conflict carries the attempted key only — never an invented id.
            assertEquals(canonicalKey(), conflict.dedupeKey)
            coVerify {
                transactionEventDao.insert(match {
                    it.eventType == com.yourname.expensetracker.domain.transaction.LifecycleEventType.CREATE_INSERT_CONFLICT.name
                })
            }
        }

    @Test
    fun `insert conflict with null source id and no identity resolves nothing before fuzzy`() = runTest(timeout = 60.seconds) {
        stubInsertConflict()
        coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
        coEvery {
            expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        val result = coordinator.createExpenseStandaloneV2(
            request(rawNotificationId = null).copy(source = ExpenseSource.MANUAL_ENTRY)
        )

        assertTrue(result is CreateExpenseResult.InsertConflict)
        assertEquals(InsertConflictCodes.UNRESOLVED, (result as CreateExpenseResult.InsertConflict).reasonCode)
        // No source id → the raw-notification lookup must not run at all.
        coVerify(exactly = 0) { expenseDao.findIdByRawNotificationId(any()) }
        coVerify(exactly = 1) { expenseDao.findIdByDedupeKey(canonicalKey()) }
        coVerify(exactly = 1) { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── P2-008 (11c): blocking vs resolution policy distinct ────────────────

    /**
     * P2-008: the fuzzy resolver's candidate family filters `isNotMine = 0`
     * in SQL (ExpenseDao.getDuplicateCandidateBy*CurrencyAware), so a
     * not-mine-only window can never be claimed as the conflicting identity.
     * The coordinator consumes the guarded resolver and surfaces the typed
     * unresolved conflict instead.
     */
    @Test
    fun `insert conflict fuzzy resolver excludes not-mine rows and reports unresolved`() =
        runTest(timeout = 60.seconds) {
            stubInsertConflict()
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
            coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
            // DAO-level guard: the fuzzy window returns null because the only
            // candidate row is isNotMine (candidate SQL excludes not-mine rows).
            coEvery {
                expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns null

            val result = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L))

            assertTrue("Expected InsertConflict, got $result", result is CreateExpenseResult.InsertConflict)
            assertEquals(
                InsertConflictCodes.UNRESOLVED,
                (result as CreateExpenseResult.InsertConflict).reasonCode
            )
            // No CREATE_DUPLICATE_SKIPPED may reference an unproven/not-mine row.
            coVerify(exactly = 0) {
                transactionEventDao.insert(
                    match {
                        it.eventType ==
                            com.yourname.expensetracker.domain.transaction.LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name
                    }
                )
            }
        }

    /**
     * P2-008: exact identity lookups intentionally INCLUDE not-mine rows —
     * a not-mine expense with the same dedupeKey is still THE expense that
     * rejected the insert, so blocking/identity resolution must resolve to it.
     * The canonical pin is `insert conflict falls back to dedupe key when raw
     * id misses` above; the fuzzy window (the only not-mine-excluded tier)
     * returning null surfaces the typed UNRESOLVED conflict instead.
     */

    // ── P2-004: skipPreflightDeduplication is preflight-only ─────────────────

    @Test
    fun `skipPreflightDeduplication bypasses preflight but insert conflict still surfaces as typed conflict`() =
        runTest(timeout = 60.seconds) {
            stubInsertConflict()
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
            coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
            coEvery {
                expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns null

            val result = coordinator.createExpenseStandaloneV2(
                request(rawNotificationId = 777L, skipPreflight = true)
            )

            // P2-004 contract: the flag bypasses ONLY the preflight duplicate
            // check; the authoritative DB constraints still enforce identity and
            // the conflict surfaces as typed InsertConflict/DuplicateSkipped —
            // never a disguised success with a random-UUID key.
            assertTrue("Expected InsertConflict, got $result", result is CreateExpenseResult.InsertConflict)
            assertEquals(InsertConflictCodes.UNRESOLVED, (result as CreateExpenseResult.InsertConflict).reasonCode)
            // Preflight range check was skipped...
            coVerify(exactly = 0) { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) }
            // ...but the DB-identity conflict resolution still ran.
            coVerify(exactly = 1) { expenseDao.findIdByRawNotificationId(777L) }
        }

    @Test
    fun `skipPreflightDeduplication with repeated raw notification id resolves to existing row`() =
        runTest(timeout = 60.seconds) {
            // First insert succeeds; second collides on the unique raw id index.
            coEvery { expenseDao.insertAtomic(any()) } returnsMany listOf(11L, -1L)
            coEvery {
                expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns false
            coEvery { expenseDao.findIdByRawNotificationId(777L) } returns 42L

            val first = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L, skipPreflight = true))
            val second = coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L, skipPreflight = true))

            assertTrue("First should be Created, got $first", first is CreateExpenseResult.Created)
            assertTrue("Second should be DuplicateSkipped, got $second", second is CreateExpenseResult.DuplicateSkipped)
            assertEquals(42L, (second as CreateExpenseResult.DuplicateSkipped).existingExpenseId)
        }

    @Test
    fun `skipPreflightDeduplication never rewrites the dedupe key to a random UUID`() = runTest(timeout = 60.seconds) {
        stubInsertConflict()
        coEvery { expenseDao.findIdByRawNotificationId(777L) } returns null
        coEvery { expenseDao.findIdByDedupeKey(canonicalKey()) } returns null
        coEvery {
            expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        val insertedSlot = slot<Expense>()
        coEvery { expenseDao.insertAtomic(capture(insertedSlot)) } returns -1L

        coordinator.createExpenseStandaloneV2(request(rawNotificationId = 777L, skipPreflight = true))

        // P2-004: no random-UUID disguise — the attempted key is the canonical
        // deterministic key, untouched by the skip flag.
        assertEquals(canonicalKey(), insertedSlot.captured.dedupeKey)
    }

    // ── P2-003: bulkUpdateMerchant atomic all-or-nothing ─────────────────────

    private fun bulkRow(id: Long, merchant: String) = Expense(
        id = id,
        amount = 10.0,
        currency = "EUR",
        merchant = merchant,
        merchantKey = "old",
        transactionType = TransactionType.PURCHASE,
        date = now,
        dedupeKey = "old-$id"
    )

    @Test
    fun `bulkUpdateMerchant all-success writes one aggregate event and dispatches once`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getExpensesByMerchantKey("old") } returns listOf(bulkRow(1L, "Old"), bulkRow(2L, "Old"))
            // RP-11 FIX 2: preflight uses the blocking-consistent
            // (not-mine-INCLUSIVE) findBlockingDuplicateIdCurrencyAware.
            coEvery {
                expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns null

            val result = coordinator.bulkUpdateMerchant("Old", "New")

            assertTrue("Expected success, got $result", result.isSuccess)
            coVerify(exactly = 2) { expenseDao.updateMerchantAndKey(any(), eq("New"), any(), any()) }
            // Exactly ONE aggregate BULK_UPDATED event — not per-row.
            coVerify(exactly = 1) {
                transactionEventDao.insert(match {
                    it.eventType == com.yourname.expensetracker.domain.transaction.LifecycleEventType.BULK_UPDATED.name
                })
            }
            // One aggregate post-commit dispatch.
            coVerify(exactly = 1) { planner.planBulkUpdated(any(), eq(2), any(), any()) }
        }

    @Test
    fun `bulkUpdateMerchant mixed collision aborts all rows with no event and no dispatch`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getExpensesByMerchantKey("old") } returns listOf(bulkRow(1L, "Old"), bulkRow(2L, "Old"))
            // Row 1's target key does not collide; row 2's does (an expense
            // outside the rename set). Mixed collision → all-or-nothing abort.
            // RP-11 FIX 2: preflight uses the blocking-consistent
            // (not-mine-INCLUSIVE) findBlockingDuplicateIdCurrencyAware.
            coEvery {
                expenseDao.findBlockingDuplicateIdCurrencyAware(
                    amount = 10.0,
                    merchant = "New",
                    date = now,
                    currency = "EUR",
                    transactionType = any(),
                    merchantKey = any(),
                    dedupeKey = any(),
                    windowMs = any()
                )
            } returnsMany listOf(null, 999L)

            val result = coordinator.bulkUpdateMerchant("Old", "New")

            assertTrue("Expected failure, got $result", result.isFailure)
            assertEquals(
                "Failure must carry the controlled reason",
                TransactionLifecycleCoordinator.BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE,
                (result.exceptionOrNull() as? DuplicateUpdateException)?.message
            )
            // No row mutated, no event, no dispatch — full rollback.
            coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
            coVerify(exactly = 0) { transactionEventDao.insert(any()) }
            coVerify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        }

    /**
     * RP-11 FIX 2: a not-mine row occupying a target identity must abort the
     * whole rename with the controlled MERCHANT_RENAME_DUPLICATE reason — not
     * slip past the preflight and die on the raw dedupeKey unique index.
     */
    @Test
    fun `bulkUpdateMerchant collision against not-mine row aborts with typed failure`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getExpensesByMerchantKey("old") } returns listOf(bulkRow(1L, "Old"))
            // Only the blocking-consistent lookup sees the not-mine row; the
            // fuzzy resolver keeps its isNotMine = 0 exclusion (relaxed null).
            coEvery {
                expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
            } returns 999L

            val result = coordinator.bulkUpdateMerchant("Old", "New")

            assertTrue("Expected failure, got $result", result.isFailure)
            assertEquals(
                "Failure must carry the controlled reason",
                TransactionLifecycleCoordinator.BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE,
                (result.exceptionOrNull() as? DuplicateUpdateException)?.message
            )
            coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
            coVerify(exactly = 0) { transactionEventDao.insert(any()) }
            coVerify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        }

    @Test
    fun `bulkUpdateMerchant zero matching rows is a successful no-op with no event and no dispatch`() =
        runTest(timeout = 60.seconds) {
            coEvery { expenseDao.getExpensesByMerchantKey("old") } returns emptyList()

            val result = coordinator.bulkUpdateMerchant("Old", "New")

            assertTrue("Expected success for no-op, got $result", result.isSuccess)
            coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
            coVerify(exactly = 0) { transactionEventDao.insert(any()) }
            coVerify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        }

    @Test
    fun `bulkUpdateMerchant same merchant is a successful no-op`() = runTest(timeout = 60.seconds) {
        val result = coordinator.bulkUpdateMerchant("Same", "Same")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { expenseDao.getExpensesByMerchantKey(any()) }
        coVerify(exactly = 0) { transactionEventDao.insert(any()) }
    }

    @Test
    fun `bulkUpdateMerchant collision inside rename set is allowed`() = runTest(timeout = 60.seconds) {
        // Rows within the rename set may collide with each other — only rows
        // OUTSIDE the set abort the operation.
        coEvery { expenseDao.getExpensesByMerchantKey("old") } returns listOf(bulkRow(1L, "Old"), bulkRow(2L, "Old"))
        coEvery {
            expenseDao.findBlockingDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1L // collides with row 1, which is part of the rename set

        val result = coordinator.bulkUpdateMerchant("Old", "New")

        assertTrue("Intra-set collision should succeed, got $result", result.isSuccess)
        coVerify(exactly = 2) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
    }
}
