package com.yourname.expensetracker.domain.workers

import androidx.work.ListenableWorker
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.yield

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

fun <T> WorkerGuardResult<T>.toWorkerResult(): ListenableWorker.Result {
    return when (this) {
        is WorkerGuardResult.Success -> ListenableWorker.Result.success()
        is WorkerGuardResult.Skipped -> ListenableWorker.Result.success()
        is WorkerGuardResult.Retry -> ListenableWorker.Result.retry()
        is WorkerGuardResult.Failed -> ListenableWorker.Result.failure()
    }
}

@Singleton
class WorkerExecutionGuard @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerRunLogger: WorkerRunLogger,
    private val privacyGate: com.yourname.expensetracker.domain.privacy.PrivacyGate
) {
    suspend fun <T> runGuarded(
        request: WorkerGuardRequest,
        block: suspend () -> T
    ): WorkerGuardResult<T> {
        val run = workerRunLogger.start(request.workerName)
        try {
            if (!request.allowDuringBackupExport &&
                restoreMaintenanceMode.currentMode() == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
            ) {
                run.skipped("RESTORE_BLOCKED")
                return WorkerGuardResult.Skipped("Backup exporting")
            }
            writeBarrier.checkWritesAllowed(request.workerName)

            val spec = WorkerSpec.DEFAULTS[request.workerName]
            if (spec != null && !spec.enabled) {
                run.skipped("DISABLED")
                return WorkerGuardResult.Skipped("Worker disabled by spec")
            }

            for (capability in request.requiredCapabilities) {
                val decision = privacyGate.check(capability)
                when (decision) {
                    is com.yourname.expensetracker.domain.privacy.PrivacyDecision.Denied -> {
                        run.skipped("PRIVACY_$capability")
                        return WorkerGuardResult.Skipped("Privacy blocked: $capability - ${decision.reason}")
                    }
                    is com.yourname.expensetracker.domain.privacy.PrivacyDecision.FailClosed -> {
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
            if (isTransient) {
                run.retry(e.message ?: "Transient error", e)
                return WorkerGuardResult.Retry(e.message ?: "Transient error", e)
            } else {
                run.failure(e.message ?: "Permanent error", e)
                return WorkerGuardResult.Failed(e.message ?: "Permanent error", e)
            }
        }
    }

    /**
     * Checkpoint that long-running workers should call before each database write.
     *
     * Verifies that writes are still allowed (i.e. no restore/maintenance mode is
     * active) and that the worker has not been cancelled. This is a lightweight
     * check — call it periodically in long-running worker loops.
     */
    suspend fun checkpoint(operation: String) {
        writeBarrier.checkWritesAllowed(operation)
        yield()  // check for coroutine cancellation
    }

    private fun classifyTransient(e: Exception): Boolean {
        val message = e.message ?: ""
        return when {
            message.contains("Timeout") -> true
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