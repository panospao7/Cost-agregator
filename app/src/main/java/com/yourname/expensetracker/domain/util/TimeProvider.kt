package com.yourname.expensetracker.domain.util

/**
 * The **single source of "now"** for the entire ExpenseTracker app.
 *
 * Every piece of production logic that needs the current time MUST obtain it
 * through this interface, NOT by calling `System.currentTimeMillis()`,
 * `Instant.now()`, `LocalDate.now()`, or `LocalDateTime.now()` directly.
 *
 * ## Usage
 *
 * - **Production**: Inject [SystemTimeProvider] (bound via Hilt in [TimeModule]).
 * - **Tests**: Inject [FakeTimeProvider] with a fixed, controllable time.
 *
 * ## Why this exists
 *
 * 1. **Deterministic testing** — tests can freeze time and assert behavior
 *    at any date (including DST transitions, leap days, etc.).
 * 2. **Centralized control** — one place to change how "now" is obtained
 *    (e.g. for logging, time-travel debugging, or replay modes).
 * 3. **Audit enforcement** — grep for `TimeProvider` usage vs. grep for
 *    `System.currentTimeMillis()` to find violations.
 *
 * ## Design note
 *
 * This interface only provides epoch milliseconds (`Long`). Callers that need
 * `LocalDate`, `Instant`, etc. should derive them from the returned value:
 * ```kotlin
 * val now = timeProvider.now()
 * val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
 * ```
 * This keeps the interface minimal and avoids baking timezone decisions into
 * the provider itself.
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
        return DateFormatterUtils.formatTimestampJavaTime(now(), "yyyy-MM-dd HH:mm")
    }
}
