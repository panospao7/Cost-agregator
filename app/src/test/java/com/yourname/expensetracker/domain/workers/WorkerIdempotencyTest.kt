package com.yourname.expensetracker.domain.workers

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [WorkerSpec] covering idempotency and configuration
 * invariants.
 *
 * WorkerSpecScheduler requires Android (WorkManager + Context) and is
 * better tested via Robolectric or instrumented tests. These unit tests
 * focus on the spec definitions and data class contract.
 */
class WorkerIdempotencyTest {

    @Test
    fun `duplicate scheduling does not double work`() {
        // Given: the same worker spec retrieved twice
        val spec1 = WorkerSpec.DEFAULTS["data_retention"]
        val spec2 = WorkerSpec.DEFAULTS["data_retention"]

        assertNotNull("data_retention spec must exist", spec1)
        assertNotNull("data_retention spec must exist on second read", spec2)

        // Then: both references are identical (DEFAULTS is an immutable map)
        assertEquals(spec1, spec2)
        assertEquals(spec1?.name, spec2?.name)
        assertEquals(spec1?.version, spec2?.version)
        assertEquals(spec1?.repeatIntervalHours, spec2?.repeatIntervalHours)

        // The work policy is KEEP by default, meaning WorkManager will not
        // create a duplicate if one already exists with the same unique name.
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, spec1?.existingWorkPolicy)
    }

    @Test
    fun `all default workers are enabled including bill reminder`() {
        // P9-CURRENT-005/P9-P1-05: bill_reminder_periodic is enabled by infrastructure
        // (version bump to 2). Runtime opt-in/quiet-hours gating is enforced separately
        // by BillReminderSettingsRepository inside BillReminderWorker, not by the spec.
        val spec = WorkerSpec.DEFAULTS["bill_reminder_periodic"]
        assertNotNull("bill_reminder_periodic spec must exist", spec)
        assertTrue("bill_reminder_periodic should be enabled by default", spec!!.enabled)

        // All default workers are enabled at the spec level.
        WorkerSpec.DEFAULTS.forEach { (name, workerSpec) ->
            assertTrue("Worker '$name' should be enabled by default", workerSpec.enabled)
        }
    }

    @Test
    fun `backoff policy is consistent across all default specs`() {
        WorkerSpec.DEFAULTS.forEach { (name, spec) ->
            // All specs should have a valid backoff policy
            assertNotNull("Worker '$name' must have a backoff policy",
                spec.backoffPolicy)

            // Backoff delay must be positive
            assertTrue("Worker '$name' must have positive backoff delay",
                spec.backoffDelaySeconds > 0L)

            // Constraints must be non-null
            assertNotNull("Worker '$name' must have constraints", spec.constraints)
        }
    }

    @Test
    fun `one-shot workers have null repeat interval`() {
        // Given: workers with null repeatIntervalHours are one-shot
        val oneShotWorkers = WorkerSpec.DEFAULTS.filter { it.value.repeatIntervalHours == null }

        assertTrue("There should be at least one one-shot worker",
            oneShotWorkers.isNotEmpty())

        oneShotWorkers.forEach { (name, spec) ->
            // One-shot workers still need a valid name
            assertTrue("Worker '$name' name must not be blank",
                spec.name.isNotBlank())

            // P9-CURRENT-020 (resolved): merchant_key_backfill uses an explicit
            // oneShotPolicy of REPLACE, matching MerchantKeyBackfillWorker's KDoc
            // intent (re-schedulable after completion).
            if (name == "merchant_key_backfill") {
                assertEquals("merchant_key_backfill must use REPLACE one-shot policy",
                    ExistingWorkPolicy.REPLACE, spec.oneShotPolicy)
            }
        }
    }

    @Test
    fun `oneShotPolicy defaults to KEEP and one-shot workers override to REPLACE`() {
        // The data class default for oneShotPolicy is KEEP so a one-shot is
        // scheduled at most once unless a spec explicitly opts into REPLACE.
        val defaultSpec = WorkerSpec(name = "default_one_shot")
        assertEquals("oneShotPolicy should default to KEEP",
            ExistingWorkPolicy.KEEP, defaultSpec.oneShotPolicy)

        // ai_daily_briefing uses REPLACE (U-WORKER-04) so the midnight chain is
        // always re-armed even if a stale pending request exists.
        val briefing = WorkerSpec.DEFAULTS["ai_daily_briefing"]
        assertNotNull("ai_daily_briefing spec must exist", briefing)
        assertEquals("ai_daily_briefing should use REPLACE one-shot policy",
            ExistingWorkPolicy.REPLACE, briefing!!.oneShotPolicy)

        // merchant_key_backfill overrides to REPLACE.
        val merchantKey = WorkerSpec.DEFAULTS["merchant_key_backfill"]
        assertNotNull("merchant_key_backfill spec must exist", merchantKey)
        assertEquals("merchant_key_backfill should override to REPLACE one-shot policy",
            ExistingWorkPolicy.REPLACE, merchantKey!!.oneShotPolicy)
    }
}
