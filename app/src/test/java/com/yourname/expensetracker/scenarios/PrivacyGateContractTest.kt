package com.yourname.expensetracker.scenarios

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.privacy.CloudAiPrivacyGate
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.DefaultRedactionSanitizer
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Contract tests for the privacy subsystem.
 *
 * These tests validate the core contracts of [PrivacyGate], [PrivacyDecision],
 * [CompositePrivacyGate], [RedactionSanitizer], and [PrivacySettings] without
 * requiring a Room database or Android dependencies.
 *
 * GIVEN / WHEN / THEN structure follows the scenario testing pattern used
 * throughout the project.
 */
class PrivacyGateContractTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Privacy gate denies disabled capability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `privacy gate denies disabled capability`() = runTest {
        // GIVEN: a PrivacyGate that checks if capability is allowed
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)

        // AND: capability is set to DISALLOWED in settings
        val disabledSettings = PrivacySettings(cloudAiEnabled = false)
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = false,
            reason = "Privacy settings: cloud AI disabled",
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )

        // WHEN: checking the gate for a cloud AI capability
        val decision = gate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: returns Denied with a reason
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        val denied = decision as PrivacyDecision.Denied
        assertThat(denied.reason).isNotEmpty()
        assertThat(denied.reason).contains("Cloud AI is disabled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Privacy gate allows enabled capability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `privacy gate allows enabled capability`() = runTest {
        // GIVEN: a PrivacyGate that checks if capability is allowed
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)

        // AND: capability is set to ALLOWED
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = false,
            receiptImageUploadAllowed = true,
            bankStatementCloudAllowed = true
        )

        // WHEN: checking the gate for a cloud AI capability
        val decision = gate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: returns Allowed
        assertThat(decision).isEqualTo(PrivacyDecision.Allowed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Composite privacy gate short-circuits on first denial
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `composite privacy gate short-circuits on first denial`() = runTest {
        // GIVEN: multiple gates, first one denies
        val firstGate = mockk<PrivacyGate>()
        val secondGate = mockk<PrivacyGate>(relaxed = true)

        val denied = PrivacyDecision.Denied("First gate blocks")
        coEvery { firstGate.check(any(), any()) } returns denied

        val composite = CompositePrivacyGate(listOf(firstGate, secondGate))

        // WHEN: checking composite gate
        val decision = composite.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: returns Denied from first gate, does not check later gates
        assertThat(decision).isEqualTo(denied)
        coVerify(exactly = 1) { firstGate.check(any(), any()) }
        coVerify(exactly = 0) { secondGate.check(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Redaction sanitizer removes sensitive data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `redaction sanitizer removes sensitive data`() {
        // GIVEN: a RedactionSanitizer and sample merchant text
        val sanitizer = DefaultRedactionSanitizer()

        // WHEN: sanitizing a known merchant name
        val original = "Walmart"
        val sanitized = sanitizer.sanitizeMerchant(original)

        // THEN: the original merchant name is not contained in the output
        assertThat(sanitized).doesNotContain("Walmart")
        assertThat(sanitized).doesNotContain("walmart")
        assertThat(sanitized).doesNotContain("WALMART")

        // AND: the output follows the "merchant_<sha256-prefix>" pattern
        val expectedPrefix = "merchant_${original.trim().take(80).sha256Prefix()}"
        assertThat(sanitized).isEqualTo(expectedPrefix)

        // AND: blank input produces "merchant_unknown"
        assertThat(sanitizer.sanitizeMerchant("   ")).isEqualTo("merchant_unknown")
        assertThat(sanitizer.sanitizeMerchant("")).isEqualTo("merchant_unknown")

        // AND: sanitization is deterministic (same input → same output)
        assertThat(sanitizer.sanitizeMerchant("Walmart"))
            .isEqualTo(sanitizer.sanitizeMerchant("Walmart"))

        // AND: different inputs produce different outputs
        assertThat(sanitizer.sanitizeMerchant("Walmart"))
            .isNotEqualTo(sanitizer.sanitizeMerchant("Target"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Privacy settings default values are correct
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `privacy settings default values are correct`() {
        // GIVEN: default PrivacySettings instance
        val settings = PrivacySettings()

        // THEN: all cloud AI features default to disabled
        assertThat(settings.cloudAiEnabled).isFalse()
        assertThat(settings.receiptImageCloudEnabled).isFalse()
        assertThat(settings.bankStatementAiEnabled).isFalse()

        // THEN: redaction is enabled by default (privacy-preserving default)
        assertThat(settings.redactBeforeCloud).isTrue()

        // THEN: location-based features default to disabled
        assertThat(settings.externalGeocodingEnabled).isFalse()
        assertThat(settings.backgroundLocationBackfillEnabled).isFalse()
        assertThat(settings.deviceGpsLocationEnabled).isFalse()

        // THEN: notification capture defaults to enabled (core app feature)
        assertThat(settings.notificationCaptureEnabled).isTrue()

        // THEN: encrypted backup defaults to enabled (data safety)
        assertThat(settings.encryptedBackupEnabled).isTrue()

        // THEN: data retention defaults to 30 days
        assertThat(settings.rawNotificationRetentionDays).isEqualTo(30)
        assertThat(settings.rawOcrRetentionDays).isEqualTo(30)

        // THEN: debug data persistence defaults to disabled
        assertThat(settings.debugDataPersistenceEnabled).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Supporting contract tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cloud ai gate logs every check decision`() = runTest {
        // GIVEN: a CloudAiPrivacyGate with a mocked audit logger
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = false, reason = "default", redactBeforeCloud = false,
            receiptImageUploadAllowed = false, bankStatementCloudAllowed = false
        )

        // WHEN: checking the gate (capability is disabled by default)
        gate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: the audit logger was called exactly once
        coVerify(exactly = 1) {
            auditLogger.logDecision(
                PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
                any<PrivacyDecision>(),
                any()
            )
        }
    }

    @Test
    fun `unrecognised capability is allowed by cloud ai gate`() = runTest {
        // GIVEN: a CloudAiPrivacyGate
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = true, reason = null, redactBeforeCloud = false,
            receiptImageUploadAllowed = true, bankStatementCloudAllowed = true
        )

        // WHEN: checking a capability not handled by CloudAiPrivacyGate
        val decision = gate.check(PrivacyCapability.NOTIFICATION_CAPTURE)

        // THEN: returns Allowed (gate delegates unrecognised capabilities)
        assertThat(decision).isEqualTo(PrivacyDecision.Allowed)
    }

    @Test
    fun `composite gate allows when all gates allow`() = runTest {
        // GIVEN: multiple gates that all allow
        val firstGate = mockk<PrivacyGate>()
        val secondGate = mockk<PrivacyGate>()
        coEvery { firstGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { secondGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val composite = CompositePrivacyGate(listOf(firstGate, secondGate))

        // WHEN: checking composite gate
        val decision = composite.check(PrivacyCapability.NOTIFICATION_CAPTURE)

        // THEN: returns Allowed
        assertThat(decision).isEqualTo(PrivacyDecision.Allowed)
        coVerify(exactly = 1) { firstGate.check(any(), any()) }
        coVerify(exactly = 1) { secondGate.check(any(), any()) }
    }

    @Test
    fun `receipt image upload denied when redactBeforeCloud is on`() = runTest {
        // GIVEN: cloud AI enabled but redactBeforeCloud is true
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = true, reason = null, redactBeforeCloud = true,
            receiptImageUploadAllowed = false, bankStatementCloudAllowed = true
        )

        // WHEN: checking RECEIPT_IMAGE_CLOUD_UPLOAD
        val decision = gate.check(PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD)

        // THEN: denied because images cannot be redacted meaningfully
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        assertThat((decision as PrivacyDecision.Denied).reason)
            .contains("image upload suppressed")
    }

    @Test
    fun `bank statement ai disabled denies bank capabilities`() = runTest {
        // GIVEN: cloud AI enabled but bank statement AI disabled
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)
        coEvery { policyResolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = true, reason = null, redactBeforeCloud = false,
            receiptImageUploadAllowed = true, bankStatementCloudAllowed = false
        )

        // WHEN: checking CLOUD_AI_BANK_STATEMENT
        val decision = gate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)

        // THEN: denied with specific reason
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        assertThat((decision as PrivacyDecision.Denied).reason)
            .contains("Bank statement AI is disabled")
    }
}
