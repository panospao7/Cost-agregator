package com.yourname.expensetracker.domain.notification.capture

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Fix-1.0.4 S2 (P-01-1, P-01-2).
 *
 * P-01-1: every enqueue site (`capture`, `captureForRetry`, `recoverPending`) uses the
 * single unique-work name `notification-intake-<id>` with [ExistingWorkPolicy.KEEP];
 * legacy bare `<id>` work is never cancelled.
 *
 * P-01-2: a failing transient encrypt inside `capture()` yields a controlled
 * [NotificationIntakeCaptureResult.StorageFailure], no row, no enqueue and exactly one
 * bounded diagnostic; cancellation is rethrown with no diagnostic.
 *
 * Harness: real [DatabaseWriteBarrier] over a mocked [RestoreMaintenanceMode] (same
 * pattern as NotificationIntakeCoordinatorTest); WorkManager is mocked and hands back a
 * REAL completed Operation ([CompletedOperation]) so the coordinator's await() returns.
 */
class NotificationIntakeCaptureHardeningTest {

    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private lateinit var diagnostics: NotificationDiagnosticEmitter
    private lateinit var crypto: NotificationTransientPayloadCrypto
    private lateinit var maintenanceMode: RestoreMaintenanceMode
    private lateinit var transactionRunner: DomainTransactionRunner
    private lateinit var coordinator: NotificationIntakeCoordinator

    private val timeProvider: TimeProvider = object : TimeProvider {
        override fun now() = 1_700_000_000_000L
    }

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
        coEvery {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns CompletedOperation()

        val ctx = TransactionContext(
            correlationId = "test-correlation",
            operationId = "test",
            source = "NotificationIntakeCaptureHardeningTest",
            occurredAt = 1_700_000_000_000L
        )
        coEvery { transactionRunner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (arg<suspend (TransactionContext) -> Any>(5))(ctx)
        }

        coordinator = NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = diagnostics,
            timeProvider = timeProvider,
            crypto = crypto,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            transactionRunner = transactionRunner
        )
    }

    private fun scheduler() = NotificationIntakeRecoveryScheduler(
        intakeDao = intakeDao,
        workManager = workManager,
        timeProvider = timeProvider,
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
    )

    private suspend fun capture(
        mode: RawStorageMode = RawStorageMode.STORE_RAW,
        text: String? = "You paid 50 EUR"
    ): NotificationIntakeCaptureResult = coordinator.capture(
        packageName = "com.bank.app",
        appName = "Bank",
        notificationKey = "key-1",
        notificationKeyHash = "hash-1",
        postTime = 1_700_000_000_000L,
        title = "Payment alert",
        text = text,
        combinedBody = null,
        subText = null,
        extrasJson = null,
        rawStorageMode = mode,
        correlationId = "corr-1",
        source = "listener"
    )

    private suspend fun captureForRetry(): NotificationIntakeCaptureResult = coordinator.captureForRetry(
        packageName = "com.bank.app",
        notificationKey = "key-1",
        postTime = 1_700_000_000_000L,
        correlationId = "corr-deferred",
        title = "Payment alert",
        text = "You paid 50 EUR",
        combinedBody = "You paid 50 EUR",
        subText = null,
        storage = DeferredCaptureStorageSnapshot(
            storageMode = RawStorageMode.STORE_METADATA_ONLY,
            appName = "Bank",
            extrasJson = null
        )
    )

    private fun readyRow(id: Long) = NotificationIntakeEntity(
        id = id,
        packageName = "com.bank.app",
        appName = null,
        notificationKeyHash = "hash-$id",
        postTime = 1_700_000_000_000L,
        capturedAt = 1_700_000_000_000L,
        source = "listener",
        correlationId = "corr-$id",
        dedupeFingerprint = "fp-$id",
        contentHash = null,
        title = null,
        text = null,
        bigText = null,
        subText = null,
        extrasJson = null,
        rawStorageMode = "STORE_RAW",
        payloadMode = "RAW",
        status = NotificationIntakeStatus.RECEIVED.name,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L
    )

    // ── P-01-1: name parity ─────────────────────────────────────────────────────

    @Test
    fun `P-01-1 work name function builds notification-intake-id`() {
        assertEquals("notification-intake-5", NotificationIntakeWorkNames.uniqueWorkName(5L))
        assertEquals("notification-intake-9000000000", NotificationIntakeWorkNames.uniqueWorkName(9_000_000_000L))
    }

    @Test
    fun `P-01-1 capture enqueues under the prefixed name with KEEP`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 31L
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        coEvery {
            workManager.enqueueUniqueWork(capture(names), capture(policies), any<OneTimeWorkRequest>())
        } returns CompletedOperation()

        val result = capture()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        assertEquals(listOf("notification-intake-31"), names)
        assertEquals(listOf(ExistingWorkPolicy.KEEP), policies)
        assertNotEquals("31", names.single())
    }

    @Test
    fun `P-01-1 captureForRetry enqueues under the prefixed name with KEEP`() = runTest {
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(any()) } returns 32L
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        coEvery {
            workManager.enqueueUniqueWork(capture(names), capture(policies), any<OneTimeWorkRequest>())
        } returns CompletedOperation()

        val result = captureForRetry()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        assertEquals(listOf("notification-intake-32"), names)
        assertEquals(listOf(ExistingWorkPolicy.KEEP), policies)
    }

    @Test
    fun `P-01-1 recoverPending enqueues every ready row under the prefixed name with KEEP and cancels nothing`() = runTest {
        coEvery { intakeDao.releaseStaleProcessing(any(), any()) } returns 0
        coEvery { intakeDao.getReadyForProcessing(any(), any()) } returns listOf(readyRow(33L), readyRow(34L))
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        coEvery {
            workManager.enqueueUniqueWork(capture(names), capture(policies), any<OneTimeWorkRequest>())
        } returns CompletedOperation()

        scheduler().recoverPending()

        assertEquals(listOf("notification-intake-33", "notification-intake-34"), names)
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), policies)
        // Upgrade rule: legacy work under the bare "<id>" name is neither cancelled nor renamed.
        coVerify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        coVerify(exactly = 0) { workManager.cancelAllWorkByTag(any()) }
        coVerify(exactly = 0) { workManager.cancelWorkById(any()) }
    }

    @Test
    fun `P-01-1 recoverPending with no ready rows enqueues nothing`() = runTest {
        coEvery { intakeDao.releaseStaleProcessing(any(), any()) } returns 0
        coEvery { intakeDao.getReadyForProcessing(any(), any()) } returns emptyList()

        scheduler().recoverPending()

        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `P-01-1 all three sites produce the same name for the same row id`() = runTest {
        val names = mutableListOf<String>()
        coEvery {
            workManager.enqueueUniqueWork(capture(names), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns CompletedOperation()
        coEvery { intakeDao.getByFingerprint(any()) } returns null
        coEvery { intakeDao.insertOrIgnore(any()) } returns 77L
        coEvery { intakeDao.releaseStaleProcessing(any(), any()) } returns 0
        coEvery { intakeDao.getReadyForProcessing(any(), any()) } returns listOf(readyRow(77L))

        capture()
        captureForRetry()
        scheduler().recoverPending()

        assertEquals(3, names.size)
        assertEquals(setOf("notification-intake-77"), names.toSet())
    }

    // ── P-01-2: capture() encrypt failure ───────────────────────────────────────

    @Test
    fun `P-01-2 capture with working encrypt in a transient mode still inserts and enqueues`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 41L
        val saved = slot<NotificationIntakeEntity>()
        coEvery { intakeDao.insertOrIgnore(capture(saved)) } returns 41L

        val result = capture(mode = RawStorageMode.STORE_REDACTED)

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        assertEquals("ct", saved.captured.transientPayloadCiphertext)
        assertNull(saved.captured.text)
        coVerify(exactly = 1) { crypto.encrypt(any()) }
    }

    @Test
    fun `P-01-2 capture encrypt failure returns StorageFailure with no row no enqueue and one bounded diagnostic`() = runTest {
        val rawSentinel = "SENTINEL-RAW-NOTIFICATION-TEXT-4711"
        val messageSentinel = "SENTINEL-ENCRYPT-EXCEPTION-MESSAGE-9"
        every { crypto.encrypt(any()) } throws RuntimeException(messageSentinel)

        val result = capture(mode = RawStorageMode.STORE_REDACTED, text = rawSentinel)

        assertTrue(result is NotificationIntakeCaptureResult.StorageFailure)
        assertEquals(
            "DEFERRED_TRANSIENT_ENCRYPT_FAILED",
            (result as NotificationIntakeCaptureResult.StorageFailure).reasonCode
        )
        assertFalse(result.toString().contains(rawSentinel))
        assertFalse(result.toString().contains(messageSentinel))
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

        val events = mutableListOf<DiagnosticEvent>()
        coVerify(exactly = 1) { diagnostics.emit(any()) }
        coVerify(exactly = 1) { diagnostics.emit(capture(events)) }
        val event = events.single()
        assertEquals(DiagnosticReasonCode.DEFERRED_TRANSIENT_ENCRYPT_FAILED, event.reasonCode)
        assertEquals(EventOutcome.FAILED_FINAL, event.outcome)
        assertEquals("intake", event.stage)
        assertEquals("corr-1", event.correlationId)
        assertTrue(event.isTerminal)
        // Bounded: no exception object (its message/stack could carry content), no entity id.
        assertNull(event.exception)
        assertNull(event.entityId)
        assertFalse(event.toString().contains(rawSentinel))
        assertFalse(event.toString().contains(messageSentinel))
    }

    @Test
    fun `P-01-2 capture encrypt failure under METADATA_ONLY is also a StorageFailure`() = runTest {
        every { crypto.encrypt(any()) } throws java.security.GeneralSecurityException("keystore unavailable")

        val result = capture(mode = RawStorageMode.STORE_METADATA_ONLY)

        assertTrue(result is NotificationIntakeCaptureResult.StorageFailure)
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `P-01-2 capture STORE_RAW never encrypts so an encrypt failure cannot affect it`() = runTest {
        every { crypto.encrypt(any()) } throws RuntimeException("must not be called")
        coEvery { intakeDao.insertOrIgnore(any()) } returns 42L

        val result = capture(mode = RawStorageMode.STORE_RAW)

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 0) { crypto.encrypt(any()) }
        coVerify(exactly = 0) { diagnostics.emit(any()) }
    }

    @Test
    fun `P-01-2 capture rethrows CancellationException from encrypt with no diagnostic no row no enqueue`() = runTest {
        every { crypto.encrypt(any()) } throws CancellationException("cancelled during encrypt")

        try {
            capture(mode = RawStorageMode.STORE_REDACTED)
            fail("CancellationException from encrypt must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled during encrypt", expected.message)
        }

        coVerify(exactly = 0) { diagnostics.emit(any()) }
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }
}
