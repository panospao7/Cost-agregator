package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rollover-aware ticker that emits at calendar day boundaries.
 *
 * Consumers that need a "current period" flow (e.g., "this month's expenses")
 * should use [dayBoundaryTicks] with `flatMapLatest` to rebuild their query
 * whenever the calendar day changes.
 *
 * ## Behavior
 * 1. Emits immediately with the current time (so the first collection gets data right away).
 * 2. Sleeps until the **start of the next calendar day** (`00:00:00.000`), then emits again.
 * 3. Repeats step 2 indefinitely (or until the collector is cancelled).
 *
 * The delay is recalculated each iteration using [TimePeriodUtils.getEndOfDay],
 * which is DST-safe (calendar-aware `Calendar.add`). A small safety margin of
 * [MARGIN_MS] is added so we never wake up a few milliseconds *before* midnight
 * due to clock drift.
 *
 * ## Thread safety
 * This class is stateless; the [Flow] it returns is cold and can be collected
 * by any number of concurrent consumers without interference.
 *
 * ## Testing
 * Inject a [FakeTimeProvider][com.yourname.expensetracker.domain.util.FakeTimeProvider]
 * to control time in tests. Since the ticker calls [TimeProvider.now] on every
 * iteration, advancing the fake clock past midnight triggers the next emission.
 */
@Singleton
class TimeBoundaryTicker @Inject constructor(
    private val timeProvider: TimeProvider
) {

    companion object {
        /**
         * Small margin added after the computed next-day start to avoid waking
         * up a few milliseconds before midnight due to timer imprecision.
         */
        internal const val MARGIN_MS = 50L
    }

    /**
     * A cold [Flow] that emits the current timestamp at each calendar-day
     * boundary. The first emission is immediate.
     *
     * Typical usage:
     * ```
     * timeBoundaryTicker.dayBoundaryTicks()
     *     .flatMapLatest { now ->
     *         val (start, end) = TimePeriodUtils.getMonthRange(now)
     *         expenseDao.getExpensesBetweenFlow(start, end)
     *     }
     * ```
     */
    fun dayBoundaryTicks(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            val now = timeProvider.now()
            emit(now)

            // Sleep until the start of the next calendar day + a small margin.
            val nextDayStart = TimePeriodUtils.getEndOfDay(now)
            val sleepMs = (nextDayStart - now + MARGIN_MS).coerceAtLeast(1L)
            delay(sleepMs)
        }
    }
}
