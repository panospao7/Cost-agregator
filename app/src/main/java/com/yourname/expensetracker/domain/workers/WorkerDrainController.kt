package com.yourname.expensetracker.domain.workers

/**
 * Controls worker drain during maintenance operations.
 * Full implementation provided in PR 4 (WorkerLeaseRegistry).
 * This stub allows MaintenanceOperationRunner to compile and be tested now.
 */
interface WorkerDrainController {
    /**
     * Requests all active workers to stop and waits until they drain or timeout.
     * @return true if all workers drained within the timeout, false if timed out.
     */
    suspend fun requestStopAndAwaitDrain(operationName: String, timeoutMs: Long = 5_000): Boolean
}
