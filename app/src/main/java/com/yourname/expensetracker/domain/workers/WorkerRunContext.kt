package com.yourname.expensetracker.domain.workers

/**
 * Per-run context passed to workers via [WorkerExecutionGuard.runGuardedWithContext].
 * Accumulates counters that are written to [BackgroundJobRun] on completion.
 */
class WorkerRunContext internal constructor(
    private val checkpointDelegate: suspend (String) -> Unit
) {
    var rowsScanned: Int = 0; private set
    var rowsUpdated: Int = 0; private set
    var notificationsSent: Int = 0; private set
    var rowsSkipped: Int = 0; private set
    var errors: Int = 0; private set

    fun addRowsScanned(n: Int = 1) { rowsScanned += n }
    fun addRowsUpdated(n: Int = 1) { rowsUpdated += n }
    fun addRowsSkipped(n: Int = 1) { rowsSkipped += n }
    fun addNotificationsSent(n: Int = 1) { notificationsSent += n }
    fun addErrors(n: Int = 1) { errors += n }

    suspend fun checkpoint(label: String) = checkpointDelegate(label)
}
