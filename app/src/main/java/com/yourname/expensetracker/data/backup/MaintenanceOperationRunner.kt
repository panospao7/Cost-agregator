package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.workers.WorkerDrainController
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a destructive DB operation (restore / reset / backup) under exclusive
 * maintenance mode with worker drain.
 *
 * Guarantees:
 * - Maintenance mode is entered before any file operation.
 * - Workers are requested to stop and awaited before the block runs.
 * - On success with [requireRestartAfterSuccess]=true, exits to RESTORE_COMPLETE_RESTART_REQUIRED.
 * - On any exception, exits maintenance mode (to NORMAL unless restart is forced).
 */
@Singleton
class MaintenanceOperationRunner @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerDrain: WorkerDrainController
) {
    suspend fun <T> runExclusive(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        requireRestartAfterSuccess: Boolean = false,
        block: suspend () -> T
    ): T {
        restoreMaintenanceMode.enter(mode)
        val drained = workerDrain.requestStopAndAwaitDrain(operationName)
        if (!drained) {
            Timber.w("MaintenanceOperationRunner: worker drain timed out for $operationName — proceeding anyway")
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
