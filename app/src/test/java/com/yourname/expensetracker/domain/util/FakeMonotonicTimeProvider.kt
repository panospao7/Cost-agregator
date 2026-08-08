package com.yourname.expensetracker.domain.util

/**
 * Fake implementation of [MonotonicTimeProvider] for testing.
 * Returns a controllable monotonic timestamp so elapsed-duration
 * diagnostics can be asserted deterministically.
 */
class FakeMonotonicTimeProvider(
    private var fixedNanos: Long = 0L
) : MonotonicTimeProvider {

    override fun nowNanos(): Long = fixedNanos

    /** Sets the exact monotonic value (in nanoseconds). */
    fun setNanos(value: Long) {
        fixedNanos = value
    }

    /** Advances the monotonic value by the given amount (in nanoseconds). */
    fun advanceNanos(delta: Long) {
        fixedNanos += delta
    }

    /** Advances the monotonic value by the given amount (in milliseconds). */
    fun advanceMillis(millis: Long) {
        fixedNanos += millis * 1_000_000L
    }
}
