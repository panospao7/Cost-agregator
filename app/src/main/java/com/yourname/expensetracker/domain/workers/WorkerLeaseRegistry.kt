package com.yourname.expensetracker.domain.workers

/**
 * Registry of active worker leases.
 * Maintenance operations use this to wait for all running workers to finish.
 */
interface WorkerLeaseRegistry {
    /** Acquire a lease for [workerName]. Must be released (closed) in a finally block. */
    suspend fun acquire(workerName: String): WorkerLease

    /** Signal all active leases to stop at their next checkpoint. */
    suspend fun requestStopAll(reason: String)

    /**
     * Wait until no leases are active or [timeoutMs] elapses.
     * @return true if all workers drained, false if timed out.
     */
    suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean
}
