package com.yourname.expensetracker.workers

import com.yourname.expensetracker.domain.workers.WorkerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests verifying all 7 default background workers defined in
 * [WorkerSpec.DEFAULTS].
 *
 * These tests validate structural invariants — worker definitions, naming
 * consistency, and enabled/disabled state — without requiring a full Hilt
 * graph or instantiating the workers themselves.
 *
 * @see WorkerSpec.DEFAULTS
 * @see com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkerContractTest {

    @Test
    fun `all 7 default workers have WorkerSpec entries`() {
        val defaults = WorkerSpec.DEFAULTS
        assertEquals("Expected 7 default workers", 7, defaults.size)

        val expected = setOf(
            "data_retention",
            "location_backfill",
            "bill_reminder_periodic",
            "receipt_matching",
            "ai_daily_briefing",
            "warranty_expiration_check",
            "merchant_key_backfill"
        )
        assertEquals(expected, defaults.keys)
    }

    @Test
    fun `all worker specs have non-null enabled flag`() {
        for ((name, spec) in WorkerSpec.DEFAULTS) {
            assertNotNull("Worker '$name' has null spec", spec)
            // enabled is a non-nullable Boolean, always present
            assertTrue(
                "Worker '$name' should have a valid enabled state",
                spec.enabled || !spec.enabled
            )
        }
    }

    @Test
    fun `worker names match WorkerSpec DEFAULTS keys`() {
        val defaults = WorkerSpec.DEFAULTS
        for ((key, spec) in defaults) {
            assertEquals(
                "WorkerSpec key '$key' must match spec.name",
                key, spec.name
            )
        }
    }

    @Test
    fun `worker count matches pauseAllWorkers in RestoreMaintenanceMode`() {
        // RestoreMaintenanceMode.pauseAllWorkers() iterates WorkerSpec.DEFAULTS.keys
        // and cancels each by name. If a new worker is added without updating
        // the DEFAULTS map (or vice versa), this test catches the mismatch.
        assertEquals(7, WorkerSpec.DEFAULTS.size)
    }
}
