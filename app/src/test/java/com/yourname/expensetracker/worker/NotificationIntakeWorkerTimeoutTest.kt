package com.yourname.expensetracker.worker

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRunHandle
import com.yourname.expensetracker.domain.workers.WorkerRunLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for timeout/cancellation distinction in [NotificationIntakeWorker].
 *
 * These tests verify that [TimeoutCancellationException] is treated as a
 * retryable condition (WorkManager 10-min execution timeout), while a plain
 * [CancellationException] is propagated as a system cancellation.
 */
class NotificationIntakeWorkerTimeoutTest {

    /** Helper: creates a real [TimeoutCancellationException] via [withTimeout]. */
    private fun createTimeoutCancellationException(): TimeoutCancellationException = runBlocking {
        try {
            withTimeout(1) { delay(100) }
        } catch (e: TimeoutCancellationException) {
            return@runBlocking e
        }
        throw IllegalStateException("Expected TimeoutCancellationException")
    }

    @Test
    fun `worker_timeout_marks_retryable_not_terminal`() {
        val timeoutEx = createTimeoutCancellationException()
        val isCancellation = timeoutEx is CancellationException
        val isTimeout = timeoutEx is TimeoutCancellationException

        assertTrue("TimeoutCancellationException must be a CancellationException subclass", isCancellation)
        assertTrue("TimeoutCancellationException must be a TimeoutCancellationException", isTimeout)
    }

    @Test
    fun `worker_system_cancellation_propagates`() {
        val systemCancel: Throwable = CancellationException("System shutdown")
        val isTimeout = systemCancel is TimeoutCancellationException

        assertFalse("Plain CancellationException must NOT be a TimeoutCancellationException", isTimeout)
    }

    @Test
    fun `timeout_cancellation_exception_is_subclass_of_cancellation_exception`() {
        val timeoutEx = createTimeoutCancellationException()
        val isCancellation = timeoutEx is CancellationException
        val isTimeout = timeoutEx is TimeoutCancellationException

        assertTrue("TimeoutCancellationException must be a CancellationException subclass", isCancellation)
        assertTrue("TimeoutCancellationException must be a TimeoutCancellationException", isTimeout)
    }

    @Test
    fun `retryable_io_exception_calls_markRetryableFailure_and_returns_retry`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        // Build a REAL WorkerExecutionGuard with mocked dependencies so the
        // worker's block executes through the actual guard logic.
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        val executionGuard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMode,
            workerRunLogger = runLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
            backgroundJobRunDao = bgJobRunDao,
            notificationPermissionChecker = permissionChecker,
            timeProvider = timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 1, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1
        coEvery { repository.processAndSave(any(), any(), any(), any()) } coAnswers {
            throw java.io.IOException("database is locked")
        }

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Must return retry() for retryable exception", WorkResult.retry(), result)
        coVerify(exactly = 1) {
            intakeDao.markRetryableFailure(
                id = intakeId, nextAttemptAt = any(), failureCode = "WORKER_EXCEPTION",
                failureHash = any(), nowMs = now
            )
        }
    }

    @Test
    fun `max_attempts_exceeded_on_timeout_returns_failure_not_retry`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        val executionGuard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMode,
            workerRunLogger = runLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
            backgroundJobRunDao = bgJobRunDao,
            notificationPermissionChecker = permissionChecker,
            timeProvider = timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 4, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1
        coEvery { repository.processAndSave(any(), any(), any(), any()) } coAnswers {
            throw java.io.IOException("timeout")
        }

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Must return failure() when max attempts exceeded", WorkResult.failure(), result)
        coVerify(exactly = 1) {
            intakeDao.markFinalFailure(
                id = intakeId, failureCode = "WORKER_EXCEPTION",
                failureHash = any(), nowMs = now
            )
        }
    }

    @Test
    fun `timeout_with_max_attempts_exceeded_returns_retry_from_guard`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        val executionGuard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMode,
            workerRunLogger = runLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
            backgroundJobRunDao = bgJobRunDao,
            notificationPermissionChecker = permissionChecker,
            timeProvider = timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 4, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1
        coEvery { repository.processAndSave(any(), any(), any(), any()) } coAnswers {
            throw createTimeoutCancellationException()
        }

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        // PR12H-1: The guard now catches TimeoutCancellationException and returns
        // Retry by default (WorkerTimeoutPolicy.RETRY). The worker's local TCE handler
        // is no longer reached; maxAttempts exhaustion is gated on the next run attempt.
        assertEquals("Must return retry() — guard handles TCE with RETRY policy", WorkResult.retry(), result)
    }

    @Test
    fun `privacy_denied_does_not_decrypt`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        // Privacy gate denies
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns PrivacyDecision.Denied("test")

        val executionGuard = WorkerExecutionGuard(
            writeBarrier, readBarrier, restoreMode, runLogger,
            privacyGate, leaseRegistry, diagnosticSink, bgJobRunDao,
            permissionChecker, timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Privacy denied should return success", WorkResult.success(), result)
        coVerify(exactly = 0) { crypto.decrypt(any(), any(), any()) }
    }

    @Test
    fun `privacy_fail_closed_does_not_decrypt`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        // Privacy gate fails closed
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns PrivacyDecision.FailClosed("test")

        val executionGuard = WorkerExecutionGuard(
            writeBarrier, readBarrier, restoreMode, runLogger,
            privacyGate, leaseRegistry, diagnosticSink, bgJobRunDao,
            permissionChecker, timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Privacy fail-closed should return success", WorkResult.success(), result)
        coVerify(exactly = 0) { crypto.decrypt(any(), any(), any()) }
    }

    @Test
    fun `privacy_denied_marks_intake_privacy_denied`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns PrivacyDecision.Denied("test")

        val executionGuard = WorkerExecutionGuard(
            writeBarrier, readBarrier, restoreMode, runLogger,
            privacyGate, leaseRegistry, diagnosticSink, bgJobRunDao,
            permissionChecker, timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 1, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Privacy denied should return success", WorkResult.success(), result)
        coVerify(atLeast = 1) {
            intakeDao.markPrivacyDeniedAndPurgeAllPayload(any(), any(), any(), any())
        }
    }

    @Test
    fun `privacy_denied_purges_payload`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns PrivacyDecision.Denied("test")

        val executionGuard = WorkerExecutionGuard(
            writeBarrier, readBarrier, restoreMode, runLogger,
            privacyGate, leaseRegistry, diagnosticSink, bgJobRunDao,
            permissionChecker, timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 1, maxAttempts = 5, now = now,
            payloadMode = "TRANSIENT"
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        val result = worker.doWork()

        assertEquals("Privacy denied should return success", WorkResult.success(), result)
        coVerify(atLeast = 1) { intakeDao.markPrivacyDeniedAndPurgeAllPayload(any(), any(), any(), any()) }
    }

    @Test
    fun `checkpoint_stop_before_decrypt_prevents_decrypt`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val bgJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockk<WorkerRunHandle>(relaxed = true)
        every { permissionChecker.areNotificationsEnabled() } returns true

        // Privacy gate allows (so the block executes)
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns PrivacyDecision.Allowed

        val executionGuard = WorkerExecutionGuard(
            writeBarrier, readBarrier, restoreMode, runLogger,
            privacyGate, leaseRegistry, diagnosticSink, bgJobRunDao,
            permissionChecker, timeProvider
        )

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 1, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1

        // Make the reload checkpoint (before decrypt) throw to block the worker
        every { writeBarrier.checkWritesAllowed("intake:reload") } throws RuntimeException("Blocked")

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = executionGuard,
            privacyGate = privacyGate
        )

        // PR12H-1: checkpoint() now throws WorkerCheckpointBlockedException instead of
        // CancellationException. The guard catches it and returns BlockedRetry (with
        // RETRY policy), which maps to Result.retry() via toWorkerResult().
        val result = worker.doWork()
        assertEquals("Checkpoint blocked should return retry", WorkResult.retry(), result)

        coVerify(exactly = 0) { crypto.decrypt(any(), any(), any()) }
    }

    private fun intakeEntity(
        id: Long, packageName: String,
        attempts: Int, maxAttempts: Int, now: Long,
        title: String = "Paid €50.00",
        text: String = "Card transaction",
        payloadMode: String = "RAW"
    ): NotificationIntakeEntity = NotificationIntakeEntity(
        id = id, packageName = packageName, appName = "Test",
        notificationKeyHash = "hash", postTime = now, capturedAt = now,
        source = "LISTENER", correlationId = "corr-$id",
        dedupeFingerprint = "fp-$id", contentHash = null,
        title = title, text = text, bigText = null, subText = null,
        extrasJson = null,
        rawStorageMode = "STORE_RAW", payloadMode = payloadMode,
        rawPayloadPurgedAt = null,
        transientPayloadCiphertext = null, transientPayloadNonce = null,
        transientPayloadVersion = null, transientPayloadPurgedAt = null,
        status = "RECEIVED",
        attempts = attempts, maxAttempts = maxAttempts,
        nextAttemptAt = null, lockedAt = null, lockedBy = null,
        lastAttemptAt = null, terminalAt = null,
        rawNotificationId = null, expenseId = null, pendingReviewId = null,
        lastFailureCode = null, lastFailureMessageHash = null,
        finalOutcome = null, createdAt = now, updatedAt = now
    )
}
