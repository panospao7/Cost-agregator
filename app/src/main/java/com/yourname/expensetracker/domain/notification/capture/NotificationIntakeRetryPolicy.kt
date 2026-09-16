package com.yourname.expensetracker.domain.notification.capture

/**
 * RP-10 10b (P1-003): the single retry-backoff ladder for notification intake.
 * The worker's per-attempt retry scheduling and the enqueue-failure transition
 * both derive nextAttemptAt from here — one source, no drift.
 */
object NotificationIntakeRetryPolicy {

    /** Backoff by attempt number (1-based), mirroring the worker's ladder. */
    private val LADDER = longArrayOf(
        30_000L,      // 30s
        120_000L,     // 2m
        600_000L,     // 10m
        1_800_000L,   // 30m
        3_600_000L    // 1h
    )

    fun backoffFor(attempt: Int): Long =
        LADDER[(attempt - 1).coerceIn(0, LADDER.lastIndex)]
}
