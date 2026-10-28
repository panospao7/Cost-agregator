package com.yourname.expensetracker.domain.privacy.applock

/**
 * The clock of the auto-lock timer (domain-spec 3.4): monotonic milliseconds that include deep sleep
 * (`elapsedRealtime` semantics). Wall-clock, time-zone and DST changes have no effect on it.
 *
 * This is deliberately not `MonotonicTimeProvider`: that port is backed by `System.nanoTime()`, which
 * does not advance during deep sleep on Android. Only differences between two reads are meaningful.
 */
interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}
