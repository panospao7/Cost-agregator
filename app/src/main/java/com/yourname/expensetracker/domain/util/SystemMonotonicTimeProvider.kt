package com.yourname.expensetracker.domain.util

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Production implementation of [MonotonicTimeProvider].
 *
 * Delegates to [TimeSource.Monotonic] (backed by `System.nanoTime()` on
 * Android/JVM) so elapsed-duration measurements are immune to wall-clock
 * jumps. Uses a reference [TimeMark] captured at construction so
 * [nowNanos] returns a value measured against a stable origin.
 */
@Singleton
class SystemMonotonicTimeProvider @Inject constructor() : MonotonicTimeProvider {

    private val referenceMark: TimeMark = TimeSource.Monotonic.markNow()

    override fun nowNanos(): Long = referenceMark.elapsedNow().inWholeNanoseconds
}
