package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier

/**
 * A lease held by a running worker for its entire execution lifetime.
 * Acquired via [WorkerLeaseRegistry.acquire]; released in a finally block.
 */
interface WorkerLease : AutoCloseable {
    /**
     * Called before each DB mutation inside a worker.
     * Checks the write barrier and yields for cancellation.
     * Throws [com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException]
     * if writes are blocked.
     */
    suspend fun checkpoint(operation: String)

    /** Releases the lease. Idempotent. */
    override fun close()
}
