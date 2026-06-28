package com.yourname.expensetracker.domain.workers

import android.content.Context
import androidx.work.*
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Centralized scheduler that all workers use instead of duplicating schedule logic.
 *
 * Reads [WorkerSpec.DEFAULTS] by worker name, handles version-change detection
 * (forcing UPDATE when the spec version differs from the stored version), and
 * delegates to either [WorkManager.enqueueUniquePeriodicWork] or
 * [WorkManager.enqueueUniqueWork] depending on whether
 * [WorkerSpec.repeatIntervalHours] is set.
 *
 * This is a stateless Kotlin `object` – no dependency injection needed.
 */
object WorkerSpecScheduler {

    private const val PREFS_NAME = "worker_spec_versions"

    /**
     * Fire-and-forget scope for diagnostic event emission. Diagnostics are best-effort
     * and must not block scheduling — failed emits are silently discarded.
     */
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Enqueue (or re-enqueue) a worker based on its spec from [WorkerSpec.DEFAULTS].
     *
     * @param context  Application or activity context.
     * @param workerName  Key into [WorkerSpec.DEFAULTS] (also used as the unique work name).
     * @param workerClass  The exact [ListenableWorker] subclass to schedule.
     * @param customConstraints  Optional constraints that override [WorkerSpec.constraints].
     * @param diagnosticEventWriter  Optional writer for emitting diagnostic events on failure.
     * @return [ScheduleResult] describing the scheduling outcome.
     */
    fun scheduleFromSpec(
        context: Context,
        workerName: String,
        workerClass: Class<out ListenableWorker>,
        customConstraints: Constraints? = null,
        diagnosticEventWriter: DiagnosticEventWriter? = null
    ): ScheduleResult {
        val spec = WorkerSpec.DEFAULTS[workerName] ?: run {
            android.util.Log.w("WorkerSpecScheduler", "No WorkerSpec found for: $workerName")
            return ScheduleResult(workerName = workerName, scheduled = false, policyUsed = "", versionChanged = false, error = "No WorkerSpec found for: $workerName")
        }

        if (!spec.enabled) {
            Timber.d("Worker '$workerName' is disabled — cancelling any existing scheduled work")
            try {
                WorkManager.getInstance(context).cancelUniqueWork(workerName)
            } catch (e: Exception) {
                Timber.w(e, "Failed to cancel disabled worker '$workerName'")
            }
            return ScheduleResult(workerName = workerName, scheduled = false, policyUsed = "", versionChanged = false, error = "Worker '$workerName' is disabled")
        }

        val constraints = customConstraints ?: spec.constraints

        // Version check: force REPLACE if version changed since last enqueue.
        // PR7: Use != instead of > so a corrupted (higher-than-spec) stored version
        // still triggers a forced re-enqueue.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(workerName, 0)
        val versionChanged = spec.version != lastVersion

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

                // Persist version only after a successful enqueue, so a crash between
                // the two does not leave stale state (the next run will see the version
                // bump and force UPDATE again, which is safe).
                prefs.edit().putInt(workerName, spec.version).apply()

                return ScheduleResult(
                    workerName = workerName,
                    scheduled = true,
                    policyUsed = policy.name,
                    versionChanged = versionChanged
                )
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
                    // PR1-FIX: was KEEP — KEEP silently ignores the new request if
                    // any work with the same unique name is already pending, meaning
                    // a version bump (e.g. new constraints) would never take effect
                    // until the existing request had run.
                    // REPLACE cancels existing pending work and enqueues the new one.
                    ExistingWorkPolicy.REPLACE
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

                // Persist version only after a successful enqueue so a crash between
                // the two does not leave stale version state.
                prefs.edit().putInt(workerName, spec.version).apply()

                return ScheduleResult(
                    workerName = workerName,
                    scheduled = true,
                    policyUsed = policy.name,
                    versionChanged = versionChanged
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorMsg = e.message ?: "Unknown error"
            android.util.Log.e("WorkerSpecScheduler", "Failed to enqueue worker '$workerName'", e)

            emitScheduleDiagnostic(
                diagnosticEventWriter = diagnosticEventWriter,
                workerName = workerName,
                phase = if (spec.repeatIntervalHours != null) "periodic" else "oneShot",
                policyUsed = "",
                versionChanged = versionChanged,
                error = errorMsg
            )

            return ScheduleResult(
                workerName = workerName,
                scheduled = false,
                policyUsed = "",
                versionChanged = versionChanged,
                error = errorMsg
            )
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
     * @param diagnosticEventWriter  Optional writer for emitting diagnostic events on failure.
     * @return [ScheduleResult] describing the scheduling outcome.
     */
    fun scheduleAtMidnight(
        context: Context,
        workerName: String,
        workerClass: Class<out ListenableWorker>,
        diagnosticEventWriter: DiagnosticEventWriter? = null
    ): ScheduleResult {
        val spec = WorkerSpec.DEFAULTS[workerName] ?: run {
            android.util.Log.w("WorkerSpecScheduler", "No WorkerSpec found for: $workerName")
            return ScheduleResult(workerName = workerName, scheduled = false, policyUsed = "", versionChanged = false, error = "No WorkerSpec found for: $workerName")
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
            return ScheduleResult(workerName = workerName, scheduled = false, policyUsed = "", versionChanged = false, error = "Worker '$workerName' is disabled")
        }

        // Version check: force UPDATE if version changed since last enqueue.
        // PR7: Use != instead of > so a corrupted (higher-than-spec) stored version
        // still triggers a forced re-enqueue.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(workerName, 0)
        val versionChanged = spec.version != lastVersion

        val effectivePolicy = if (versionChanged) {
            android.util.Log.i(
                "WorkerSpecScheduler",
                "Worker '$workerName' version changed (${lastVersion} → ${spec.version}), forcing UPDATE"
            )
            // A version bump always wins over the spec's oneShotPolicy.
            // PR1-FIX: was KEEP — KEEP silently ignores the new request if
            // any work with the same unique name is already pending, meaning
            // a version bump (e.g. new constraints) would never take effect
            // until the existing request had run.
            // REPLACE cancels existing pending work and enqueues the new one.
            ExistingWorkPolicy.REPLACE
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

            return ScheduleResult(
                workerName = workerName,
                scheduled = true,
                policyUsed = effectivePolicy.name,
                versionChanged = versionChanged
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorMsg = e.message ?: "Unknown error"
            android.util.Log.e("WorkerSpecScheduler", "Failed to enqueue midnight worker '$workerName'", e)

            emitScheduleDiagnostic(
                diagnosticEventWriter = diagnosticEventWriter,
                workerName = workerName,
                phase = "midnight_oneShot",
                policyUsed = effectivePolicy.name,
                versionChanged = versionChanged,
                error = errorMsg
            )

            return ScheduleResult(
                workerName = workerName,
                scheduled = false,
                policyUsed = effectivePolicy.name,
                versionChanged = versionChanged,
                error = errorMsg
            )
        }
    }

    /**
     * Returns all known worker unique work names.
     *
     * Used by [WorkerGuardVerifier.verifyAllWorkersGuarded] to cross-check that every
     * registered worker class maps to a known spec entry. When a new worker is added
     * to [WorkerSpec.DEFAULTS], its key must be included here.
     *
     * Note: "notification_intake" is listed here even though it is not a periodic
     * spec in [WorkerSpec.DEFAULTS] — it uses one-shot scheduling via
     * [com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCoordinator].
     */
    fun listAllWorkerNames(): List<String> = listOf(
        "notification_intake",
        "location_backfill",
        "merchant_key_backfill",
        "receipt_matching",
        "warranty_expiration_check",
        "data_retention",
        "ai_daily_briefing",
        "bill_reminder_periodic"
    )

    /**
     * Fire-and-forget diagnostic emission on schedule failure.
     * Launched on a background scope; failures in the emit itself are silently discarded.
     */
    private fun emitScheduleDiagnostic(
        diagnosticEventWriter: DiagnosticEventWriter?,
        workerName: String,
        phase: String,
        policyUsed: String,
        versionChanged: Boolean,
        error: String
    ) {
        val writer = diagnosticEventWriter ?: return
        diagnosticScope.launch {
            try {
                writer.emit(
                    DiagnosticEvent(
                        pipeline = AppPipeline.WORKER,
                        stage = "schedule",
                        outcome = EventOutcome.FAILED_FINAL,
                        entityType = "WorkerSchedule",
                        entityId = null,
                        metadata = SafeEventMetadata.builder()
                            .put("workerName", workerName)
                            .put("phase", phase)
                            .put("policyUsed", policyUsed)
                            .put("versionChanged", versionChanged)
                            .put("error", error)
                            .build()
                    )
                )
            } catch (_: Exception) {
                // Diagnostics are best-effort; suppress emit failures.
            }
        }
    }
}
