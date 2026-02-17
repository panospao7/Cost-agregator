package com.yourname.expensetracker.domain.util

/**
 * Abstraction for system time to enable deterministic testing.
 * 
 * Usage:
 * - Production: Inject SystemTimeProvider
 * - Tests: Inject FakeTimeProvider with fixed time
 * 
 * Benefits:
 * - Mockable time for unit tests
 * - Centralized time access
 * - Easier debugging (log time source)
 */
interface TimeProvider {
    /**
     * Returns the current time in milliseconds since epoch.
     * In production, this delegates to System.currentTimeMillis().
     * In tests, this can return a fixed value.
     */
    fun now(): Long
    
    /**
     * Returns the current time formatted for display.
     * Useful for debugging and logging.
     */
    fun nowFormatted(): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now()
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)+1}-${cal.get(java.util.Calendar.DAY_OF_MONTH)} " +
               "${cal.get(java.util.Calendar.HOUR_OF_DAY)}:${cal.get(java.util.Calendar.MINUTE)}"
    }
}
