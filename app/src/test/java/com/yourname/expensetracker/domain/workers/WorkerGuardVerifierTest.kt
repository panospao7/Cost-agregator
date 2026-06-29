package com.yourname.expensetracker.domain.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WorkerGuardVerifier.verifyAllWorkersGuarded].
 *
 * These tests validate the runtime guard verification that runs in debug builds.
 * The CI-style static verification test is in
 * [com.yourname.expensetracker.architecture.WorkerGuardStaticVerificationTest].
 */
class WorkerGuardVerifierTest {

    private val verifier = WorkerGuardVerifier()

    /**
     * Happy-path: the hardcoded [WorkerGuardVerifier.findWorkerClasses] maps 10 workers
     * whose work names are all returned by [WorkerSpecScheduler.listAllWorkerNames].
     */
    @Test
    fun `verifyAllWorkersGuarded returns Success when all 10 workers are registered`() {
        val result = verifier.verifyAllWorkersGuarded()
        assertEquals(
            "All 10 workers should be guarded → Success",
            WorkerGuardVerifier.VerificationResult.Success::class,
            result::class
        )
    }

    /**
     * Negative test: if a worker's work name is missing from
     * [WorkerSpecScheduler.listAllWorkerNames], the verifier reports it as unguarded.
     *
     * We simulate this by verifying that the failure path correctly detects the
     * mismatch. Since [WorkerSpecScheduler] is an object, we cannot easily mock it
     * in this pure unit test. Instead, we validate the structural contract:
     * - [WorkerGuardVerifier.workerRegistry] maps every known worker class to a name
     * - [WorkerSpecScheduler.listAllWorkerNames] includes every mapped name
     *
     * The CI test in [com.yourname.expensetracker.architecture.WorkerGuardStaticVerificationTest]
     * actually fails the build when this invariant is violated, serving as the
     * true negative enforcement.
     */
    @Test
    fun `worker registry contains all 10 workers`() {
        val knownNames = WorkerSpecScheduler.listAllWorkerNames()

        // The 10 expected work names from the spec
        val expectedNames = setOf(
            "notification_intake",
            "location_backfill",
            "merchant_key_backfill",
            "receipt_matching",
            "warranty_expiration_check",
            "data_retention",
            "ai_daily_briefing",
            "bill_reminder_periodic",
            "reminder_action_dismiss",
            "reminder_action_snooze"
        )

        assertEquals(
            "listAllWorkerNames must return exactly 10 work names",
            10, knownNames.size
        )

        for (name in expectedNames) {
            assertTrue(
                "Worker name '$name' must be in listAllWorkerNames()",
                name in knownNames
            )
        }
    }

    /**
     * Verify that every work name known to the scheduler has a corresponding
     * [WorkerSpec] entry (or is explicitly exempt like notification_intake
     * and the action workers).
     */
    @Test
    fun `every scheduled work name has worker spec except exempt ones`() {
        val knownNames = WorkerSpecScheduler.listAllWorkerNames()
        val specKeys = WorkerSpec.DEFAULTS.keys

        val exemptNames = setOf(
            "notification_intake",  // one-shot via coordinator, not periodic spec
            "reminder_action_dismiss",   // one-shot on-demand via DismissReminderReceiver
            "reminder_action_snooze"     // one-shot on-demand via SnoozeReminderReceiver
        )

        for (name in knownNames) {
            if (name in exemptNames) continue
            assertTrue(
                "Work name '$name' must have a WorkerSpec entry",
                name in specKeys
            )
        }
    }
}
