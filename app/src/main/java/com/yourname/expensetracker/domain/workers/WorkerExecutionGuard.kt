package com.yourname.expensetracker.domain.workers

import androidx.work.ListenableWorker
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class WorkerGuardRequest(
    val workerName: String,
    val requiredCapabilities: List<PrivacyCapability> = emptyList(),
    /**
     * When true, the guard verifies notification permission before running the
     * block. If notifications are disabled, the run is durably recorded as
     * SKIPPED with statusReason [DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED]
     * and the block never executes. Enforced by [WorkerExecutionGuard] via
     * [NotificationPermissionChecker].
     */
    val requiresNotificationPermission: Boolean = false,
    val requiresDatabaseWrite: Boolean = true,
    val allowDuringBackupExport: Boolean = false
)

sealed interface WorkerGuardResult<out T> {
    data class Success<T>(val value: T) : WorkerGuardResult<T>
    data class Skipped(val reason: String) : WorkerGuardResult<Nothing>
    data class Retry(val reason: String, val error: Throwable? = null) : WorkerGuardResult<Nothing>
    data class Failed(val reason: String, val error: Throwable? = null) : WorkerGuardResult<Nothing>
}

fun <T> WorkerGuardResult<T>.toWorkerResult(): ListenableWorker.Result = when (this) {
    is WorkerGuardResult.Success -> ListenableWorker.Result.success()
    is WorkerGuardResult.Skipped -> ListenableWorker.Result.success()
    is WorkerGuardResult.Retry -> ListenableWorker.Result.retry()
    is WorkerGuardResult.Failed -> ListenableWorker.Result.failure()
}

@Singleton
class WorkerExecutionGuard @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val readBarrier: DatabaseReadBarrier,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerRunLogger: WorkerRunLogger,
    private val privacyGate: PrivacyGate,
    private val leaseRegistry: WorkerLeaseRegistry,
    private val diagnosticSink: MaintenanceSafeDiagnosticSink,
    private val backgroundJobRunDao: BackgroundJobRunDao,
    private val notificationPermissionChecker: NotificationPermissionChecker,
    private val timeProvider: TimeProvider
) {
    suspend fun <T> runGuarded(
        request: WorkerGuardRequest,
        block: suspend () -> T
    ): WorkerGuardResult<T> {
        val mode = restoreMaintenanceMode.currentMode()
        val allowedReadOnly = mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING &&
            request.allowDuringBackupExport &&
            !request.requiresDatabaseWrite

        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            if (allowedReadOnly) {
                try {
                    readBarrier.checkReadAllowed(
                        DatabaseAccessOperation(request.workerName, pipeline = "P9"),
                        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                        reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
                }
            } else {
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
            }
        } else if (request.requiresDatabaseWrite) {
            try {
                writeBarrier.checkWritesAllowed(request.workerName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return WorkerGuardResult.Skipped(DiagnosticReasonCode.WRITE_BARRIER_DENIED.name)
            }
        }

        val lease = leaseRegistry.acquire(request.workerName)
        try {
            // Read-only backup path: no DB run logging
            if (allowedReadOnly) {
                val result = block()
                return WorkerGuardResult.Success(result)
            }

            val run = when (val startResult = startRunSafely(request)) {
                is StartRunResult.Started -> startResult.run
                is StartRunResult.Skipped -> return WorkerGuardResult.Skipped(startResult.reason)
                is StartRunResult.Retry -> return WorkerGuardResult.Retry(startResult.reason)
            }
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name) }
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name) }
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability - ${decision.reason}")
                        }
                        is PrivacyDecision.FailClosed -> {
                            withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name) }
                            return WorkerGuardResult.Skipped("Privacy check failed (fail-closed): $capability - ${decision.reason}")
                        }
                        else -> { }
                    }
                }

                if (request.requiresNotificationPermission && !notificationPermissionChecker.areNotificationsEnabled()) {
                    withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name) }
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
                }

                val result = block()
                withContext(NonCancellable) { run.success() }
                return WorkerGuardResult.Success(result)
            } catch (e: Exception) {
                // P9-PR1 (NEW-P9-001): TimeoutCancellationException is retryable, not system cancel.
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    withContext(NonCancellable) { run.retry("Timed out: ${e.message}", e) }
                    return WorkerGuardResult.Retry("Timed out: ${e.message}", e)
                }
                if (e is kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                // P9-NEW-13: an explicit typed retry signal takes precedence over the
                // message-based heuristic, so a worker's retry intent is never lost when
                // its message matches none of the transient keywords. CancellationException
                // is already rethrown above (highest precedence); classifyTransient remains
                // the unchanged fallback for every other exception.
                return if (e is RetryableWorkerException || classifyTransient(e)) {
                    withContext(NonCancellable) { run.retry(e.message ?: "Transient error", e) }
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    withContext(NonCancellable) { run.failure(e.message ?: "Permanent error", e) }
                    WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
                }
            }
        } finally {
            lease.close()
        }
    }

    /**
     * P9 (NEW-P9-013): The read-only path (checking if writes are allowed) is
     * wrapped in try-catch. If an exception occurs, log and fail-safe by
     * throwing a [kotlinx.coroutines.CancellationException] to stop the worker
     * rather than allowing it to proceed with an unknown barrier state.
     */
    suspend fun checkpoint(operation: String) {
        if (leaseRegistry.isStopRequested()) {
            throw kotlinx.coroutines.CancellationException(
                "Worker cancelled at checkpoint '$operation' — maintenance stop requested"
            )
        }
        try {
            writeBarrier.checkWritesAllowed(operation)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Checkpoint '$operation' failed — blocking writes")
            diagnosticSink.recordBlockedOperation(
                operation,
                restoreMaintenanceMode.currentMode(),
                "P9"
            )
            throw kotlinx.coroutines.CancellationException(
                "Writes blocked at checkpoint '$operation': ${e.message}"
            )
        }
        yield()
    }

    suspend fun <T> runGuardedWithContext(
        request: WorkerGuardRequest,
        block: suspend (WorkerRunContext) -> T
    ): WorkerGuardResult<T> {
        val mode = restoreMaintenanceMode.currentMode()
        val allowedReadOnly = mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING &&
            request.allowDuringBackupExport &&
            !request.requiresDatabaseWrite

        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            if (allowedReadOnly) {
                // Read-only backup path: use read barrier checkpoint, no DB run logging
                try {
                    readBarrier.checkReadAllowed(
                        DatabaseAccessOperation(request.workerName, pipeline = "P9"),
                        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                        reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
                }
                val readOnlyCtx = WorkerRunContext(checkpointDelegate = { op ->
                    readBarrier.checkReadAllowed(
                        DatabaseAccessOperation(op, pipeline = "P9"),
                        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                    )
                    yield()
                })
                val lease = leaseRegistry.acquire(request.workerName)
                return try {
                    WorkerGuardResult.Success(block(readOnlyCtx))
                } finally {
                    lease.close()
                }
            } else {
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
            }
        }

        val ctx = WorkerRunContext(checkpointDelegate = ::checkpoint)
        val lease = leaseRegistry.acquire(request.workerName)
        try {
            val run = when (val startResult = startRunSafely(request)) {
                is StartRunResult.Started -> startResult.run
                is StartRunResult.Skipped -> return WorkerGuardResult.Skipped(startResult.reason)
                is StartRunResult.Retry -> return WorkerGuardResult.Retry(startResult.reason)
            }
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name) }
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name) }
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability")
                        }
                        is PrivacyDecision.FailClosed -> {
                            withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name) }
                            return WorkerGuardResult.Skipped("Privacy fail-closed: $capability")
                        }
                        else -> { }
                    }
                }

                if (request.requiresNotificationPermission && !notificationPermissionChecker.areNotificationsEnabled()) {
                    withContext(NonCancellable) { run.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name) }
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
                }

                val result = block(ctx)
                withContext(NonCancellable) {
                    val noWork = ctx.rowsScanned == 0 && ctx.rowsUpdated == 0 && ctx.notificationsSent == 0
                    run.success(
                        rowsScanned = ctx.rowsScanned,
                        rowsUpdated = ctx.rowsUpdated,
                        notificationsSent = ctx.notificationsSent,
                        message = if (noWork) "NO_WORK" else null
                    )
                }
                return WorkerGuardResult.Success(result)
            } catch (e: Exception) {
                // P9-PR1 (NEW-P9-001): TimeoutCancellationException is retryable, not system cancel.
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    withContext(NonCancellable) { run.retry("Timed out: ${e.message}", e) }
                    return WorkerGuardResult.Retry("Timed out: ${e.message}", e)
                }
                if (e is kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                // P9-NEW-13: an explicit typed retry signal takes precedence over the
                // message-based heuristic, so a worker's retry intent is never lost when
                // its message matches none of the transient keywords. CancellationException
                // is already rethrown above (highest precedence); classifyTransient remains
                // the unchanged fallback for every other exception.
                return if (e is RetryableWorkerException || classifyTransient(e)) {
                    withContext(NonCancellable) { run.retry(e.message ?: "Transient error", e) }
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    withContext(NonCancellable) { run.failure(e.message ?: "Permanent error", e) }
                    WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
                }
            }
        } finally {
            lease.close()
        }
    }

    private sealed interface StartRunResult {
        data class Started(val run: WorkerRunHandle) : StartRunResult
        data class Skipped(val reason: String) : StartRunResult
        data class Retry(val reason: String) : StartRunResult
    }

    /**
     * DDL-81-04: wraps workerRunLogger.start() so failures are classified, not raw exceptions.
     * U-WORKER-01: explicit write barrier check closes the TOCTOU race between the
     * mode pre-check at the top of runGuarded/runGuardedWithContext and the dao.insert()
     * inside workerRunLogger.start(). Without this, a mode transition between the two
     * could allow a write against a database about to be swapped.
     */
    private suspend fun startRunSafely(request: WorkerGuardRequest): StartRunResult {
        return try {
            writeBarrier.checkWritesAllowed("WorkerRunLogger.start:${request.workerName}")
            StartRunResult.Started(workerRunLogger.start(request.workerName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            diagnosticSink.recordBlockedOperation(request.workerName, restoreMaintenanceMode.currentMode(), "P9",
                reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.WRITE_BARRIER_DENIED)
            StartRunResult.Skipped(DiagnosticReasonCode.WRITE_BARRIER_DENIED.name)
        } catch (e: Exception) {
            Timber.w(e, "WorkerExecutionGuard: failed to start run for ${request.workerName}")
            diagnosticSink.recordBlockedOperation(request.workerName, restoreMaintenanceMode.currentMode(), "P9")
            if (classifyTransient(e)) StartRunResult.Retry(e.message ?: "Transient start failure")
            else StartRunResult.Retry(DiagnosticReasonCode.UNKNOWN_ERROR.name)
        }
    }

    suspend fun recoverStaleRunningJobs(staleThresholdMs: Long = timeProvider.now() - STALE_THRESHOLD_MS) {
        val stale = backgroundJobRunDao.getStaleRunningRuns(staleThresholdMs)
        for (run in stale) {
            backgroundJobRunDao.update(
                run.copy(
                    status = "STALE_ABORTED",
                    finishedAt = timeProvider.now(),
                    statusReason = DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name
                )
            )
        }
        if (stale.isNotEmpty()) Timber.w("Recovered ${stale.size} stale RUNNING job(s) as STALE_ABORTED")
    }

    private fun classifyTransient(e: Exception): Boolean {
        val message = e.message ?: ""
        return when {
            message.contains("timeout", ignoreCase = true) -> true
            message.contains("interrupted", ignoreCase = true) -> true
            message.contains("deadlock", ignoreCase = true) -> true
            message.contains("SQLITE_BUSY", ignoreCase = true) -> true
            message.contains("database is locked", ignoreCase = true) -> true
            e is java.io.IOException -> true
            // TimeoutCancellationException removed: cancellation is caught before this
            else -> false
        }
    }

    companion object {
        const val STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000L
    }
}
