package com.yourname.expensetracker.scenarios

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.privacy.CloudAiPrivacyGate
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.LocationPrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Scenario tests verifying privacy gates block capabilities.
 *
 * These tests validate that each [PrivacyGate] implementation correctly enforces
 * its corresponding [PrivacySettings] toggles, that [CompositePrivacyGate]
 * short-circuits on the first denial, and that audit events are logged.
 *
 * GIVEN / WHEN / THEN structure follows the scenario testing pattern used
 * throughout the project.
 */
class PrivacyGateEnforcementScenarioTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Cloud AI denied blocks warranty extraction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cloud AI denied blocks warranty extraction`() = runTest {
        // GIVEN: a CloudAiPrivacyGate with cloud AI disabled
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)

        // AND: cloud AI disabled in resolved policy
        coEvery { policyResolver.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = false,
            reason = "Cloud AI is disabled",
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )

        // WHEN: calling privacyGate.check(CLOUD_AI_WARRANTY_EXTRACTION)
        val decision = gate.check(PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION)

        // THEN: returns Denied decision
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        val denied = decision as PrivacyDecision.Denied
        assertThat(denied.reason).isNotEmpty()
        assertThat(denied.reason).contains("Cloud AI is disabled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Device GPS denied blocks location access (LocationPrivacyGate)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `device GPS denied blocks location access`() = runTest {
        // GIVEN: privacy settings with deviceGpsLocationEnabled = false
        val settingsRepository = mockk<PrivacySettingsRepository>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = LocationPrivacyGate(settingsRepository, auditLogger)
        coEvery { settingsRepository.getSettings() } returns PrivacySettings(deviceGpsLocationEnabled = false)

        // WHEN: checking DEVICE_GPS_LOCATION capability
        val decision = gate.check(PrivacyCapability.DEVICE_GPS_LOCATION)

        // THEN: returns Denied
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        val denied = decision as PrivacyDecision.Denied
        assertThat(denied.reason).isNotEmpty()
        assertThat(denied.reason).contains("GPS")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Composite gate short-circuits on first denial
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `composite gate short-circuits on first denial`() = runTest {
        // GIVEN: multiple gates, first one denies
        val firstGate = mockk<PrivacyGate>()
        val secondGate = mockk<PrivacyGate>(relaxed = true)
        val denied = PrivacyDecision.Denied("First gate blocks")

        coEvery { firstGate.check(any(), any()) } returns denied

        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val composite = CompositePrivacyGate(listOf(firstGate, secondGate), auditLogger)

        // WHEN: checking composite gate
        val decision = composite.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: returns Denied from first gate without checking others
        assertThat(decision).isEqualTo(denied)
        coVerify(exactly = 1) { firstGate.check(any(), any()) }
        coVerify(exactly = 0) { secondGate.check(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Privacy audit event logged on denial
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `privacy audit event logged on denial`() = runTest {
        // GIVEN: privacy audit logger
        val policyResolver = mockk<EffectiveCloudAiPolicyResolver>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = CloudAiPrivacyGate(policyResolver, auditLogger)
        coEvery { policyResolver.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = false,
            reason = "Cloud AI is disabled",
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )

        // WHEN: a capability is denied
        gate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)

        // THEN: audit event is recorded (verify via mock)
        coVerify(exactly = 1) {
            auditLogger.logDecision(
                PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
                any<PrivacyDecision>(),
                any()
            )
        }
    }
}
