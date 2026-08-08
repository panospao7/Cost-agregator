package com.yourname.expensetracker.domain.util

/**
 * The **single source of elapsed (monotonic) time** for the entire app.
 *
 * Unlike [TimeProvider] (wall-clock epoch millis), this interface exposes a
 * monotonic clock whose values are only meaningful as **differences** between
 * two reads. It is intended for elapsed-duration measurement (performance /
 * diagnostics timing) where wall-clock jumps (NTP sync, user clock changes)
 * would corrupt the result.
 *
 * ## Usage
 *
 * - **Production**: Inject [SystemMonotonicTimeProvider] (bound via Hilt in
 *   [com.yourname.expensetracker.di.TimeModule]).
 * - **Tests**: Inject a fake (e.g. `FakeMonotonicTimeProvider`) with a
 *   controllable value.
 *
 * ## Why this exists
 *
 * 1. **Semantics** — elapsed durations require a monotonic source;
 *    `TimeProvider.now()` (wall clock) is for logical app time only.
 * 2. **Deterministic testing** — tests can freeze/advance the fake and assert
 *    exact duration behavior.
 * 3. **Audit enforcement** — production code never calls
 *    `System.nanoTime()` / `TimeSource.Monotonic` directly; it goes through
 *    this boundary.
 *
 * ## Design note
 *
 * The unit is **nanoseconds** (not millis) so callers can choose their own
 * precision when converting to display values without losing resolution.
 */
interface MonotonicTimeProvider {
    /**
     * Returns the current monotonic timestamp in nanoseconds since an
     * arbitrary, stable origin. Only differences between two calls are
     * meaningful.
     */
    fun nowNanos(): Long
}
