package com.yourname.expensetracker.domain.notification.capture

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.common.sha256
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-02 U-004: barrier ownership tests for [NotificationIntakeCoordinator].
 *
 * The coordinator owns a [DatabaseWriteBarrier]; both durable intake writes
 * ([NotificationIntakeDao.insertOrIgnore] in [NotificationIntakeCoordinator.capture]
 * and [NotificationIntakeCoordinator.captureForRetry]) run behind barrier gates
 * against the merged (gr-14f-mediated) contract: during restore/maintenance
 * BOTH paths skip silently — `capture` reports the typed
 * [NotificationIntakeCaptureResult.Dropped] outcome, `captureForRetry` logs and
 * returns — and no DAO mutation or WorkManager enqueue happens.
 *
 * Harness: a REAL [DatabaseWriteBarrier] over a mocked [RestoreMaintenanceMode]
 * (RetentionTargetPurgeTest pattern) — stubbing runWrite on a relaxed mock would
 * silently drop the executed lambda. The [DomainTransactionRunner] is mocked with
 * the ReceiptLifecycleCoordinatorTest pattern: a setup-default stub executes every
 * runInTransaction block with a [TransactionContext], mirroring the real runner,
 * so the legacy-transition work actually runs against the mocked DAOs.
 *
 * RP-10 10a (P1-001/P1-002): the deferred path now uses the canonical content
 * fingerprint, carries the caller-resolved storage snapshot, and performs an
 * atomic legacy `DEFERRED_<keyHash>` row transition inside the barrier write.
 */
class NotificationIntakeCoordinatorTest {

    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private lateinit var diagnostics: NotificationDiagnosticEmitter
    private lateinit var crypto: NotificationTransientPayloadCrypto
    private lateinit var maintenanceMode: RestoreMaintenanceMode
    private lateinit var transactionRunner: DomainTransactionRunner

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_700_000_000_000L }

    private lateinit var coordinator: NotificationIntakeCoordinator

    @Before
    fun setup() {
        intakeDao = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        diagnostics = mockk(relaxed = true)
        crypto = mockk(relaxed = true)
        maintenanceMode = mockk(relaxed = true)
        transactionRunner = mockk(relaxed = true)
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        every { crypto.encrypt(any()) } returns EncryptedPayload("ct", "nonce", 1)

        // The legacy transition runs inside transactionRunner.runInTransaction —
        // execute the block with a TransactionContext (ReceiptLifecycleCoordinatorTest
        // pattern); a relaxed mock that silently skips it would let the DB-dependent
        // assertions pass vacuously.
        stubTransactionRunnerExecutesBlocks()

        coordinator = makeCoordinator()
    }

    private fun stubTransactionRunnerExecutesBlocks() {
        val ctx = TransactionContext(
            correlationId = "test-correlation",
            operationId = "test",
            source = "NotificationIntakeCoordinator",
            occurredAt = 1_700_000_000_000L
        )
        coEvery { transactionRunner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (arg<suspend (TransactionContext) -> Any>(5))(ctx)
        }
    }

    private fun makeCoordinator(mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL): NotificationIntakeCoordinator {
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            every { maintenanceMode.currentMode() } returns mode
            every { maintenanceMode.isWritesAllowed() } returns false
        }
        return NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = diagnostics,
            timeProvider = timeProvider,
            crypto = crypto,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            transactionRunner = transactionRunner
        )
    }

    private suspend fun capture(combinedBody: String? = null): NotificationIntakeCaptureResult =
        coordinator.capture(
            packageName = "com.bank.app",
            appName = "Bank",
            notificationKey = "key-1",
            notificationKeyHash = "hash-1",
            postTime = 1_700_000_000_000L,
            title = "Payment alert",
            text = "You paid 50 EUR",
            combinedBody = combinedBody,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr-1",
            source = "listener"
        )

    private fun deferredStorage(mode: RawStorageMode) = DeferredCaptureStorageSnapshot(
        storageMode = mode,
        appName = "Bank",
        extrasJson = if (mode == RawStorageMode.STORE_RAW) "{\"k\":\"v\"}" else null
    )

    private suspend fun captureForRetry(
        mode: RawStorageMode = RawStorageMode.STORE_METADATA_ONLY
    ): NotificationIntakeCaptureResult = coordinator.captureForRetry(
        packageName = "com.bank.app",
        notificationKey = "key-1",
        postTime = 1_700_000_000_000L,
        correlationId = "corr-deferred",
        title = "Payment alert",
        text = "You paid 50 EUR",
        combinedBody = "You paid 50 EUR",
        subText = null,
        storage = deferredStorage(mode)
    )

    /** The legacy identity used before RP-10 10a. */
    private fun legacyFingerprint(): String =
        "DEFERRED_" + "key-1".sha256().take(32)

    /** The canonical fingerprint both paths must share for the test content. */
    private fun canonicalFingerprint(): String = RawNotificationFingerprint.compute(
        packageName = "com.bank.app",
        title = "Payment alert",
        text = "You paid 50 EUR",
        bigText = "You paid 50 EUR",
        timestamp = 1_700_000_000_000L
    )

    private fun testIntakeRow(id: Long, status: String, fingerprint: String) =
        NotificationIntakeEntity(
            id = id,
            packageName = "com.bank.app",
            appName = null,
            notificationKeyHash = "hash",
            postTime = 1_700_000_000_000L,
            capturedAt = 1_700_000_000_000L,
            source = "deferred",
            correlationId = "corr-legacy",
            dedupeFingerprint = fingerprint,
            contentHash = null,
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = "STORE_METADATA_ONLY",
            payloadMode = "DEFERRED",
            status = status,
            lockedAt = if (status == NotificationIntakeStatus.PROCESSING.name) 1L else null,
            lockedBy = if (status == NotificationIntakeStatus.PROCESSING.name) "worker" else null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )

    // ── Barrier semantics (pinned, gr-14f-mediated) ─────────────────────────────

    @Test
    fun `capture checks barrier before the intake insert`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 42L

        val result = capture()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        assertEquals(42L, (result as NotificationIntakeCaptureResult.Enqueued).intakeId)

        // The gate-before-write ordering is proven behaviorally by
        // `capture during restore throws barrier exception before any insert`:
        // with the barrier blocked, the typed exception surfaces and the DAO
        // write never happens.
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `capture during restore is dropped before any insert`() = runTest {
        // Merged (gr-14f-mediated) semantics: capture reports a typed Dropped
        // result instead of throwing when writes are blocked.
        coordinator = makeCoordinator(RestoreMaintenanceMode.Mode.RESTORE_STAGING)

        val result = capture()

        assertTrue(result is NotificationIntakeCaptureResult.Dropped)
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `captureForRetry inserts behind the barrier in normal mode`() = runTest {
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(any()) } returns 7L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry during restore skips silently before any insert`() = runTest {
        // Merged (gr-14f-mediated) semantics: the deferred path logs and skips —
        // it never throws on a barrier block.
        coordinator = makeCoordinator(RestoreMaintenanceMode.Mode.RESTORE_STAGING)

        coordinator.captureForRetry(
            packageName = "com.bank.app",
            notificationKey = "key-1",
            postTime = 1_700_000_000_000L,
            correlationId = "corr-deferred",
            title = "Payment alert",
            storage = deferredStorage(RawStorageMode.STORE_METADATA_ONLY)
        )

        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    // ── P1-001: canonical deferred identity ─────────────────────────────────────

    @Test
    fun `captureForRetry persists canonical content fingerprint not the legacy key hash`() = runTest {
        val slot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(capture(slot)) } returns 7L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        val entity = slot.captured
        assertEquals(canonicalFingerprint(), entity.dedupeFingerprint)
        assertFalse(entity.dedupeFingerprint.startsWith("DEFERRED_"))
        // The notification key is used only for the legacy lookup / work name.
        coVerify(exactly = 1) { intakeDao.getByFingerprint(legacyFingerprint()) }
    }

    @Test
    fun `deferred row is deduped by live capture via the canonical fingerprint`() = runTest {
        val slot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(capture(slot)) } returns 7L

        captureForRetry()

        // The live path's existence check must recognize the deferred row.
        coEvery { intakeDao.existsByFingerprint(slot.captured.dedupeFingerprint) } returns true
        val result = capture(combinedBody = "You paid 50 EUR")

        assertTrue(result is NotificationIntakeCaptureResult.Duplicate)
    }

    @Test
    fun `live and deferred captures share one canonical identity for equal content`() = runTest {
        coEvery { intakeDao.existsByFingerprint(any()) } returns false
        val liveSlot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.insertOrIgnore(capture(liveSlot)) } returns 42L

        capture(combinedBody = "You paid 50 EUR")

        val deferredSlot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(capture(deferredSlot)) } returns 43L

        captureForRetry()

        assertEquals(liveSlot.captured.dedupeFingerprint, deferredSlot.captured.dedupeFingerprint)
    }

    // ── P1-001: atomic legacy-row transition ────────────────────────────────────

    @Test
    fun `captureForRetry terminalizes actionable legacy row before canonical insert`() = runTest {
        val legacyRow = testIntakeRow(5L, NotificationIntakeStatus.RECEIVED.name, legacyFingerprint())
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns legacyRow
        coEvery { intakeDao.insertOrIgnore(any()) } returns 9L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 1) {
            intakeDao.markFinalFailure(
                id = 5L,
                failureCode = NotificationIntakeCoordinator.LEGACY_DEFERRED_SUPERSEDED,
                failureHash = null,
                nowMs = 1_700_000_000_000L
            )
        }
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry terminalizes retryable legacy row before canonical insert`() = runTest {
        val legacyRow = testIntakeRow(6L, NotificationIntakeStatus.FAILED_RETRYABLE.name, legacyFingerprint())
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns legacyRow
        coEvery { intakeDao.insertOrIgnore(any()) } returns 10L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 1) {
            intakeDao.markFinalFailure(
                id = 6L,
                failureCode = NotificationIntakeCoordinator.LEGACY_DEFERRED_SUPERSEDED,
                failureHash = null,
                nowMs = 1_700_000_000_000L
            )
        }
    }

    @Test
    fun `captureForRetry leaves active claim untouched and still inserts canonical row`() = runTest {
        val claimedRow = testIntakeRow(7L, NotificationIntakeStatus.PROCESSING.name, legacyFingerprint())
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns claimedRow
        coEvery { intakeDao.insertOrIgnore(any()) } returns 11L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 0) { intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry terminal legacy row stays untouched and canonical row inserts`() = runTest {
        val terminalRow = testIntakeRow(8L, NotificationIntakeStatus.PROCESSED.name, legacyFingerprint())
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns terminalRow
        coEvery { intakeDao.insertOrIgnore(any()) } returns 12L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 0) { intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry canonical conflict returns Duplicate with existing intake id`() = runTest {
        val existingRow = testIntakeRow(12L, NotificationIntakeStatus.RECEIVED.name, canonicalFingerprint())
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns null
        coEvery { intakeDao.getByFingerprint(canonicalFingerprint()) } returns existingRow
        coEvery { intakeDao.insertOrIgnore(any()) } returns -1L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Duplicate)
        assertEquals(12L, (result as NotificationIntakeCaptureResult.Duplicate).existingIntakeId)
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `captureForRetry duplicate conflict emits a terminal duplicate diagnostic`() = runTest {
        coEvery { intakeDao.getByFingerprint(legacyFingerprint()) } returns null
        coEvery { intakeDao.getByFingerprint(canonicalFingerprint()) } returns null
        coEvery { intakeDao.insertOrIgnore(any()) } returns -1L

        captureForRetry()

        coVerify(exactly = 1) {
            diagnostics.emit(match {
                it.outcome == com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE &&
                    it.reasonCode == DiagnosticReasonCode.DUPLICATE &&
                    it.isTerminal
            })
        }
    }

    // ── P1-002: resolved storage mode snapshot ──────────────────────────────────

    @Test
    fun `captureForRetry persists the resolved metadata mode with transient payload`() = runTest {
        val slot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(capture(slot)) } returns 13L

        captureForRetry(mode = RawStorageMode.STORE_METADATA_ONLY)

        val entity = slot.captured
        assertEquals("STORE_METADATA_ONLY", entity.rawStorageMode)
        assertEquals("TRANSIENT", entity.payloadMode)
        assertEquals("Bank", entity.appName)
        // No visible payload outside the encrypted transient payload.
        assertEquals(null, entity.title)
        assertEquals(null, entity.text)
        assertEquals(null, entity.bigText)
        assertEquals(null, entity.extrasJson)
        assertNotNull(entity.transientPayloadCiphertext)
    }

    @Test
    fun `captureForRetry STORE_RAW preserves visible payload without encryption`() = runTest {
        val slot = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(capture(slot)) } returns 14L

        captureForRetry(mode = RawStorageMode.STORE_RAW)

        val entity = slot.captured
        assertEquals("STORE_RAW", entity.rawStorageMode)
        assertEquals("RAW", entity.payloadMode)
        assertEquals("Payment alert", entity.title)
        assertEquals("You paid 50 EUR", entity.text)
        assertEquals("You paid 50 EUR", entity.bigText)
        assertEquals("{\"k\":\"v\"}", entity.extrasJson)
        assertEquals(null, entity.transientPayloadCiphertext)
        coVerify(exactly = 0) { crypto.encrypt(any()) }
    }

    @Test
    fun `captureForRetry DO_NOT_STORE returns NotStored before any durable write`() = runTest {
        val result = captureForRetry(mode = RawStorageMode.DO_NOT_STORE)

        assertTrue(result is NotificationIntakeCaptureResult.NotStored)
        coVerify(exactly = 0) { intakeDao.getByFingerprint(any()) }
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) { crypto.encrypt(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
        coVerify(exactly = 1) {
            diagnostics.emit(match {
                it.isTerminal && it.reasonCode == DiagnosticReasonCode.DEFERRED_NOT_STORED
            })
        }
    }

    @Test
    fun `captureForRetry encryption failure returns StorageFailure without any insert`() = runTest {
        every { crypto.encrypt(any()) } throws RuntimeException("encryption backend unavailable")

        val result = captureForRetry(mode = RawStorageMode.STORE_METADATA_ONLY)

        assertTrue(result is NotificationIntakeCaptureResult.StorageFailure)
        assertEquals(
            NotificationIntakeCoordinator.DEFERRED_TRANSIENT_ENCRYPT_FAILED,
            (result as NotificationIntakeCaptureResult.StorageFailure).reasonCode
        )
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 1) {
            diagnostics.emit(match {
                it.isTerminal &&
                    it.reasonCode == DiagnosticReasonCode.DEFERRED_TRANSIENT_ENCRYPT_FAILED
            })
        }
    }

    // ── P1-001: transaction-runner ownership (DomainTransactionRunner migration) ──

    @Test
    fun `captureForRetry runs legacy transition inside DomainTransactionRunner and inside barrier runWrite`() = runTest {
        // Proof the block ran inside the runner: the canonical insert happens INSIDE
        // the runInTransaction block (setup stub executes the block), behind the REAL
        // DatabaseWriteBarrier's runWrite (barrier check first, then transaction).
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(any()) } returns 21L

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 1) {
            transactionRunner.runInTransaction<Any>(
                correlationId = "corr-deferred",
                causationId = null,
                operationId = "notification.captureForRetry.legacy_transition",
                source = "NotificationIntakeCoordinator.captureForRetry",
                metadata = any(),
                block = any()
            )
        }
        // The insert executed inside the transaction block (stub executed it).
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
        // The barrier gate was checked under the deferred-path operation name before
        // the transaction ran (checkWritesAllowed happens before the enqueue write too,
        // hence exactly = 2).
        coVerify(exactly = 2) {
            maintenanceMode.currentMode()
        }
    }
}
