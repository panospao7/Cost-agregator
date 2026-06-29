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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class BlockedPolicy { RETRY, SKIP_SUCCESS, FAIL }
enum class PermissionPolicy { SKIP_SUCCESS, RETRY, FAIL }
enum class PrivacyPolicy { SKIP_SUCCESS, RETRY, FAIL }

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
    val allowDuringBackupExport: Boolean = false,
    val blockedPolicy: BlockedPolicy = BlockedPolicy.RETRY,
    val notificationPermissionPolicy: PermissionPolicy = PermissionPolicy.SKIP_SUCCESS,
    val privacyPolicy: PrivacyPolicy = PrivacyPolicy.SKIP_SUCCESS,
    // --- PR6B WorkManager metadata ---
    /** WorkManager work ID (UUID string from CoroutineWorker.id). */
    val workId: String? = null,
    /** WorkManager runAttemptCount. */
    val runAttemptCount: Int? = null,
    /** WorkerSpec version for this worker. */
    val specVersion: Int? = null
)

sealed interface WorkerGuardResult<out T> {
    data class Success<T>(val value: T) : WorkerGuardResult<T>
    data class Skipped(val reason: String) : WorkerGuardResult<Nothing>
    data class BlockedRetry(val reason: String, val blockedReasonCode: String) : WorkerGuardResult<Nothing>
    data class Retry(val reason: String, val error: Throwable? = null) : WorkerGuardResult<Nothing>
    data class Failed(val reason: String, val error: Throwable? = null) : WorkerGuardResult<Nothing>
}

fun <T> WorkerGuardResult<T>.toWorkerResult(): ListenableWorker.Result = when (this) {
    is WorkerGuardResult.Success -> ListenableWorker.Result.success()
    is WorkerGuardResult.Skipped -> ListenableWorker.Result.success()
    is WorkerGuardResult.BlockedRetry -> ListenableWorker.Result.retry()
    is WorkerGuardResult.Retry -> ListenableWorker.Result.retry()
    is WorkerGuardResult.Failed -> ListenableWorker.Result.failure()
}

class LeaseAcquisitionBlockedException(val workerName: String, message: String) : RuntimeException(message)

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
                    return applyBlockedPolicy(request, DiagnosticReasonCode.RESTORE_BLOCKED.name)
                }
            } else {
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return applyBlockedPolicy(request, DiagnosticReasonCode.RESTORE_BLOCKED.name)
            }
        } else if (request.requiresDatabaseWrite) {
            try {
                writeBarrier.checkWritesAllowed(request.workerName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return applyBlockedPolicy(request, DiagnosticReasonCode.WRITE_BARRIER_DENIED.name)
            }
        }

        val lease = try {
            leaseRegistry.acquire(request.workerName)
        } catch (e: LeaseAcquisitionBlockedException) {
            return applyBlockedPolicy(request, DiagnosticReasonCode.STOP_REQUESTED.name)
        }
        try {
            // Read-only backup path: no DB run logging
            if (allowedReadOnly) {
                val result = block()
                return WorkerGuardResult.Success(result)
            }

            val leaseId = lease.leaseId
            val run = when (val startResult = startRunSafely(request, leaseId)) {
                is StartRunResult.Started -> startResult.run
                is StartRunResult.Skipped -> return WorkerGuardResult.Skipped(startResult.reason)
                is StartRunResult.Blocked -> return applyBlockedPolicy(request, startResult.code)
                is StartRunResult.Retry -> return WorkerGuardResult.Retry(startResult.reason)
            }
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name) }
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name) }
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability - ${decision.reason}")
                        }
                        is PrivacyDecision.FailClosed -> {
                            withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name) }
                            return WorkerGuardResult.Skipped("Privacy check failed (fail-closed): $capability - ${decision.reason}")
                        }
                        else -> { }
                    }
                }

                if (request.requiresNotificationPermission && !notificationPermissionChecker.areNotificationsEnabled()) {
                    withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name) }
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
                }

                val result = block()
                withBoundedTerminalWrite { run.success() }
                return WorkerGuardResult.Success(result)
            } catch (e: Exception) {
                // P9-PR1 (NEW-P9-001): TimeoutCancellationException is retryable, not system cancel.
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    withBoundedTerminalWrite { run.retry("Timed out: ${e.message}", e) }
                    return WorkerGuardResult.Retry("Timed out: ${e.message}", e)
                }
                if (e is kotlinx.coroutines.CancellationException) {
                    withBoundedTerminalWrite { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                // P9-NEW-13: an explicit typed retry signal takes precedence over the
                // message-based heuristic, so a worker's retry intent is never lost when
                // its message matches none of the transient keywords. CancellationException
                // is already rethrown above (highest precedence); classifyTransient remains
                // the unchanged fallback for every other exception.
                return if (e is RetryableWorkerException || classifyTransient(e)) {
                    withBoundedTerminalWrite { run.retry(e.message ?: "Transient error", e) }
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    withBoundedTerminalWrite { run.failure(e.message ?: "Permanent error", e) }
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
                    return applyBlockedPolicy(request, DiagnosticReasonCode.RESTORE_BLOCKED.name)
                }
                val readOnlyCtx = WorkerRunContext(checkpointDelegate = { op ->
                    readBarrier.checkReadAllowed(
                        DatabaseAccessOperation(op, pipeline = "P9"),
                        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                    )
                    yield()
                })
                val lease = try {
                    leaseRegistry.acquire(request.workerName)
                } catch (e: LeaseAcquisitionBlockedException) {
                    return applyBlockedPolicy(request, DiagnosticReasonCode.STOP_REQUESTED.name)
                }
                return try {
                    WorkerGuardResult.Success(block(readOnlyCtx))
                } finally {
                    lease.close()
                }
            } else {
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
                return applyBlockedPolicy(request, DiagnosticReasonCode.RESTORE_BLOCKED.name)
            }
        }

        val ctx = WorkerRunContext(checkpointDelegate = ::checkpoint)
        val lease = try {
            leaseRegistry.acquire(request.workerName)
        } catch (e: LeaseAcquisitionBlockedException) {
            return applyBlockedPolicy(request, DiagnosticReasonCode.STOP_REQUESTED.name)
        }
        try {
            val leaseId = lease.leaseId
            val run = when (val startResult = startRunSafely(request, leaseId)) {
                is StartRunResult.Started -> startResult.run
                is StartRunResult.Skipped -> return WorkerGuardResult.Skipped(startResult.reason)
                is StartRunResult.Blocked -> return applyBlockedPolicy(request, startResult.code)
                is StartRunResult.Retry -> return WorkerGuardResult.Retry(startResult.reason)
            }
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name) }
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name) }
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability")
                        }
                        is PrivacyDecision.FailClosed -> {
                            withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name) }
                            return WorkerGuardResult.Skipped("Privacy fail-closed: $capability")
                        }
                        else -> { }
                    }
                }

                if (request.requiresNotificationPermission && !notificationPermissionChecker.areNotificationsEnabled()) {
                    withBoundedTerminalWrite { run.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name) }
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
                }

                val result = block(ctx)
                withBoundedTerminalWrite {
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
                    withBoundedTerminalWrite { run.retry("Timed out: ${e.message}", e) }
                    return WorkerGuardResult.Retry("Timed out: ${e.message}", e)
                }
                if (e is kotlinx.coroutines.CancellationException) {
                    withBoundedTerminalWrite { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                // P9-NEW-13: an explicit typed retry signal takes precedence over the
                // message-based heuristic, so a worker's retry intent is never lost when
                // its message matches none of the transient keywords. CancellationException
                // is already rethrown above (highest precedence); classifyTransient remains
                // the unchanged fallback for every other exception.
                return if (e is RetryableWorkerException || classifyTransient(e)) {
                    withBoundedTerminalWrite { run.retry(e.message ?: "Transient error", e) }
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    withBoundedTerminalWrite { run.failure(e.message ?: "Permanent error", e) }
                    WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
                }
            }
        } finally {
            lease.close()
        }
    }

    /**
     * PR8: Bounded terminal write helper — wraps terminal DB writes with a timeout
     * so that a blocked/locked database cannot hang the worker indefinitely during
     * shutdown or restore. The [NonCancellable] context is INSIDE the timeout so
     * that [withTimeout] can cancel its own child coroutine even while the outer
     * scope is being cancelled. If [withTimeout] were outside [NonCancellable], the
     * cancellation would be blocked and the timeout would be ineffective.
     */
    private suspend fun <T> withBoundedTerminalWrite(
        block: suspend () -> T
    ): T? {
        return withContext(NonCancellable) {
            try {
                withTimeout(TERMINAL_WRITE_TIMEOUT_MS) {
                    block()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w("Terminal write timed out after ${TERMINAL_WRITE_TIMEOUT_MS}ms — continuing")
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Terminal write failed")
                null
            }
        }
    }

    private sealed interface StartRunResult {
        data class Started(val run: WorkerRunHandle) : StartRunResult
        data class Skipped(val reason: String) : StartRunResult
        data class Blocked(val code: String) : StartRunResult
        data class Retry(val reason: String) : StartRunResult
    }

    /**
     * DDL-81-04: wraps workerRunLogger.start() so failures are classified, not raw exceptions.
     * U-WORKER-01: explicit write barrier check closes the TOCTOU race between the
     * mode pre-check at the top of runGuarded/runGuardedWithContext and the dao.insert()
     * inside workerRunLogger.start(). Without this, a mode transition between the two
     * could allow a write against a database about to be swapped.
     */
    private suspend fun startRunSafely(request: WorkerGuardRequest, leaseId: String? = null): StartRunResult {
        return try {
            writeBarrier.checkWritesAllowed("WorkerRunLogger.start:${request.workerName}")
            StartRunResult.Started(workerRunLogger.start(
                workerName = request.workerName,
                workId = request.workId,
                uniqueWorkName = request.workerName, // uniqueWorkName is the worker name for scheduling
                specVersion = request.specVersion,
                runAttempt = request.runAttemptCount,
                leaseId = leaseId
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            diagnosticSink.recordBlockedOperation(request.workerName, restoreMaintenanceMode.currentMode(), "P9",
                reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.WRITE_BARRIER_DENIED)
            StartRunResult.Blocked(DiagnosticReasonCode.WRITE_BARRIER_DENIED.name)
        } catch (e: Exception) {
            Timber.w(e, "WorkerExecutionGuard: failed to start run for ${request.workerName}")
            diagnosticSink.recordBlockedOperation(request.workerName, restoreMaintenanceMode.currentMode(), "P9")
            if (classifyTransient(e)) StartRunResult.Retry(e.message ?: "Transient start failure")
            else StartRunResult.Retry(DiagnosticReasonCode.UNKNOWN_ERROR.name)
        }
    }

    /**
     * PR12B: Uses CAS-based [BackgroundJobRunDao.staleAbortIfStillRunning] to atomically
     * transition stale RUNNING rows to STALE_ABORTED. The conditional WHERE clause
     * (status = 'RUNNING' AND startedAt < :staleThresholdMs) prevents overwriting a
     * real terminal state if recovery races with live completion.
     */
    suspend fun recoverStaleRunningJobs(staleThresholdMs: Long = timeProvider.now() - STALE_THRESHOLD_MS) {
        val stale = backgroundJobRunDao.getStaleRunningRuns(staleThresholdMs)
        var recovered = 0
        for (run in stale) {
            val affected = backgroundJobRunDao.staleAbortIfStillRunning(
                id = run.id,
                staleThresholdMs = staleThresholdMs,
                finishedAt = timeProvider.now(),
                statusReason = DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name,
                terminalReasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name
            )
            if (affected == 1) recovered++
        }
        if (recovered > 0) Timber.w("Recovered $recovered stale RUNNING job(s) as STALE_ABORTED")
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

    private fun applyBlockedPolicy(request: WorkerGuardRequest, code: String): WorkerGuardResult<Nothing> =
        when (request.blockedPolicy) {
            BlockedPolicy.RETRY -> WorkerGuardResult.BlockedRetry(code, code)
            BlockedPolicy.SKIP_SUCCESS -> WorkerGuardResult.Skipped(code)
            BlockedPolicy.FAIL -> WorkerGuardResult.Failed(code)
        }

    companion object {
        const val STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000L
        const val TERMINAL_WRITE_TIMEOUT_MS = 5_000L  // PR8: 5 seconds max for terminal write
    }
}
