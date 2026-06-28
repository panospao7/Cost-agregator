package com.yourname.expensetracker.domain.workers

/**
 * Result returned by [WorkerSpecScheduler.scheduleFromSpec] and
 * [WorkerSpecScheduler.scheduleAtMidnight] so callers can inspect whether
 * scheduling succeeded and emit diagnostics on failure.
 *
 * @property workerName The unique work name that was scheduled (or attempted).
 * @property scheduled Whether the enqueue call reached WorkManager successfully.
 * @property policyUsed The [ExistingWorkPolicy] or [ExistingPeriodicWorkPolicy] name used.
 * @property versionChanged True if the spec version differed from the persisted version.
 * @property error A human-readable error message when [scheduled] is false.
 */
data class ScheduleResult(
    val workerName: String,
    val scheduled: Boolean,
    val policyUsed: String,
    val versionChanged: Boolean,
    val error: String? = null
)
