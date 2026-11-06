package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * N-05-2b: the AI master switch (`AiSettings.aiEnabled`) must dominate every cloud flag.
 * With aiEnabled=false the resolved policy must be fully closed with the bounded
 * CLOUD_AI_DISABLED reason, regardless of any other flag.
 */
class EffectiveCloudAiPolicyAiMasterSwitchTest {

    private fun privacyRepo(settings: PrivacySettings) = object : PrivacySettingsRepository {
        override fun observeSettings() = flowOf(settings)
        override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(settings))
        override suspend fun getSettings() = settings
        override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
        override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
    }

    private fun aiRepo(settings: AiSettings) = object : AiSettingsRepository {
        override fun settings() = flowOf(settings)
        override suspend fun update(transform: (AiSettings) -> AiSettings) {}
    }

    private val allCloudOnPrivacy = PrivacySettings(
        cloudAiEnabled = true,
        redactBeforeCloud = false,
        receiptImageCloudEnabled = true,
        bankStatementAiEnabled = true
    )

    private fun allCloudOnAi(aiEnabled: Boolean) = AiSettings(
        aiEnabled = aiEnabled,
        allowCloudAi = true,
        receiptImageCloudEnabled = true,
        redactBeforeCloud = false
    )

    private suspend fun resolve(privacy: PrivacySettings, ai: AiSettings) =
        EffectiveCloudAiPolicyResolver(privacyRepo(privacy), aiRepo(ai)).resolve()

    private val cloudCapabilities = listOf(
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
        PrivacyCapability.CLOUD_AI_RECEIPT_OCR,
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD,
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
        PrivacyCapability.CLOUD_AI_GENERAL,
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
        PrivacyCapability.AI_BANK_STATEMENT_PARSING
    )

    @Test
    fun N052b_aiDisabled_withEveryCloudFlagOn_deniesCloudWithBoundedReason() = runTest {
        val policy = resolve(allCloudOnPrivacy, allCloudOnAi(aiEnabled = false))

        assertFalse(policy.cloudAllowed)
        assertEquals(PrivacyGateReasonCodes.CLOUD_AI_DISABLED, policy.reason)
    }

    @Test
    fun N052b_aiDisabled_deniesEveryDerivedCapability() = runTest {
        val policy = resolve(allCloudOnPrivacy, allCloudOnAi(aiEnabled = false))

        assertFalse(policy.receiptImageUploadAllowed)
        assertFalse(policy.bankStatementCloudAllowed)
    }

    @Test
    fun N052b_aiDisabled_requireAllowedThrowsCloudAiDisabledForEveryCloudCapability() = runTest {
        val policy = resolve(allCloudOnPrivacy, allCloudOnAi(aiEnabled = false))

        for (capability in cloudCapabilities) {
            try {
                policy.requireAllowed(capability)
                fail("Expected SecurityException for $capability when aiEnabled=false")
            } catch (e: SecurityException) {
                assertEquals(capability.name, PrivacyGateReasonCodes.CLOUD_AI_DISABLED, e.message)
            }
        }
    }

    @Test
    fun N052b_aiEnabled_withEveryCloudFlagOn_behaviourUnchanged_allowsCloud() = runTest {
        val policy = resolve(allCloudOnPrivacy, allCloudOnAi(aiEnabled = true))

        assertTrue(policy.cloudAllowed)
        assertNull(policy.reason)
        assertTrue(policy.receiptImageUploadAllowed)
        assertTrue(policy.bankStatementCloudAllowed)
        assertFalse(policy.redactBeforeCloud)
        policy.requireAllowed(PrivacyCapability.CLOUD_AI_GENERAL)
        policy.requireAllowed(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)
        policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
        policy.requireAllowed(PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD)
    }

    @Test
    fun N052b_aiEnabled_privacyCloudOff_keepsPrivacyReason() = runTest {
        val policy = resolve(
            allCloudOnPrivacy.copy(cloudAiEnabled = false),
            allCloudOnAi(aiEnabled = true)
        )

        assertFalse(policy.cloudAllowed)
        assertEquals("Privacy settings: cloud AI disabled", policy.reason)
    }

    @Test
    fun N052b_aiEnabled_aiCloudOff_keepsAiSettingsReason() = runTest {
        val policy = resolve(
            allCloudOnPrivacy,
            allCloudOnAi(aiEnabled = true).copy(allowCloudAi = false)
        )

        assertFalse(policy.cloudAllowed)
        assertEquals("AI settings: cloud AI disabled", policy.reason)
    }

    @Test
    fun N052b_aiDisabled_andPrivacyCloudOff_masterSwitchReasonWins() = runTest {
        val policy = resolve(
            allCloudOnPrivacy.copy(cloudAiEnabled = false),
            allCloudOnAi(aiEnabled = false).copy(allowCloudAi = false)
        )

        assertFalse(policy.cloudAllowed)
        assertEquals(PrivacyGateReasonCodes.CLOUD_AI_DISABLED, policy.reason)
    }
}
