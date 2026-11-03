package com.yourname.expensetracker.service

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounds how many notification-capture pipelines run at once (P-01-3).
 *
 * Callers suspend until a permit is free; nothing is ever dropped. The wait is
 * cancellable and a cancelled waiter never takes a permit. The permit is always
 * released when the block completes, fails or is cancelled.
 *
 * Android-free so it can be unit-tested on the JVM.
 */
internal class NotificationCaptureConcurrencyLimiter(
    permits: Int = DEFAULT_PERMITS
) {
    init {
        require(permits > 0) { "permits must be positive" }
    }

    @PublishedApi
    internal val semaphore = Semaphore(permits)

    /** Permits currently free. Intended for tests and bounded diagnostics. */
    val availablePermits: Int get() = semaphore.availablePermits

    /**
     * Runs [block] while holding a permit. Inline so a non-local `return@label`
     * to an enclosing coroutine lambda keeps working.
     */
    suspend inline fun <T> withPermit(block: () -> T): T = semaphore.withPermit(block)

    companion object {
        /**
         * Enough parallelism to overlap I/O waits (gate, privacy settings, Room)
         * without letting a notification burst fan out unbounded coroutines.
         */
        const val DEFAULT_PERMITS = 4
    }
}
