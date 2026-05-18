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
import kotlinx.coroutines.yield
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class WorkerGuardRequest(
    val workerName: String,
    val requiredCapabilities: List<PrivacyCapability> = emptyList(),
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
                    diagnosticSink.recordBlockedOperation(
                        request.workerName, mode, "P9",
                        reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS
                    )
                    return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
                }
            } else {
                diagnosticSink.recordBlockedOperation(
                    request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS
                )
                return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
            }
        } else if (request.requiresDatabaseWrite) {
            try {
                writeBarrier.checkWritesAllowed(request.workerName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                diagnosticSink.recordBlockedOperation(
                    request.workerName, mode, "P9",
                    reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS
                )
                return WorkerGuardResult.Skipped(DiagnosticReasonCode.WRITE_BARRIER_DENIED.name)
            }
        }

        val lease = leaseRegistry.acquire(request.workerName)
        try {
            if (allowedReadOnly) {
                val result = block()
                return WorkerGuardResult.Success(result)
            }

            val run = workerRunLogger.start(request.workerName)
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name)
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name)
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability - ${decision.reason}")
                        }
                        is PrivacyDecision.FailClosed -> {
                            run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name)
                            return WorkerGuardResult.Skipped("Privacy check failed (fail-closed): $capability - ${decision.reason}")
                        }
                        else -> { }
                    }
                }

                val result = block()
                run.success()
                return WorkerGuardResult.Success(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Finalize CANCELLED before rethrowing — plan requirement
                    run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                return if (classifyTransient(e)) {
                    run.retry(e.message ?: "Transient error", e)
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    run.failure(e.message ?: "Permanent error", e)
                    WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
                }
            }
        } finally {
            lease.close()
        }
    }

    suspend fun checkpoint(operation: String) {
        if (leaseRegistry.isStopRequested()) {
            throw kotlinx.coroutines.CancellationException(
                "Worker cancelled at checkpoint '$operation' — maintenance stop requested"
            )
        }
        writeBarrier.checkWritesAllowed(operation)
        yield()
    }

    /**
     * Like [runGuarded] but provides a [WorkerRunContext] to the block.
     * Counters accumulated in the context are written to [BackgroundJobRun] on success.
     */
    suspend fun <T> runGuardedWithContext(
        request: WorkerGuardRequest,
        block: suspend (WorkerRunContext) -> T
    ): WorkerGuardResult<T> {
        val ctx = WorkerRunContext(checkpointDelegate = ::checkpoint)
        val mode = restoreMaintenanceMode.currentMode()
        val allowedReadOnly = mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING &&
            request.allowDuringBackupExport &&
            !request.requiresDatabaseWrite

        if (mode != RestoreMaintenanceMode.Mode.NORMAL && !allowedReadOnly) {
            diagnosticSink.recordBlockedOperation(
                request.workerName, mode, "P9",
                reason = com.yourname.expensetracker.data.backup.MaintenanceBlockedReason.RESTORE_IN_PROGRESS
            )
            return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        }

        val lease = leaseRegistry.acquire(request.workerName)
        try {
            val run = workerRunLogger.start(request.workerName)
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    run.skipped(DiagnosticReasonCode.PROVIDER_DISABLED.name)
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            run.skipped(DiagnosticReasonCode.PRIVACY_DENIED.name)
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability")
                        }
                        is PrivacyDecision.FailClosed -> {
                            run.skipped(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name)
                            return WorkerGuardResult.Skipped("Privacy fail-closed: $capability")
                        }
                        else -> { }
                    }
                }

                val result = block(ctx)
                run.success(
                    rowsScanned = ctx.rowsScanned,
                    rowsUpdated = ctx.rowsUpdated,
                    notificationsSent = ctx.notificationsSent
                )
                return WorkerGuardResult.Success(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)
                    throw e
                }
                Timber.w(e, "Worker ${request.workerName} failed")
                return if (classifyTransient(e)) {
                    run.retry(e.message ?: "Transient error", e)
                    WorkerGuardResult.Retry(e.message ?: "Transient error", e)
                } else {
                    run.failure(e.message ?: "Permanent error", e)
                    WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
                }
            }
        } finally {
            lease.close()
        }
    }

    /**
     * Marks any RUNNING rows older than [staleThresholdMs] as STALE_ABORTED.
     * Call from app startup or a periodic maintenance worker.
     */
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
        if (stale.isNotEmpty()) {
            Timber.w("Recovered ${stale.size} stale RUNNING job(s) as STALE_ABORTED")
        }
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
            e is kotlinx.coroutines.TimeoutCancellationException -> true
            else -> false
        }
    }

    companion object {
        /** Runs older than 4 hours are considered stale. */
        const val STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000L
    }
}
