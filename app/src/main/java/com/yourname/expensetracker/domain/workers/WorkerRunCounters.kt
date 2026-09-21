package com.yourname.expensetracker.domain.workers

/**
 * RP-16 16-B (decision D9): Immutable terminal counter snapshot for a worker run.
 *
 * Produced by [WorkerRunContext.snapshot] and persisted as REAL DURABLE Room
 * columns on `background_job_runs` via the terminal write (scalar arguments
 * only — never a Room data object or `COALESCE(:counters, 0)`).
 *
 * Measurement semantics:
 * - A non-null snapshot means the counters were MEASURED at terminal
 *   persistence time (captured under the terminal mutex).
 * - `rowsScanned`, `rowsUpdated`, `notificationsSent`, `rowsSkipped`, `errors`
 *   are always measured; `0` means a measured zero.
 * - `partialFailureCount` / `failedTargetCount`: non-null means measured
 *   (`0` = measured zero); `null` means unknown / not supplied by the worker.
 * - A `null` snapshot (old callers / paths without a [WorkerRunContext])
 *   means no measurement was supplied: scalar defaults (0) are persisted for
 *   the non-null columns and `null` for the two optional counts.
 */
data class WorkerRunCounters(
    val rowsScanned: Int,
    val rowsUpdated: Int,
    val notificationsSent: Int,
    val rowsSkipped: Int,
    val errors: Int,
    val partialFailureCount: Int? = null,
    val failedTargetCount: Int? = null
)
