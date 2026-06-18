package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiPolicyTest {

    private lateinit var policy: AiPolicyImpl

    @Before
    fun setup() {
        policy = AiPolicyImpl()
    }

    // ── canUseCloud ───────────────────────────────────────────────────────────

    @Test
    fun `canUseCloud returns false when both aiEnabled and allowCloudAi are false`() {
        val settings = AiSettings(aiEnabled = false, allowCloudAi = false)
        assertFalse(policy.canUseCloud(settings))
    }

    @Test
    fun `canUseCloud returns false when aiEnabled is false but allowCloudAi is true`() {
        val settings = AiSettings(aiEnabled = false, allowCloudAi = true)
        assertFalse(policy.canUseCloud(settings))
    }

    @Test
    fun `canUseCloud returns false when aiEnabled is true but allowCloudAi is false`() {
        val settings = AiSettings(aiEnabled = true, allowCloudAi = false)
        assertFalse(policy.canUseCloud(settings))
    }

    @Test
    fun `canUseCloud returns true when both aiEnabled and allowCloudAi are true`() {
        val settings = AiSettings(aiEnabled = true, allowCloudAi = true)
        assertTrue(policy.canUseCloud(settings))
    }

    @Test
    fun `canUseCloudFor returns false when capability flag is off`() {
        val settings = AiSettings(aiEnabled = true, allowCloudAi = true, reviewExplanationEnabled = false)
        assertFalse(policy.canUseCloudFor(settings, AiCapability.REVIEW_EXPLANATION))
    }

    @Test
    fun `canUseCloudFor returns true when cloud and capability are enabled`() {
        val settings = AiSettings(aiEnabled = true, allowCloudAi = true, reviewExplanationEnabled = true)
        assertTrue(policy.canUseCloudFor(settings, AiCapability.REVIEW_EXPLANATION))
    }

    @Test
    fun `canUseCloudFor receipt extraction does not require image toggle`() {
        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            receiptAssistEnabled = true,
            receiptImageCloudEnabled = false
        )

        assertTrue(policy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION))
    }

    @Test
    fun `canUseCloudFor warranty extraction follows warranty extraction toggle`() {
        val enabled = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            receiptAssistEnabled = true,
            warrantyExtractionEnabled = true
        )
        val disabled = enabled.copy(warrantyExtractionEnabled = false)

        assertTrue(policy.canUseCloudFor(enabled, AiCapability.WARRANTY_EXTRACTION))
        assertFalse(policy.canUseCloudFor(disabled, AiCapability.WARRANTY_EXTRACTION))
    }

    @Test
    fun `canUseCloudFor warranty extraction stays disabled when receipt assist is enabled`() {
        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            receiptAssistEnabled = true,
            warrantyExtractionEnabled = false
        )

        assertFalse(policy.canUseCloudFor(settings, AiCapability.WARRANTY_EXTRACTION))
    }

    @Test
    fun `shouldAllowOnDevice returns false when on-device is disabled`() {
        val settings = AiSettings(aiEnabled = true, allowOnDeviceAi = false, receiptAssistEnabled = true)
        assertFalse(policy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION))
    }

    @Test
    fun `shouldAllowOnDevice returns true when on-device and capability are enabled`() {
        val settings = AiSettings(aiEnabled = true, allowOnDeviceAi = true, receiptAssistEnabled = true)
        assertTrue(policy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION))
    }

    // ── shouldRedact ──────────────────────────────────────────────────────────

    @Test
    fun `shouldRedact returns true when redactBeforeCloud is true regardless of capability`() {
        val settings = AiSettings(redactBeforeCloud = true)
        for (capability in AiCapability.entries) {
            assertTrue(
                "Expected shouldRedact=true for capability $capability",
                policy.shouldRedact(settings, capability)
            )
        }
    }

    @Test
    fun `shouldRedact returns false when redactBeforeCloud is false regardless of capability`() {
        val settings = AiSettings(redactBeforeCloud = false)
        for (capability in AiCapability.entries) {
            assertFalse(
                "Expected shouldRedact=false for capability $capability",
                policy.shouldRedact(settings, capability)
            )
        }
    }

    @Test
    fun `shouldRedact is not influenced by aiEnabled or allowCloudAi flags`() {
        // redactBeforeCloud=true should win even if AI cloud is fully enabled
        val allOn = AiSettings(aiEnabled = true, allowCloudAi = true, redactBeforeCloud = true)
        assertTrue(policy.shouldRedact(allOn, AiCapability.REVIEW_EXPLANATION))

        // redactBeforeCloud=false should give false even if AI is disabled
        val allOff = AiSettings(aiEnabled = false, allowCloudAi = false, redactBeforeCloud = false)
        assertFalse(policy.shouldRedact(allOff, AiCapability.REVIEW_EXPLANATION))
    }

    // ── defaults ──────────────────────────────────────────────────────────────

    @Test
    fun `default AiSettings result in canUseCloud false and shouldRedact true`() {
        val defaults = AiSettings() // aiEnabled=false, allowCloudAi=false, redactBeforeCloud=true
        assertFalse(policy.canUseCloud(defaults))
        assertTrue(policy.shouldRedact(defaults, AiCapability.REVIEW_EXPLANATION))
    }
}
