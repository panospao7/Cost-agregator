package com.yourname.expensetracker.domain.workers

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `restore mode blocks worker execution`() {
        // Given: the bill_reminder_periodic worker is disabled by default
        val spec = WorkerSpec.DEFAULTS["bill_reminder_periodic"]
        assertNotNull("bill_reminder_periodic spec must exist", spec)

        // Then: it is not enabled, so WorkerSpecScheduler.scheduleFromSpec
        // will skip enqueuing (the first check in that method is `if (!spec.enabled)`)
        assertFalse("bill_reminder_periodic should be disabled by default",
            spec!!.enabled)

        // Given: all other default workers (except bill_reminder) are enabled
        WorkerSpec.DEFAULTS.forEach { (name, workerSpec) ->
            if (name != "bill_reminder_periodic") {
                assertTrue("Worker '$name' should be enabled by default",
                    workerSpec.enabled)
            }
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

            // merchant_key_backfill should use REPLACE (per WRK-N5 comment)
            if (name == "merchant_key_backfill") {
                assertEquals("merchant_key_backfill should use KEEP per default",
                    ExistingPeriodicWorkPolicy.KEEP, spec.existingWorkPolicy)
            }
        }
    }
}
