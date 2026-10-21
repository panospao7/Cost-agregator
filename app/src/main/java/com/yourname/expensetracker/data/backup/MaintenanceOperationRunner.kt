package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.workers.WorkerDrainController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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

/** In-flight counted writes did not drain within the bounded window; operation aborted. */
class WriteDrainTimeoutException :
    IllegalStateException("MAINTENANCE_WRITE_DRAIN_TIMEOUT")

@Singleton
class MaintenanceOperationRunner @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerDrain: WorkerDrainController,
    private val writeBarrier: DatabaseWriteBarrier
) {
    /**
     * Enters [mode], drains workers, then awaits in-flight counted writes. Throws
     * [WorkerDrainTimeoutException] on worker timeout if [failOnTimeout] is true. A write-drain
     * timeout ALWAYS exits maintenance and throws [WriteDrainTimeoutException] (fail closed,
     * independent of [failOnTimeout]). A re-entrant call from inside a counted write throws
     * `MAINTENANCE_DRAIN_REENTRANT` BEFORE the mode is entered. Any other failure or
     * cancellation after entry (including during the drains) exits maintenance exactly once
     * before propagating. Caller is responsible for calling [RestoreMaintenanceMode.exit] after
     * a successful return only.
     */
    suspend fun enterAndDrain(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        failOnTimeout: Boolean = true
    ) {
        writeBarrier.checkNotInsideCountedWrite(currentCoroutineContext())
        restoreMaintenanceMode.enter(mode)
        var drainSucceeded = false
        try {
            val drained = workerDrain.requestStopAndAwaitDrain(operationName)
            if (!drained && failOnTimeout) {
                throw WorkerDrainTimeoutException(operationName)
            }
            if (!drained) {
                Timber.w("MaintenanceOperationRunner: drain timed out for $operationName — proceeding")
            }
            if (!writeBarrier.awaitNoInFlightWrites(DatabaseWriteBarrier.WRITE_DRAIN_TIMEOUT_MS)) {
                throw WriteDrainTimeoutException()
            }
            drainSucceeded = true
        } finally {
            if (!drainSucceeded) {
                withContext(NonCancellable) {
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                }
            }
        }
    }

    /**
     * Runs [block] inside [mode] after the same worker + counted-write drain as [enterAndDrain]
     * (drain failures exit maintenance once and throw before [block] runs).
     */
    suspend fun <T> runExclusive(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        requireRestartAfterSuccess: Boolean = false,
        drainTimeoutPolicy: DrainTimeoutPolicy = DrainTimeoutPolicy.FAIL_OPERATION,
        block: suspend () -> T
    ): T {
        enterAndDrain(
            mode = mode,
            operationName = operationName,
            failOnTimeout = drainTimeoutPolicy == DrainTimeoutPolicy.FAIL_OPERATION
        )
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
