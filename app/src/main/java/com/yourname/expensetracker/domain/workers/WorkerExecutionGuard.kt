package com.yourname.expensetracker.domain.workers

import androidx.work.ListenableWorker
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerRunLogger: WorkerRunLogger,
    private val privacyGate: PrivacyGate,
    private val leaseRegistry: WorkerLeaseRegistry
) {
    suspend fun <T> runGuarded(
        request: WorkerGuardRequest,
        block: suspend () -> T
    ): WorkerGuardResult<T> {
        // Check write barrier BEFORE acquiring a lease or logging a run record
        try {
            writeBarrier.checkWritesAllowed(request.workerName)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return WorkerGuardResult.Skipped("Write barrier denied: ${e.message}")
        }

        // Acquire lease — held for the entire worker execution lifetime
        val lease = leaseRegistry.acquire(request.workerName)
        val run = workerRunLogger.start(request.workerName)
        try {
            if (!request.allowDuringBackupExport &&
                restoreMaintenanceMode.currentMode() == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
            ) {
                run.skipped("RESTORE_BLOCKED")
                return WorkerGuardResult.Skipped("Backup exporting")
            }

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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Worker ${request.workerName} failed")
            val isTransient = classifyTransient(e)
            return if (isTransient) {
                run.retry(e.message ?: "Transient error", e)
                WorkerGuardResult.Retry(e.message ?: "Transient error", e)
            } else {
                run.failure(e.message ?: "Permanent error", e)
                WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
            }
        } finally {
            lease.close()
        }
    }

    /**
     * Checkpoint for long-running workers — delegates to the lease so the
     * registry's stop-requested flag is also checked.
     */
    suspend fun checkpoint(operation: String) {
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
