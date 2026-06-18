package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.workers.PrivacyRuntimeWorkerPolicy.PrivacyToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mapping-correctness tests for [PrivacyRuntimeWorkerPolicy].
 *
 * P9-P1-11 / PR8 regression guards:
 *  - `merchant_key_backfill` is local and must NEVER be gated by background
 *    location (the over-cancel bug).
 *  - `data_retention` must NEVER appear in any cancel set (cleanup keeps running).
 *  - Every referenced worker name must exist in [WorkerSpec.DEFAULTS].
 *
 * Uses Robolectric to mirror [WorkerContractTest], since [WorkerSpec.DEFAULTS]
 * builds `androidx.work.Constraints` instances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyRuntimeWorkerPolicyTest {

    @Test
    fun `cloud AI disable cancels ai_daily_briefing only`() {
        assertEquals(
            setOf("ai_daily_briefing"),
            PrivacyRuntimeWorkerPolicy.workersToCancel(setOf(PrivacyToggle.CLOUD_AI))
        )
    }

    @Test
    fun `background location disable cancels location_backfill only`() {
        val cancelled =
            PrivacyRuntimeWorkerPolicy.workersToCancel(setOf(PrivacyToggle.BACKGROUND_LOCATION_BACKFILL))
        assertEquals(setOf("location_backfill"), cancelled)
    }

    @Test
    fun `background location never gates merchant_key_backfill`() {
        // The core PR8 fix: merchant-key generation is LOCAL, not location-based.
        val cancelled =
            PrivacyRuntimeWorkerPolicy.workersToCancel(setOf(PrivacyToggle.BACKGROUND_LOCATION_BACKFILL))
        assertFalse(
            "merchant_key_backfill must not be cancelled when background location is disabled",
            cancelled.contains("merchant_key_backfill")
        )
        assertFalse(
            "merchant_key_backfill must not be referenced by the policy at all",
            PrivacyRuntimeWorkerPolicy.allReferencedWorkerNames.contains("merchant_key_backfill")
        )
    }

    @Test
    fun `notification capture disable cancels receipt warranty bill but not data_retention`() {
        val cancelled =
            PrivacyRuntimeWorkerPolicy.workersToCancel(setOf(PrivacyToggle.NOTIFICATION_CAPTURE))
        assertEquals(
            setOf("receipt_matching", "warranty_expiration_check", "bill_reminder_periodic"),
            cancelled
        )
        assertFalse(
            "data_retention must keep running to purge already-collected data",
            cancelled.contains("data_retention")
        )
    }

    @Test
    fun `data_retention never appears in any cancel set`() {
        val allToggles = PrivacyToggle.values().toSet()
        val cancelled = PrivacyRuntimeWorkerPolicy.workersToCancel(allToggles)
        assertFalse(
            "data_retention is cancel-exempt across every privacy toggle",
            cancelled.contains("data_retention")
        )
        assertTrue(
            "data_retention is declared cancel-exempt",
            PrivacyRuntimeWorkerPolicy.cancelExemptWorkers.contains("data_retention")
        )
    }

    @Test
    fun `reschedule mirrors cancel mapping for enabled toggles`() {
        assertEquals(
            setOf("ai_daily_briefing"),
            PrivacyRuntimeWorkerPolicy.workersToReschedule(setOf(PrivacyToggle.CLOUD_AI))
        )
        assertEquals(
            setOf("location_backfill"),
            PrivacyRuntimeWorkerPolicy.workersToReschedule(setOf(PrivacyToggle.BACKGROUND_LOCATION_BACKFILL))
        )
        assertEquals(
            setOf("receipt_matching", "warranty_expiration_check", "bill_reminder_periodic"),
            PrivacyRuntimeWorkerPolicy.workersToReschedule(setOf(PrivacyToggle.NOTIFICATION_CAPTURE))
        )
    }

    @Test
    fun `every referenced worker name exists in WorkerSpec DEFAULTS`() {
        val unknown = PrivacyRuntimeWorkerPolicy.allReferencedWorkerNames - WorkerSpec.DEFAULTS.keys
        assertTrue("Policy references unknown worker names: $unknown", unknown.isEmpty())
    }

    @Test
    fun `disabledToggles detects only true to false transitions`() {
        val old = PrivacySettings(
            cloudAiEnabled = true,
            backgroundLocationBackfillEnabled = true,
            notificationCaptureEnabled = true
        )
        val persisted = old.copy(backgroundLocationBackfillEnabled = false)
        assertEquals(
            setOf(PrivacyToggle.BACKGROUND_LOCATION_BACKFILL),
            PrivacyRuntimeWorkerPolicy.disabledToggles(old, persisted)
        )
        assertTrue(
            "No false->true transition occurred",
            PrivacyRuntimeWorkerPolicy.enabledToggles(old, persisted).isEmpty()
        )
    }

    @Test
    fun `enabledToggles detects only false to true transitions`() {
        val old = PrivacySettings(
            cloudAiEnabled = false,
            backgroundLocationBackfillEnabled = false,
            notificationCaptureEnabled = false
        )
        val persisted = old.copy(cloudAiEnabled = true)
        assertEquals(
            setOf(PrivacyToggle.CLOUD_AI),
            PrivacyRuntimeWorkerPolicy.enabledToggles(old, persisted)
        )
        assertTrue(
            "No true->false transition occurred",
            PrivacyRuntimeWorkerPolicy.disabledToggles(old, persisted).isEmpty()
        )
    }
}
