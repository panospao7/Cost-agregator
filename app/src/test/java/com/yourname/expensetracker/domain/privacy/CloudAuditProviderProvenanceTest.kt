package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR7 acceptance tests:
 *
 * cloud_call_audit_has_provider_model_purpose
 * cloud_call_audit_has_payload_hash
 * cloud_call_audit_records_redactionApplied
 * audit_context_rejects_raw_prompt
 * privacy_gate_unrelated_returns_not_applicable
 * missing_sensitive_handler_fail_closed
 */
class CloudAuditProviderProvenanceTest {

    // ── PrivacyAuditContext ───────────────────────────────────────────────────

    @Test
    fun cloud_call_audit_has_provider_model_purpose() {
        val payload = PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = "redacted text",
            redactionApplied = true,
            fieldsRedacted = emptySet(),
            payloadHash = "abc123",
            rawTextIncluded = false,
            rawImageIncluded = false
        )
        val ctx = PrivacyAuditContext.forCloudCall(
            provider = "gemini",
            modelId = "gemini-1.5-flash",
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            payload = payload,
            correlationId = "corr-001"
        )
        assertEquals("gemini", ctx.provider)
        assertEquals("gemini-1.5-flash", ctx.modelId)
        assertEquals(CloudPayloadPurpose.RECEIPT_ASSIST, ctx.purpose)
        assertEquals("corr-001", ctx.correlationId)
    }

    @Test
    fun cloud_call_audit_has_payload_hash() {
        val payload = PreparedCloudPayload(
            purpose = CloudPayloadPurpose.DASHBOARD_BRIEFING,
            text = "briefing text",
            redactionApplied = true,
            fieldsRedacted = setOf("email"),
            payloadHash = "hash-xyz-123",
            rawTextIncluded = false,
            rawImageIncluded = false
        )
        val ctx = PrivacyAuditContext.forCloudCall(
            provider = "gemini",
            modelId = "model",
            purpose = CloudPayloadPurpose.DASHBOARD_BRIEFING,
            payload = payload
        )
        assertEquals("hash-xyz-123", ctx.payloadHash)
        assertNotNull(ctx.payloadHash)
    }

    @Test
    fun cloud_call_audit_records_redactionApplied() {
        val payload = PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = "text",
            redactionApplied = true,
            fieldsRedacted = emptySet(),
            payloadHash = "h",
            rawTextIncluded = false,
            rawImageIncluded = false
        )
        val ctx = PrivacyAuditContext.forCloudCall("p", "m", CloudPayloadPurpose.RECEIPT_ASSIST, payload)
        assertTrue(ctx.redactionApplied == true)
        assertFalse(ctx.rawTextIncluded == true)
        assertFalse(ctx.rawImageIncluded == true)
    }

    @Test
    fun audit_context_toMap_does_not_include_raw_prompt() {
        val ctx = PrivacyAuditContext(
            operation = "receipt_assist",
            provider = "gemini",
            payloadHash = "hash"
        )
        val map = ctx.toMap()
        // Map must not contain anything called "prompt", "rawText", "text", etc.
        assertFalse(map.keys.any { it.contains("prompt", ignoreCase = true) })
        assertFalse(map.keys.any { it.contains("rawText", ignoreCase = true) })
        assertFalse(map.keys.any { it == "text" })
    }

    @Test
    fun audit_context_carries_purpose_name_in_map() {
        val ctx = PrivacyAuditContext(
            operation = "cloud_call",
            purpose = CloudPayloadPurpose.BANK_STATEMENT_VALIDATION
        )
        val map = ctx.toMap()
        assertEquals("BANK_STATEMENT_VALIDATION", map["purpose"])
    }

    // ── PrivacyGate NotApplicable ─────────────────────────────────────────────

    @Test
    fun privacy_gate_unrelated_capability_returns_not_applicable_or_allowed() = runTest {
        val settingsRepo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { settingsRepo.getSettings() } returns PrivacySettings()
        val auditLogger = PrivacyAuditLogger.NO_OP

        // A gate that handles only NOTIFICATION_CAPTURE should return NotApplicable for EXTERNAL_GEOCODING
        val notificationGate = NotificationPrivacyGate(settingsRepo, auditLogger)
        val decision = notificationGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        assertTrue(
            "Unrelated capability must return NotApplicable or Allowed",
            decision == PrivacyDecision.NotApplicable || decision == PrivacyDecision.Allowed
        )
    }

    @Test
    fun missing_sensitive_handler_in_composite_fails_closed() = runTest {
        // Composite with gateHandledCapabilities containing a capability that no gate handles
        val auditLogger = PrivacyAuditLogger.NO_OP
        val composite = CompositePrivacyGate(
            gates = emptyList(),
            auditLogger = auditLogger,
            gateHandledCapabilities = setOf(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        )
        val decision = composite.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        assertTrue(
            "Missing handler for gateHandledCapabilities must fail closed",
            decision.blocksExecution()
        )
    }

    // ── SafePrivacyMetadata blocked keys ─────────────────────────────────────

    @Test
    fun safe_privacy_metadata_rejects_prompt_key() {
        val meta = SafePrivacyMetadata.builder()
            .put("prompt", "AI prompt content")
            .put("purpose", "receipt_assist")
            .build()
        val json = meta.toJson()
        assertFalse("prompt key must be blocked", json.contains("AI prompt content"))
        assertTrue("safe key must be allowed", json.contains("receipt_assist"))
    }

    @Test
    fun safe_privacy_metadata_rejects_token_key() {
        val meta = SafePrivacyMetadata.builder()
            .put("token", "bearer-xyz-secret")
            .build()
        assertFalse(meta.toJson().contains("bearer-xyz-secret"))
    }

    // ── Prepared payload from policy has full provenance ──────────────────────

    @Test
    fun prepared_payload_for_cloud_call_has_purpose_and_hash() = runTest {
        val privacySettings = PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = true)
        val privacyRepo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacyRepo.getSettings() } returns privacySettings
        every { privacyRepo.observeSettings() } returns flowOf(privacySettings)
        every { privacyRepo.observeLoadState() } returns flowOf(PrivacySettingsLoadState.Loaded(privacySettings))

        val aiSettings = AiSettings(allowCloudAi = true, redactBeforeCloud = false)
        val aiRepo = mockk<AiSettingsRepository>(relaxed = true)
        every { aiRepo.settings() } returns flowOf(aiSettings)

        val policy = DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo),
            DefaultCloudPayloadRedactor()
        )

        val payload = policy.prepareText(CloudPayloadPurpose.WARRANTY_EXTRACTION, "warranty text")

        // Build PrivacyAuditContext from the payload
        val ctx = PrivacyAuditContext.forCloudCall("gemini", "model", CloudPayloadPurpose.WARRANTY_EXTRACTION, payload)
        assertNotNull(ctx.payloadHash)
        assertEquals(CloudPayloadPurpose.WARRANTY_EXTRACTION, ctx.purpose)
        assertTrue(ctx.redactionApplied == true)
    }
}
