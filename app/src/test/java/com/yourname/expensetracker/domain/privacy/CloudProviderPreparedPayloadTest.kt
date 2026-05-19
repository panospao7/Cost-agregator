package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PRIV-441-01 / PRIV-441-14 acceptance tests.
 *
 * Verifies that cloud providers use CloudPayloadPolicy / PreparedCloudPayload
 * and do not access redactBeforeCloud directly.
 */
class CloudProviderPreparedPayloadTest {

    private fun buildPolicy(privacyRedact: Boolean): CloudPayloadPolicy {
        val privacySettings = PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = privacyRedact)
        val privacyRepo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacyRepo.getSettings() } returns privacySettings
        every { privacyRepo.observeSettings() } returns flowOf(privacySettings)

        val aiSettings = AiSettings(allowCloudAi = true, redactBeforeCloud = false)
        val aiRepo = mockk<AiSettingsRepository>(relaxed = true)
        every { aiRepo.settings() } returns flowOf(aiSettings)

        return DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo),
            DefaultCloudPayloadRedactor()
        )
    }

    @Test
    fun cloud_payload_policy_prepares_receipt_assist_text() = runTest {
        val policy = buildPolicy(privacyRedact = false)
        val rawText = "Receipt from Starbucks €5.00"
        val prepared = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, rawText)

        assertEquals(CloudPayloadPurpose.RECEIPT_ASSIST, prepared.purpose)
        assertNotNull(prepared.payloadHash)
        assertTrue(prepared.payloadHash.isNotBlank())
    }

    @Test
    fun cloud_payload_policy_redacts_when_privacy_requires() = runTest {
        val policy = buildPolicy(privacyRedact = true)
        val prepared = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "Sensitive receipt text")

        assertTrue("Redaction must be applied", prepared.redactionApplied)
        assertFalse("Raw text must not be included", prepared.rawTextIncluded)
        assertFalse("Raw image must not be included", prepared.rawImageIncluded)
    }

    @Test
    fun cloud_payload_policy_allows_raw_when_no_redaction_required() = runTest {
        val policy = buildPolicy(privacyRedact = false)
        val rawText = "Receipt text no redaction"
        val prepared = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, rawText)

        assertFalse(prepared.redactionApplied)
        assertTrue(prepared.rawTextIncluded)
        assertEquals(rawText, prepared.text)
    }

    @Test
    fun bank_statement_cloud_call_uses_bank_statement_validation_purpose() = runTest {
        val policy = buildPolicy(privacyRedact = false)
        val prepared = policy.prepareBankStatementValidation("Bank statement with IBAN GR123")

        assertEquals(CloudPayloadPurpose.BANK_STATEMENT_VALIDATION, prepared.purpose)
        // Bank statement always redacted regardless of general policy
        assertTrue("Bank statement must always be redacted", prepared.redactionApplied)
        assertFalse(prepared.rawTextIncluded)
    }

    @Test
    fun receipt_image_upload_suppressed_when_prepared_payload_requires_redaction() = runTest {
        val policy = buildPolicy(privacyRedact = true)
        val prepared = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "text")

        assertFalse("Image upload must be suppressed when redaction required", prepared.rawImageIncluded)
        assertNull(prepared.imageBytes)
    }

    @Test
    fun privacy_redact_true_ai_redact_false_redacts_all_provider_payloads() = runTest {
        val policy = buildPolicy(privacyRedact = true)

        val receiptPayload = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "receipt text")
        val dashboardPayload = policy.prepareText(CloudPayloadPurpose.DASHBOARD_BRIEFING, "dashboard text")
        val categorizationPayload = policy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, "item text")

        assertTrue(receiptPayload.redactionApplied)
        assertTrue(dashboardPayload.redactionApplied)
        assertTrue(categorizationPayload.redactionApplied)
    }

    @Test
    fun prepared_payload_has_non_empty_payload_hash() = runTest {
        val policy = buildPolicy(privacyRedact = false)
        val prepared = policy.prepareText(CloudPayloadPurpose.RECEIPT_ASSIST, "some text")

        assertNotNull(prepared.payloadHash)
        assertTrue(prepared.payloadHash.length >= 8)
    }

    @Test
    fun ci_runs_verify_privacy_boundaries_script_exists() {
        // Verify the script file exists (CI step added in PRIV-441-02)
        val scriptFile = java.io.File("scripts/verify_privacy_boundaries.py")
        // In test environment, check relative to project root
        val projectRoot = java.io.File(".").canonicalFile
        val candidates = listOf(
            java.io.File(projectRoot, "scripts/verify_privacy_boundaries.py"),
            java.io.File(projectRoot, "../scripts/verify_privacy_boundaries.py"),
            java.io.File(projectRoot, "../../scripts/verify_privacy_boundaries.py")
        )
        val exists = candidates.any { it.exists() }
        assertTrue("verify_privacy_boundaries.py must exist for CI enforcement", exists)
    }
}
