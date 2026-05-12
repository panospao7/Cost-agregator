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
 * Scenario tests verifying cloud-AI and location privacy gates deny
 * capabilities when their corresponding settings are disabled.
 *
 * These tests validate that [CloudAiPrivacyGate] and [LocationPrivacyGate]
 * correctly enforce their respective [PrivacySettings] toggles, and that
 * [CompositePrivacyGate] short-circuits on the first denial without
 * evaluating subsequent gates.
 */
class PrivacyCloudLocationDeniedScenarioTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Cloud AI denied blocks warranty extraction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cloud AI denied blocks warranty extraction`() = runTest {
        // GIVEN: a CloudAiPrivacyGate with cloud AI capabilities disabled
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

        // WHEN: checking CLOUD_AI_WARRANTY_EXTRACTION capability
        val decision = gate.check(PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION)

        // THEN: returns Denied
        assertThat(decision).isInstanceOf(PrivacyDecision.Denied::class.java)
        val denied = decision as PrivacyDecision.Denied
        assertThat(denied.reason).isNotEmpty()
        assertThat(denied.reason).contains("Cloud AI is disabled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Device GPS denied blocks location access
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `device GPS denied blocks location access`() = runTest {
        // GIVEN: a LocationPrivacyGate with device GPS location disabled
        val settingsRepository = mockk<PrivacySettingsRepository>()
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val gate = LocationPrivacyGate(settingsRepository, auditLogger)

        // AND: deviceGpsLocationEnabled = false in settings
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
    // Test 3: Composite gate respects first denial
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `composite gate respects first denial`() = runTest {
        // GIVEN: two privacy gates where the first one denies
        val firstGate = mockk<PrivacyGate>()
        val secondGate = mockk<PrivacyGate>(relaxed = true)
        val denied = PrivacyDecision.Denied("First gate blocks")

        coEvery { firstGate.check(any(), any()) } returns denied

        val composite = CompositePrivacyGate(listOf(firstGate, secondGate))

        // WHEN: checking the composite gate
        val decision = composite.check(PrivacyCapability.DEVICE_GPS_LOCATION)

        // THEN: returns Denied from the first gate
        assertThat(decision).isEqualTo(denied)

        // AND: first gate was checked exactly once
        coVerify(exactly = 1) { firstGate.check(any(), any()) }

        // AND: second gate was never checked
        coVerify(exactly = 0) { secondGate.check(any(), any()) }
    }
}
