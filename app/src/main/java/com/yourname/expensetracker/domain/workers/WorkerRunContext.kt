package com.yourname.expensetracker.domain.workers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-run context passed to workers via [WorkerExecutionGuard.runGuardedWithContext].
 * Accumulates counters that are written to [BackgroundJobRun] on completion.
 *
 * P9-PR1 (NEW-P9-003): Counters use AtomicInteger for thread safety under
 * concurrent coroutine access (e.g. parallel item processing).
 */
class WorkerRunContext internal constructor(
    private val checkpointDelegate: suspend (String) -> Unit
) {
    private val _rowsScanned = AtomicInteger(0)
    private val _rowsUpdated = AtomicInteger(0)
    private val _notificationsSent = AtomicInteger(0)
    private val _rowsSkipped = AtomicInteger(0)
    private val _errors = AtomicInteger(0)

    val rowsScanned: Int get() = _rowsScanned.get()
    val rowsUpdated: Int get() = _rowsUpdated.get()
    val notificationsSent: Int get() = _notificationsSent.get()
    val rowsSkipped: Int get() = _rowsSkipped.get()
    val errors: Int get() = _errors.get()

    fun addRowsScanned(n: Int = 1) { _rowsScanned.addAndGet(n) }
    fun addRowsUpdated(n: Int = 1) { _rowsUpdated.addAndGet(n) }
    fun addRowsSkipped(n: Int = 1) { _rowsSkipped.addAndGet(n) }
    fun addNotificationsSent(n: Int = 1) { _notificationsSent.addAndGet(n) }
    fun addErrors(n: Int = 1) { _errors.addAndGet(n) }

    suspend fun checkpoint(label: String) = checkpointDelegate(label)
}
