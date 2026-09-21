package com.yourname.expensetracker.domain.workers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-run context passed to workers via [WorkerExecutionGuard.runGuardedWithContext].
 * Accumulates counters that are written to [BackgroundJobRun] on completion.
 *
 * P9-PR1 (NEW-P9-003): Counters use AtomicInteger for thread safety under
 * concurrent coroutine access (e.g. parallel item processing).
 *
 * RP-16 16-B (D9): [snapshot] produces the immutable [WorkerRunCounters]
 * persisted with the terminal write. The guard invokes it inside the run
 * handle's terminal mutex, immediately before persistence, so the persisted
 * values are exactly the measured totals.
 */
class WorkerRunContext internal constructor(
    private val checkpointDelegate: suspend (String) -> Unit
) {
    private val _rowsScanned = AtomicInteger(0)
    private val _rowsUpdated = AtomicInteger(0)
    private val _notificationsSent = AtomicInteger(0)
    private val _rowsSkipped = AtomicInteger(0)
    private val _errors = AtomicInteger(0)

    /** Nullable measured totals (null = not supplied); guarded by [WorkerRunCounters] semantics. */
    @Volatile
    private var _partialFailureCount: Int? = null

    @Volatile
    private var _failedTargetCount: Int? = null

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

    /**
     * RP-16 16-B: Report a measured partial-failure count (e.g. targets that
     * failed transiently). Non-null = measured; the last write wins.
     */
    fun setPartialFailureCount(n: Int) { _partialFailureCount = n }

    /**
     * RP-16 16-B: Report a measured failed-target count. Non-null = measured;
     * the last write wins.
     */
    fun setFailedTargetCount(n: Int) { _failedTargetCount = n }

    /**
     * RP-16 16-B: Immutable terminal snapshot. Captured by the run handle
     * under the terminal mutex immediately before persistence.
     */
    fun snapshot(): WorkerRunCounters = WorkerRunCounters(
        rowsScanned = _rowsScanned.get(),
        rowsUpdated = _rowsUpdated.get(),
        notificationsSent = _notificationsSent.get(),
        rowsSkipped = _rowsSkipped.get(),
        errors = _errors.get(),
        partialFailureCount = _partialFailureCount,
        failedTargetCount = _failedTargetCount
    )

    suspend fun checkpoint(label: String) = checkpointDelegate(label)
}
