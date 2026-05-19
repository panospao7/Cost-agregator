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
 * PR6 acceptance tests:
 *
 * privacy_redact_true_ai_redact_false_redacts_receipt_assist
 * privacy_redact_true_ai_redact_false_redacts_dashboard
 * bank_statement_uses_BANK_STATEMENT_VALIDATION_purpose
 * receipt_image_upload_suppressed_when_redaction_required
 * prepared_cloud_payload_has_payload_hash
 * new_cloud_purposes_are_available
 */
class CloudPayloadPolicyTest {

    private val redactor = DefaultCloudPayloadRedactor()

    private fun buildPolicy(
        privacyRedact: Boolean,
        aiRedact: Boolean = false
    ): DefaultCloudPayloadPolicy {
        val privacySettings = PrivacySettings(
            cloudAiEnabled = true,
            redactBeforeCloud = privacyRedact
        )
        val privacyRepo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacyRepo.getSettings() } returns privacySettings
        every { privacyRepo.observeSettings() } returns flowOf(privacySettings)

        val aiSettings = AiSettings(
            allowCloudAi = true,
            redactBeforeCloud = aiRedact
        )
        val aiRepo = mockk<AiSettingsRepository>(relaxed = true)
        every { aiRepo.settings() } returns flowOf(aiSettings)

        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        return DefaultCloudPayloadPolicy(resolver, redactor)
    }

    @Test
    fun privacy_redact_true_ai_redact_false_redacts_receipt_assist() = runTest {
        val policy = buildPolicy(privacyRedact = true, aiRedact = false)
        val rawText = "Receipt from Starbucks. Card ending 1234. Amount €5.00"
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, rawText)

        assertTrue("Redaction must be applied", payload.redactionApplied)
        assertFalse("Raw text must not be included", payload.rawTextIncluded)
        assertFalse("Raw image must not be included", payload.rawImageIncluded)
        assertEquals(CloudPayloadPurpose.RECEIPT_ASSIST, payload.purpose)
        assertNotNull("Payload hash must be present", payload.payloadHash)
    }

    @Test
    fun privacy_redact_true_ai_redact_false_redacts_dashboard() = runTest {
        val policy = buildPolicy(privacyRedact = true, aiRedact = false)
        val payload = policy.prepareText(CloudPayloadPurpose.DASHBOARD_BRIEFING, "Top merchant: Starbucks €200")

        assertTrue(payload.redactionApplied)
        assertFalse(payload.rawTextIncluded)
    }

    @Test
    fun privacy_redact_false_ai_redact_false_includes_raw_text() = runTest {
        val policy = buildPolicy(privacyRedact = false, aiRedact = false)
        val rawText = "Receipt text no redaction needed"
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, rawText)

        assertFalse(payload.redactionApplied)
        assertTrue(payload.rawTextIncluded)
        assertEquals(rawText, payload.text)
    }

    @Test
    fun privacy_redact_false_ai_redact_true_still_redacts() = runTest {
        // PrivacySettings is authoritative — if either is true, redact
        val policy = buildPolicy(privacyRedact = false, aiRedact = true)
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "Some text")
        // AI redact=true should trigger redaction via EffectiveCloudAiPolicy
        assertTrue(payload.redactionApplied)
        assertFalse(payload.rawTextIncluded)
    }

    @Test
    fun bank_statement_uses_BANK_STATEMENT_VALIDATION_purpose() = runTest {
        val policy = buildPolicy(privacyRedact = false)  // even when not redacting generally
        val payload = policy.prepareBankStatementValidation("Bank statement text with IBAN GR123")
        assertEquals(CloudPayloadPurpose.BANK_STATEMENT_VALIDATION, payload.purpose)
    }

    @Test
    fun bank_statement_always_redacted_even_when_general_policy_allows_raw() = runTest {
        val policy = buildPolicy(privacyRedact = false, aiRedact = false)
        val payload = policy.prepareBankStatementValidation("Bank data with account GR1234")
        assertTrue("Bank statement payload must always be redacted", payload.redactionApplied)
        assertFalse("Raw text must never be included in bank statement payload", payload.rawTextIncluded)
    }

    @Test
    fun receipt_image_upload_suppressed_when_redaction_required() = runTest {
        val policy = buildPolicy(privacyRedact = true)
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "Receipt text")
        assertFalse("Image upload must be suppressed when redaction is required", payload.rawImageIncluded)
        assertNull(payload.imageBytes)
    }

    @Test
    fun prepared_cloud_payload_has_payload_hash() = runTest {
        val policy = buildPolicy(privacyRedact = true)
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "Test text")
        assertNotNull(payload.payloadHash)
        assertTrue(payload.payloadHash.isNotBlank())
    }

    @Test
    fun audit_metadata_does_not_contain_raw_text() = runTest {
        val policy = buildPolicy(privacyRedact = false)
        val rawText = "Sensitive receipt content"
        val payload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, rawText)
        val metaJson = payload.auditMetadata.toJson()
        assertFalse("Audit metadata must not contain raw text", metaJson.contains(rawText))
    }

    // ── CloudPayloadPurpose new values ────────────────────────────────────────

    @Test
    fun new_cloud_purposes_are_available() {
        // Ensure the new purposes are part of the enum
        val purposes = CloudPayloadPurpose.values()
        assertTrue(purposes.contains(CloudPayloadPurpose.BANK_STATEMENT_VALIDATION))
        assertTrue(purposes.contains(CloudPayloadPurpose.BANK_TRANSACTION_CLASSIFICATION))
        assertTrue(purposes.contains(CloudPayloadPurpose.EXPORT_SUMMARY))
    }

    // ── PreparedCloudPayload contract ─────────────────────────────────────────

    @Test
    fun prepared_cloud_payload_purpose_is_preserved() = runTest {
        val policy = buildPolicy(privacyRedact = true)
        val payload = policy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, "items text")
        assertEquals(CloudPayloadPurpose.ITEM_CATEGORIZATION, payload.purpose)
    }
}
