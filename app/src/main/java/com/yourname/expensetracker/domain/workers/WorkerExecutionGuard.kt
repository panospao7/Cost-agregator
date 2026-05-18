package com.yourname.expensetracker.domain.workers

import androidx.work.ListenableWorker
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import kotlinx.coroutines.yield
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class WorkerGuardRequest(
    val workerName: String,
    val requiredCapabilities: List<PrivacyCapability> = emptyList(),
    val requiresNotificationPermission: Boolean = false,
    /** If true, this worker writes to the DB and must be blocked in all non-NORMAL modes. */
    val requiresDatabaseWrite: Boolean = true,
    /** If true AND requiresDatabaseWrite=false, allow read-only work during BACKUP_EXPORTING. */
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
    private val diagnosticSink: MaintenanceSafeDiagnosticSink
) {
    suspend fun <T> runGuarded(
        request: WorkerGuardRequest,
        block: suspend () -> T
    ): WorkerGuardResult<T> {
        val mode = restoreMaintenanceMode.currentMode()
        val allowedReadOnly = mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING &&
            request.allowDuringBackupExport &&
            !request.requiresDatabaseWrite

        // Barrier check — before acquiring lease or logging a run
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            if (allowedReadOnly) {
                try {
                    readBarrier.checkReadAllowed(
                        DatabaseAccessOperation(request.workerName, pipeline = "P9"),
                        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9")
                    return WorkerGuardResult.Skipped("Read barrier denied during backup: ${e.message}")
                }
            } else {
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9")
                return WorkerGuardResult.Skipped("Blocked in mode $mode")
            }
        } else if (request.requiresDatabaseWrite) {
            try {
                writeBarrier.checkWritesAllowed(request.workerName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9")
                return WorkerGuardResult.Skipped("Write barrier denied: ${e.message}")
            }
        }

        val lease = leaseRegistry.acquire(request.workerName)
        try {
            // Read-only backup-export path: no Room logging (BackgroundJobRun is a DB write)
            if (allowedReadOnly) {
                val result = block()
                return WorkerGuardResult.Success(result)
            }

            // Normal path: start run log inside try so lease is always released
            val run = workerRunLogger.start(request.workerName)
            try {
                val spec = WorkerSpec.DEFAULTS[request.workerName]
                if (spec != null && !spec.enabled) {
                    run.skipped("DISABLED")
                    return WorkerGuardResult.Skipped("Worker disabled by spec")
                }

                for (capability in request.requiredCapabilities) {
                    when (val decision = privacyGate.check(capability)) {
                        is PrivacyDecision.Denied -> {
                            run.skipped("PRIVACY_$capability")
                            return WorkerGuardResult.Skipped("Privacy blocked: $capability - ${decision.reason}")
                        }
                        is PrivacyDecision.FailClosed -> {
                            run.skipped("PRIVACY_FAIL_CLOSED_$capability")
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
                    run.cancelled("coroutine_cancelled_or_maintenance_stop")
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
}
