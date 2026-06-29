package com.yourname.expensetracker.architecture

import com.yourname.expensetracker.domain.workers.WorkerGuardVerifier
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI-enforcement test: fails the build if any *known* [androidx.work.CoroutineWorker]
 * subclass in the app is unguarded, or if stale registry entries are detected.
 *
 * **Important limitation:** [WorkerGuardVerifier.findWorkerClasses] uses a
 * hardcoded list. This test cannot automatically discover brand-new
 * [CoroutineWorker] subclasses that were never added to that list. When you
 * add a new [CoroutineWorker], you MUST:
 *
 * 1. Add its class to [WorkerGuardVerifier.findWorkerClasses].
 * 2. Add its simple name → work name mapping to [WorkerGuardVerifier.workerRegistry].
 * 3. Add its work name to [WorkerSpecScheduler.listAllWorkerNames].
 * 4. If it is a periodic worker, add a [com.yourname.expensetracker.domain.workers.WorkerSpec]
 *    entry to [com.yourname.expensetracker.domain.workers.WorkerSpec.DEFAULTS].
 * 5. Add its fully-qualified class name to [KNOWN_WORKER_FQNS] in this test and
 *    update the expected count in `listAllWorkerNames count matches expected worker total`.
 *
 * The [all_known_coroutine_worker_subclasses_are_in_guard_verifier] test provides
 * a partial safety net by loading every class in [KNOWN_WORKER_FQNS] via reflection
 * and asserting each is present in [WorkerGuardVerifier.findWorkerClasses]. That
 * test will fail if you add a new worker to the FQN list but forget to update
 * [findWorkerClasses], catching one class of registration oversight.
 *
 * This runs in CI (debug unit tests) and will block a PR that adds a new
 * [androidx.work.CoroutineWorker] without registering it in the guard system.
 */
class WorkerGuardStaticVerificationTest {

    /**
     * Every [CoroutineWorker] in the app, by fully-qualified class name.
     *
     * Used by [all_known_coroutine_worker_subclasses_are_in_guard_verifier] to
     * cross-check against [WorkerGuardVerifier.findWorkerClasses]. When you add a
     * new [CoroutineWorker], add its FQN here.
     */
    private val KNOWN_WORKER_FQNS: Set<String> = setOf(
        "com.yourname.expensetracker.worker.NotificationIntakeWorker",
        "com.yourname.expensetracker.data.location.LocationBackfillWorker",
        "com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker",
        "com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker",
        "com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker",
        "com.yourname.expensetracker.data.privacy.DataRetentionWorker",
        "com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker",
        "com.yourname.expensetracker.service.reminder.BillReminderWorker",
        "com.yourname.expensetracker.service.reminder.DismissReminderActionWorker",
        "com.yourname.expensetracker.service.reminder.SnoozeReminderActionWorker"
    )

    @Test
    fun `all known workers must be registered with guard`() {
        val verifier = WorkerGuardVerifier()
        val result = verifier.verifyAllWorkersGuarded()

        val details = when (result) {
            is WorkerGuardVerifier.VerificationResult.Failure -> buildString {
                if (result.unguardedWorkers.isNotEmpty()) {
                    append("Unguarded workers: ${result.unguardedWorkers}. ")
                }
                if (result.staleEntries.isNotEmpty()) {
                    append("Stale registry entries: ${result.staleEntries}. ")
                }
            }
            else -> ""
        }

        assertTrue(
            "$details" +
                "Every known CoroutineWorker must be registered in WorkerGuardVerifier.workerRegistry " +
                "and its work name must appear in WorkerSpecScheduler.listAllWorkerNames(). " +
                "Stale registry entries (workers in the map whose class no longer exists) " +
                "must be removed. See WorkerGuardVerifier.kt for the registration instructions.",
            result is WorkerGuardVerifier.VerificationResult.Success
        )
    }

    /**
     * Partial safety net: loads every class in [KNOWN_WORKER_FQNS] via reflection,
     * asserts each is a [CoroutineWorker] subclass, and asserts each is present in
     * [WorkerGuardVerifier.findWorkerClasses].
     *
     * This will catch the case where a developer adds a new worker FQN to the list
     * but forgets to update [findWorkerClasses]. It will NOT catch a brand-new
     * worker that was never added to [KNOWN_WORKER_FQNS] in the first place — that
     * requires manual discipline (see class-level KDoc).
     */
    @Test
    fun `all known coroutine worker subclasses are in guard verifier findWorkerClasses`() {
        val verifier = WorkerGuardVerifier()
        val verifierClassNames = verifier.findWorkerClasses().map { it.name }.toSet()

        val missing = mutableListOf<String>()

        for (fqn in KNOWN_WORKER_FQNS) {
            val clazz = try {
                Class.forName(fqn)
            } catch (e: ClassNotFoundException) {
                missing.add("$fqn (ClassNotFoundException — worker class may have been renamed or deleted)")
                continue
            }

            // Verify it is actually a CoroutineWorker subclass
            assertTrue(
                "$fqn must extend CoroutineWorker",
                androidx.work.CoroutineWorker::class.java.isAssignableFrom(clazz)
            )

            // Verify it is in the verifier's findWorkerClasses list
            if (fqn !in verifierClassNames) {
                missing.add("$fqn (present in KNOWN_WORKER_FQNS but NOT in WorkerGuardVerifier.findWorkerClasses())")
            }
        }

        assertTrue(
            "CoroutineWorker subclasses missing from WorkerGuardVerifier.findWorkerClasses(): $missing. " +
                "Add the missing classes to findWorkerClasses() in WorkerGuardVerifier.kt.",
            missing.isEmpty()
        )

        // Verify the counts match — KNOWN_WORKER_FQNS and findWorkerClasses must stay in sync
        assertEquals(
            "KNOWN_WORKER_FQNS size (${KNOWN_WORKER_FQNS.size}) must match " +
                "findWorkerClasses size (${verifierClassNames.size}). " +
                "If you added or removed a worker, update BOTH lists.",
            KNOWN_WORKER_FQNS.size, verifierClassNames.size
        )
    }

    /**
     * Validate that [WorkerSpecScheduler.listAllWorkerNames] and
     * [WorkerSpec.DEFAULTS] are consistent — every periodic spec key must
     * appear in the list, and no spec key is accidentally omitted.
     */
    @Test
    fun `listAllWorkerNames includes all WorkerSpec DEFAULTS keys`() {
        val knownNames = WorkerSpecScheduler.listAllWorkerNames()
        val specKeys = com.yourname.expensetracker.domain.workers.WorkerSpec.DEFAULTS.keys

        val missingFromList = specKeys.filter { it !in knownNames }
        assertTrue(
            "WorkerSpec.DEFAULTS keys missing from listAllWorkerNames: $missingFromList. " +
                "Add missing keys to WorkerSpecScheduler.listAllWorkerNames().",
            missingFromList.isEmpty()
        )
    }

    @Test
    fun `listAllWorkerNames count matches expected worker total`() {
        val knownNames = WorkerSpecScheduler.listAllWorkerNames()
        assertEquals(
            "Expected exactly 10 unique work names (7 from WorkerSpec.DEFAULTS + notification_intake + 2 action workers). " +
                "If you added a new worker, increment this count and register it in WorkerGuardVerifier.workerRegistry.",
            10, knownNames.size
        )
    }
}
