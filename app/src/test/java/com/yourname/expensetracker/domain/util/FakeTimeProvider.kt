package com.yourname.expensetracker.domain.util

/**
 * Fake implementation of TimeProvider for testing.
 * Returns a fixed timestamp that can be controlled in tests.
 * 
 * Usage:
 * ```
 * val fakeTime = FakeTimeProvider(fixedTime = 1234567890000L)
 * val viewModel = HomeViewModel(timeProvider = fakeTime, ...)
 * ```
 */
class FakeTimeProvider(
    private var fixedTime: Long = 0L
) : TimeProvider {
    
    override fun now(): Long = fixedTime
    
    /**
     * Updates the fixed time. Useful for testing time progression.
     */
    fun setTime(timestamp: Long) {
        fixedTime = timestamp
    }
    
    /**
     * Advances time by the given amount in milliseconds.
     */
    fun advanceTime(millis: Long) {
        fixedTime += millis
    }
    
    companion object {
        /**
         * Creates a FakeTimeProvider with a specific date/time.
         *
         * Example:
         * ```
         * // Jan 15, 2026 at 10:30
         * val t1 = FakeTimeProvider.forDate(2026, 1, 15, 10, 30)
         *
         * // Jan 15, 2026 at midnight (00:00)
         * val t2 = FakeTimeProvider.forDate(2026, 1, 15)
         * ```
         */
        fun forDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): FakeTimeProvider {
            val cal = java.util.Calendar.getInstance()
            cal.set(year, month - 1, day, hour, minute, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return FakeTimeProvider(cal.timeInMillis)
        }

        /**
         * Creates a FakeTimeProvider with a specific date at midnight (00:00).
         *
         * Convenience wrapper around [forDate] that defaults hour and minute to 0.
         *
         * Example:
         * ```
         * val t = FakeTimeProvider.forDate(2026, 1, 15)   // Jan 15, 2026 00:00
         * ```
         */
        fun forDate(year: Int, month: Int, day: Int): FakeTimeProvider {
            return forDate(year, month, day, 0, 0)
        }
    }
}
