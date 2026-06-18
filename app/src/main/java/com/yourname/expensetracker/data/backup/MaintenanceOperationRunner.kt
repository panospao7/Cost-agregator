package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.workers.WorkerDrainController
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class DrainTimeoutPolicy {
    /** Throw [WorkerDrainTimeoutException] if workers do not drain in time. */
    FAIL_OPERATION,
    /** Log a warning and proceed anyway. */
    PROCEED_WITH_WARNING
}

class WorkerDrainTimeoutException(operationName: String) :
    IllegalStateException("Worker drain timed out before $operationName — operation aborted")

@Singleton
class MaintenanceOperationRunner @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerDrain: WorkerDrainController
) {
    /**
     * Enters [mode] and drains workers. Throws [WorkerDrainTimeoutException] on timeout
     * if [failOnTimeout] is true. Caller is responsible for calling [RestoreMaintenanceMode.exit].
     */
    suspend fun enterAndDrain(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        failOnTimeout: Boolean = true
    ) {
        restoreMaintenanceMode.enter(mode)
        val drained = workerDrain.requestStopAndAwaitDrain(operationName)
        if (!drained && failOnTimeout) {
            restoreMaintenanceMode.exit(forceRestartRequired = false)
            throw WorkerDrainTimeoutException(operationName)
        }
        if (!drained) {
            Timber.w("MaintenanceOperationRunner: drain timed out for $operationName — proceeding")
        }
    }

    suspend fun <T> runExclusive(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        requireRestartAfterSuccess: Boolean = false,
        drainTimeoutPolicy: DrainTimeoutPolicy = DrainTimeoutPolicy.FAIL_OPERATION,
        block: suspend () -> T
    ): T {
        restoreMaintenanceMode.enter(mode)
        val drained = workerDrain.requestStopAndAwaitDrain(operationName)
        if (!drained) {
            when (drainTimeoutPolicy) {
                DrainTimeoutPolicy.FAIL_OPERATION -> {
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    throw WorkerDrainTimeoutException(operationName)
                }
                DrainTimeoutPolicy.PROCEED_WITH_WARNING ->
                    Timber.w("MaintenanceOperationRunner: drain timed out for $operationName — proceeding")
            }
        }
        return try {
            val result = block()
            restoreMaintenanceMode.exit(forceRestartRequired = requireRestartAfterSuccess)
            result
        } catch (t: Throwable) {
            restoreMaintenanceMode.exit(forceRestartRequired = requireRestartAfterSuccess)
            throw t
        }
    }
}
