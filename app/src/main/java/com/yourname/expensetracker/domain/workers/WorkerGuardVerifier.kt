package com.yourname.expensetracker.domain.workers

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime verifier that checks all *known* [androidx.work.CoroutineWorker] subclasses
 * have a registered unique work name and are referenced in the guard system.
 *
 * **Limitation:** [findWorkerClasses] uses a hardcoded list. If a developer adds a
 * new [CoroutineWorker] but forgets to register it in both [findWorkerClasses] and
 * [workerRegistry], this verifier will report [VerificationResult.Success] because
 * it cannot discover unknown workers at runtime on Android.
 *
 * The CI analogue is [com.yourname.expensetracker.architecture.WorkerGuardStaticVerificationTest]
 * which fails the build if any *known* worker is unregistered. When adding a new
 * [CoroutineWorker] you MUST also add its class to [findWorkerClasses], its simple
 * name + work name to [workerRegistry], and its work name to
 * [WorkerSpecScheduler.listAllWorkerNames].
 *
 * Called at startup in debug builds.
 */
@Singleton
class WorkerGuardVerifier @Inject constructor() {

    /**
     * Mapping from CoroutineWorker simple class name to unique work name.
     *
     * Every [androidx.work.CoroutineWorker] subclass in the app must have an entry
     * here. When a new worker is added, register it in this map AND ensure its
     * unique work name is returned by [WorkerSpecScheduler.listAllWorkerNames].
     */
    private val workerRegistry: Map<String, String> = mapOf(
        "NotificationIntakeWorker" to "notification_intake",
        "LocationBackfillWorker" to "location_backfill",
        "MerchantKeyBackfillWorker" to "merchant_key_backfill",
        "ReceiptMatchingWorker" to "receipt_matching",
        "WarrantyExpirationWorker" to "warranty_expiration_check",
        "DataRetentionWorker" to "data_retention",
        "DailyBriefingWorker" to "ai_daily_briefing",
        "BillReminderWorker" to "bill_reminder_periodic",
        "DismissReminderActionWorker" to "reminder_action_dismiss",
        "SnoozeReminderActionWorker" to "reminder_action_snooze"
    )

    /**
     * Verifies that all CoroutineWorker subclasses are known to the guard system.
     *
     * Returns [VerificationResult.Success] when every worker class has a registered
     * work name AND every registered work name appears in [WorkerSpecScheduler.listAllWorkerNames].
     *
     * Returns [VerificationResult.Failure] listing the simple names of workers that
     * are either unregistered or whose work name is missing from the scheduler.
     */
    fun verifyAllWorkersGuarded(): VerificationResult {
        val knownWorkNames = WorkerSpecScheduler.listAllWorkerNames()
        val workerClasses = findWorkerClasses()
        val unguarded = mutableListOf<String>()

        for (clazz in workerClasses) {
            val simpleName = clazz.simpleName ?: continue

            // Check if this worker class is registered in the verifier map
            val registeredWorkName = workerRegistry[simpleName]
            if (registeredWorkName == null) {
                unguarded.add(simpleName)
                continue
            }

            // Check if the registered work name is known to the scheduler
            if (registeredWorkName !in knownWorkNames) {
                unguarded.add("$simpleName (work name '$registeredWorkName' not in scheduler)")
            }
        }

        // Collect stale registry entries: entries in workerRegistry whose class
        // no longer exists among the discovered workers. These must fail the build
        // so dead entries don't accumulate silently.
        val discoveredNames = workerClasses.mapNotNull { it.simpleName }.toSet()
        val stale = workerRegistry
            .filterKeys { it !in discoveredNames }
            .map { (className, workName) ->
                Timber.w("WorkerGuardVerifier: stale registry entry '$className' → '$workName' (class not found)")
                "$className → $workName"
            }

        return when {
            unguarded.isEmpty() && stale.isEmpty() -> VerificationResult.Success
            else -> VerificationResult.Failure(unguardedWorkers = unguarded, staleEntries = stale)
        }
    }

    /**
     * Returns the hardcoded list of known [androidx.work.CoroutineWorker] subclasses.
     *
     * This is more reliable than classpath scanning on Android. When a new worker is
     * added, its class must be included here AND registered in [workerRegistry].
     *
     * Visibility is internal (not private) so that
     * [com.yourname.expensetracker.architecture.WorkerGuardStaticVerificationTest]
     * can cross-check this list against its own [KNOWN_WORKER_FQNS].
     */
    internal fun findWorkerClasses(): List<Class<*>> {
        return listOf(
            com.yourname.expensetracker.worker.NotificationIntakeWorker::class.java,
            com.yourname.expensetracker.data.location.LocationBackfillWorker::class.java,
            com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker::class.java,
            com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker::class.java,
            com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker::class.java,
            com.yourname.expensetracker.data.privacy.DataRetentionWorker::class.java,
            com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker::class.java,
            com.yourname.expensetracker.service.reminder.BillReminderWorker::class.java,
            com.yourname.expensetracker.service.reminder.DismissReminderActionWorker::class.java,
            com.yourname.expensetracker.service.reminder.SnoozeReminderActionWorker::class.java
        )
    }

    sealed class VerificationResult {
        object Success : VerificationResult()
        data class Failure(
            val unguardedWorkers: List<String>,
            val staleEntries: List<String> = emptyList()
        ) : VerificationResult()
    }
}
