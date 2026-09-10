package com.yourname.expensetracker.domain.util

import java.util.concurrent.locks.ReentrantLock

/**
 * Process-wide test lock that serializes tests which mutate the JVM-wide default
 * timezone via [java.util.TimeZone.setDefault].
 *
 * Unit tests can run in parallel across classes. Because TimeZone.setDefault changes
 * global JVM state shared by every thread, any test that switches the zone must hold
 * this lock for the whole duration of the mutation (set, assertions, restore) so that
 * concurrent tests never observe a foreign default zone.
 *
 * Usage:
 * ```
 * @Test
 * fun `zone dependent test`() = GlobalTimeZoneTestLock.withLock {
 *     val originalTz = TimeZone.getDefault()
 *     try {
 *         TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
 *         // ... assertions ...
 *     } finally {
 *         TimeZone.setDefault(originalTz)
 *     }
 * }
 * ```
 */
object GlobalTimeZoneTestLock {

    private val lock = ReentrantLock()

    /**
     * Runs [block] while holding the process-wide timezone lock.
     *
     * The lock is always released, even when [block] throws, so a failing test never
     * leaves the global zone lock held for other parallel test classes.
     */
    fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Suspend variant of [withLock] for tests that need to call suspend
     * functions (e.g. DAO/cash-flow/dashboard calls) while holding the
     * process-wide timezone lock. The lock is always released, even when
     * [block] throws or suspends, so a failing test never leaves the global
     * zone lock held for other parallel test classes.
     */
    suspend fun <T> withLockSuspend(block: suspend () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Acquires the process-wide timezone lock, blocking until it is available. */
    fun acquire() {
        lock.lock()
    }

    /** Releases the process-wide timezone lock. Must be called after [acquire]. */
    fun release() {
        lock.unlock()
    }
}
