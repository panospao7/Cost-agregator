package com.yourname.expensetracker.domain.workers

import android.content.Context
import androidx.work.*
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Centralized scheduler that all workers use instead of duplicating schedule logic.
 *
 * Reads [WorkerSpec.DEFAULTS] by worker name, handles version-change detection
 * (forcing UPDATE when the spec version bumps), and delegates to either
 * [WorkManager.enqueueUniquePeriodicWork] or [WorkManager.enqueueUniqueWork]
 * depending on whether [WorkerSpec.repeatIntervalHours] is set.
 *
 * This is a stateless Kotlin `object` – no dependency injection needed.
 */
object WorkerSpecScheduler {

    private const val PREFS_NAME = "worker_spec_versions"

    /**
     * Enqueue (or re-enqueue) a worker based on its spec from [WorkerSpec.DEFAULTS].
     *
     * @param context  Application or activity context.
     * @param workerName  Key into [WorkerSpec.DEFAULTS] (also used as the unique work name).
     * @param workerClass  The exact [ListenableWorker] subclass to schedule.
     * @param customConstraints  Optional constraints that override [WorkerSpec.constraints].
     */
    fun scheduleFromSpec(
        context: Context,
        workerName: String,
        workerClass: Class<out ListenableWorker>,
        customConstraints: Constraints? = null
    ) {
        val spec = WorkerSpec.DEFAULTS[workerName] ?: run {
            android.util.Log.w("WorkerSpecScheduler", "No WorkerSpec found for: $workerName")
            return
        }

        if (!spec.enabled) {
            Timber.d("Worker '$workerName' is disabled — cancelling any existing scheduled work")
            try {
                WorkManager.getInstance(context).cancelUniqueWork(workerName)
            } catch (e: Exception) {
                Timber.w(e, "Failed to cancel disabled worker '$workerName'")
            }
            return
        }

        val constraints = customConstraints ?: spec.constraints

        // Version check: force REPLACE if version changed since last enqueue
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(workerName, 0)
        val versionChanged = spec.version > lastVersion

        @Suppress("UNCHECKED_CAST")
        val typedClass = workerClass as Class<ListenableWorker>

        try {
            if (spec.repeatIntervalHours != null) {
                // ── Periodic worker ───────────────────────────────────────────
                val policy = if (versionChanged) {
                    android.util.Log.i(
                        "WorkerSpecScheduler",
                        "Worker '$workerName' version changed (${lastVersion} → ${spec.version}), forcing UPDATE"
                    )
                    // UPDATE atomically cancels the pending periodic work and enqueues
                    // the new one, matching the old REPLACE behaviour without the
                    // deprecated API constant (ExistingPeriodicWorkPolicy.REPLACE was
                    // deprecated in WorkManager 2.8+ in favour of UPDATE).
                    ExistingPeriodicWorkPolicy.UPDATE
                } else {
                    spec.existingWorkPolicy
                }

                val request = PeriodicWorkRequest
                    .Builder(
                        typedClass,
                        spec.repeatIntervalHours, TimeUnit.HOURS,
                        spec.flexMinutes ?: 15, TimeUnit.MINUTES
                    )
                    .setConstraints(constraints)
                    .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(workerName, policy, request)
            } else {
                // ── One-shot worker ───────────────────────────────────────────
                val policy = if (versionChanged) {
                    android.util.Log.i(
                        "WorkerSpecScheduler",
                        "Worker '$workerName' version changed (${lastVersion} → ${spec.version}), forcing UPDATE"
                    )
                    // A version bump always wins over the spec's oneShotPolicy.
                    // UPDATE atomically cancels the previous work and enqueues the
                    // new one — replacing the deprecated ExistingWorkPolicy.REPLACE
                    // (WorkManager 2.8+).
                    ExistingWorkPolicy.UPDATE
                } else {
                    spec.oneShotPolicy
                }

                val request = OneTimeWorkRequest
                    .Builder(typedClass)
                    .setConstraints(constraints)
                    .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(workerName, policy, request)
            }

            // Persist version only after a successful enqueue, so a crash between
            // the two does not leave stale state (the next run will see the version
            // bump and force UPDATE again, which is safe).
            prefs.edit().putInt(workerName, spec.version).apply()
        } catch (e: Exception) {
            android.util.Log.e("WorkerSpecScheduler", "Failed to enqueue worker '$workerName'", e)
        }
    }

    /**
     * Schedule a one-shot worker at the next midnight boundary (calendar-day-aligned).
     *
     * Reads [WorkerSpec.DEFAULTS] by [workerName], checks [WorkerSpec.enabled] and version
     * (same logic as [scheduleFromSpec]), computes the delay until the next midnight in the
     * system timezone, and enqueues a [OneTimeWorkRequest] with that delay.
     *
     * If the spec is disabled, any existing scheduled work is cancelled (parity with
     * [scheduleFromSpec]) and no new work is enqueued. When enabled, the enqueue uses
     * [WorkerSpec.oneShotPolicy], except a version bump forces [ExistingWorkPolicy.UPDATE].
     *
     * @param context  Application or activity context.
     * @param workerName  Key into [WorkerSpec.DEFAULTS] (also used as the unique work name).
     * @param workerClass  The exact [ListenableWorker] subclass to schedule.
     */
    fun scheduleAtMidnight(
        context: Context,
        workerName: String,
        workerClass: Class<out ListenableWorker>
    ) {
        val spec = WorkerSpec.DEFAULTS[workerName] ?: run {
            android.util.Log.w("WorkerSpecScheduler", "No WorkerSpec found for: $workerName")
            return
        }
        if (!spec.enabled) {
            android.util.Log.d(
                "WorkerSpecScheduler",
                "Worker '$workerName' is disabled — cancelling any existing scheduled work"
            )
            try {
                WorkManager.getInstance(context).cancelUniqueWork(workerName)
            } catch (e: Exception) {
                android.util.Log.w("WorkerSpecScheduler", "Failed to cancel disabled worker '$workerName'", e)
            }
            return
        }

        // Version check: force UPDATE if version changed since last enqueue
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(workerName, 0)
        val effectivePolicy = if (spec.version > lastVersion) {
            android.util.Log.i(
                "WorkerSpecScheduler",
                "Worker '$workerName' version changed (${lastVersion} → ${spec.version}), forcing UPDATE"
            )
            // A version bump always wins over the spec's oneShotPolicy.
            // UPDATE cancels the pending work and enqueues the new one, replacing
            // the deprecated ExistingWorkPolicy.REPLACE (WorkManager 2.8+).
            ExistingWorkPolicy.UPDATE
        } else {
            spec.oneShotPolicy
        }

        // Compute next midnight in system timezone.
        // NOTE: System.currentTimeMillis() is intentionally used here because
        // WorkManager scheduling inherently operates on real wall-clock time.
        // Injecting TimeProvider would not make scheduling more testable since
        // the actual enqueue depends on the real system clock.
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        // Guard against near-zero delay when scheduled just before midnight
        // (e.g. 23:59:59.999). A sub-minute initial delay could cause tight
        // re-scheduling loops. Floor at 60 seconds.
        val rawDelayMs = cal.timeInMillis - now
        val delayMs = maxOf(rawDelayMs, 60_000L)

        @Suppress("UNCHECKED_CAST")
        val typedClass = workerClass as Class<ListenableWorker>

        val request = OneTimeWorkRequest.Builder(typedClass)
            .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(spec.constraints)
            .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(workerName, effectivePolicy, request)

            // Persist version only after a successful enqueue so a crash between
            // enqueue and prefs write does not leave stale version state.
            prefs.edit().putInt(workerName, spec.version).apply()
            android.util.Log.d("WorkerSpecScheduler", "Worker '$workerName' scheduled at midnight in ${delayMs}ms")
        } catch (e: Exception) {
            android.util.Log.e("WorkerSpecScheduler", "Failed to enqueue midnight worker '$workerName'", e)
        }
    }
}
