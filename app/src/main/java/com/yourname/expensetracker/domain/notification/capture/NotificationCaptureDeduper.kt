package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory deduplication for notification capture.
 *
 * Provides atomic check-and-insert operations with TTL-based expiration
 * to prevent duplicate processing of the same notification content within
 * a configurable window. Uses SHA-256 content fingerprints and hashed
 * package/key identifiers to avoid storing raw text in the dedupe map.
 *
 * Time source: the injected [MonotonicTimeProvider] makes the window immune
 * to wall-clock jumps (NTP sync, manual clock changes) — a backward jump can
 * no longer suppress identical re-posts and a forward jump cannot disable
 * the window. Timestamps are stored internally in **nanoseconds**
 * ([MonotonicTimeProvider.nowNanos]); the public [windowMs] parameters stay
 * in milliseconds and are converted to nanos once per call.
 */
@Singleton
class NotificationCaptureDeduper @Inject constructor(
    private val timeProvider: MonotonicTimeProvider
) {
    private val entries = LinkedHashMap<String, Long>(100, 0.75f, true)
    private val lock = Any()

    /**
     * Atomically checks whether [key] is already in the dedupe cache within
     * [windowMs]. If not, inserts it and returns false (not a duplicate).
     * If already present and within window, returns true (duplicate).
     */
    fun tryStart(key: String, windowMs: Long): Boolean {
        val now = timeProvider.nowNanos()
        val windowNanos = windowMs * 1_000_000L
        synchronized(lock) {
            val last = entries[key]
            if (last != null && (now - last) < windowNanos) {
                return true // duplicate
            }
            entries[key] = now
            if (entries.size > MAX_ENTRIES) {
                // Remove oldest entry (access-order LinkedHashMap)
                entries.remove(entries.keys.first())
            }
            return false // not a duplicate
        }
    }

    /**
     * Remove a key from the dedupe cache (e.g. on error/cancellation).
     */
    fun remove(key: String) {
        synchronized(lock) {
            entries.remove(key)
        }
    }

    /**
     * Remove expired entries older than [maxAgeMs].
     */
    fun cleanupExpired(maxAgeMs: Long) {
        val now = timeProvider.nowNanos()
        val maxAgeNanos = maxAgeMs * 1_000_000L
        synchronized(lock) {
            entries.entries.removeIf { now - it.value > maxAgeNanos }
        }
    }

    /** Number of entries currently in the cache. */
    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        private const val MAX_ENTRIES = 1000
    }
}
