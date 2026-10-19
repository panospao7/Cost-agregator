package com.yourname.expensetracker.data.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Collections

/**
 * B4-K2 test seam: records the backoff delays requested by the Cloud*Service retry loops instead of
 * waiting in real time. [assertSchedule] pins the production schedule with literals (independent of
 * the CloudRetryPolicy constants): exponential 250ms, 500ms, 1000ms (cap 1500ms) plus 0..200ms jitter.
 */
internal class RecordingRetryDelay {
    private val recorded: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    val delays: List<Long> get() = synchronized(recorded) { recorded.toList() }

    /** Pass this as the `retryDelay` constructor argument of a Cloud*Service. */
    val delayer: suspend (Long) -> Unit = { recorded += it }

    /** Forget recorded delays so one instance can verify several independent calls. */
    fun reset() {
        recorded.clear()
    }

    fun assertNoRetries() {
        assertEquals("no retry delay must be requested", emptyList<Long>(), delays)
    }

    fun assertSchedule(expectedRetries: Int) {
        val actual = delays
        assertEquals("number of retry delays (attempts - 1)", expectedRetries, actual.size)
        actual.forEachIndexed { index, delayMs ->
            val base = EXPONENTIAL_BASES.getOrElse(index) { 1_500L }
            assertTrue(
                "retry ${index + 1} delay $delayMs must be in [$base, ${base + JITTER_MAX_MS}]",
                delayMs in base..(base + JITTER_MAX_MS)
            )
        }
    }

    private companion object {
        val EXPONENTIAL_BASES = listOf(250L, 500L, 1_000L)
        const val JITTER_MAX_MS = 200L
    }
}
