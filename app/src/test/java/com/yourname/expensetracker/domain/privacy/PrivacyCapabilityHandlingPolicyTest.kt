package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract test ensuring every PrivacyCapability has an explicit handling policy.
 *
 * Prevents new capabilities from accidentally being fail-open (allowed by default)
 * when no gate handles them.
 */
class PrivacyCapabilityHandlingPolicyTest {

    /**
     * Every capability must be explicitly classified as either:
     * - GATE_HANDLED: a concrete gate checks this capability
     * - LOCAL_ONLY: no external call, no gate needed (retention, debug)
     */
    enum class CapabilityPolicy { GATE_HANDLED, LOCAL_ONLY }

    private val policyMap: Map<PrivacyCapability, CapabilityPolicy> = mapOf(
        // Gate-handled (external calls or sensitive operations)
        PrivacyCapability.NOTIFICATION_CAPTURE to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.AI_BANK_STATEMENT_PARSING to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.CLOUD_AI_GENERAL to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.EXTERNAL_GEOCODING to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.BACKGROUND_LOCATION_BACKFILL to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.DEVICE_GPS_LOCATION to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.RAWBACKUP_EXPORT to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.ENCRYPTED_BACKUP to CapabilityPolicy.GATE_HANDLED,
        PrivacyCapability.OVERPASS_API to CapabilityPolicy.GATE_HANDLED,

        // Local-only (no external call, managed by retention/debug settings)
        PrivacyCapability.RAW_NOTIFICATION_RETENTION to CapabilityPolicy.LOCAL_ONLY,
        PrivacyCapability.RAW_OCR_RETENTION to CapabilityPolicy.LOCAL_ONLY,
        PrivacyCapability.DEBUG_DATA_PERSISTENCE to CapabilityPolicy.LOCAL_ONLY,
        PrivacyCapability.TIMBER_PII_LOGGING to CapabilityPolicy.LOCAL_ONLY
    )

    @Test
    fun `every PrivacyCapability has an explicit handling policy`() {
        val allCapabilities = PrivacyCapability.entries
        val unmapped = allCapabilities.filter { it !in policyMap }

        assertTrue(
            "New PrivacyCapability values without explicit policy (fail-open risk): $unmapped. " +
            "Add them to policyMap in this test with GATE_HANDLED or LOCAL_ONLY.",
            unmapped.isEmpty()
        )
    }

    @Test
    fun `policy map covers all enum values`() {
        assertEquals(
            "Policy map size must match PrivacyCapability.entries size",
            PrivacyCapability.entries.size,
            policyMap.size
        )
    }

    @Test
    fun `gate-handled capabilities are majority`() {
        val gateHandled = policyMap.values.count { it == CapabilityPolicy.GATE_HANDLED }
        assertTrue(
            "Most capabilities should be gate-handled (found $gateHandled)",
            gateHandled >= 15
        )
    }
}
