package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

data class EffectiveCloudAiPolicy(
    val cloudAllowed: Boolean,
    val reason: String?,
    val redactBeforeCloud: Boolean,
    val receiptImageUploadAllowed: Boolean,
    val bankStatementCloudAllowed: Boolean
) {
    /**
     * P8-PR1 (NEW-P8-005): Checks whether the specific [capability] is allowed.
     *
     * @throws SecurityException if the capability is blocked or unrecognised.
     *         Unrecognised capabilities throw rather than silently returning,
     *         so callers are forced to register new capabilities explicitly.
     */
    fun requireAllowed(capability: PrivacyCapability) {
        if (!cloudAllowed) {
            throw SecurityException("Cloud AI blocked by privacy policy: $reason")
        }
        // P8-PR1 (NEW-P8-005): Check specific capability, not just global cloud flag
        val capabilityAllowed = when (capability) {
            PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> receiptImageUploadAllowed
            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            PrivacyCapability.AI_BANK_STATEMENT_PARSING -> bankStatementCloudAllowed
            PrivacyCapability.CLOUD_AI_RECEIPT_OCR -> receiptImageUploadAllowed
            // All other capabilities must be explicitly listed; unrecognised ones
            // throw so the gap is discovered immediately at development time.
            else -> throw SecurityException(
                "EffectiveCloudAiPolicy does not recognise capability $capability — " +
                "it must be explicitly registered in requireAllowed()"
            )
        }
        if (!capabilityAllowed) {
            throw SecurityException("Capability $capability blocked by privacy policy")
        }
    }
}

@Singleton
class EffectiveCloudAiPolicyResolver @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val aiSettingsRepository: AiSettingsRepository
) {
    suspend fun resolve(): EffectiveCloudAiPolicy {
        val privacy = privacySettingsRepository.getSettings()
        val ai = aiSettingsRepository.settings().first()

        val cloudAllowed: Boolean
        val reason: String?

        when {
            !privacy.cloudAiEnabled -> {
                cloudAllowed = false
                reason = "Privacy settings: cloud AI disabled"
            }
            !ai.allowCloudAi -> {
                cloudAllowed = false
                reason = "AI settings: cloud AI disabled"
            }
            else -> {
                cloudAllowed = true
                reason = null
            }
        }

        val redactBeforeCloud = privacy.redactBeforeCloud || ai.redactBeforeCloud

        val receiptImageUploadAllowed = cloudAllowed &&
            privacy.receiptImageCloudEnabled &&
            ai.receiptImageCloudEnabled

        val bankStatementCloudAllowed = cloudAllowed &&
            privacy.bankStatementAiEnabled

        return EffectiveCloudAiPolicy(
            cloudAllowed = cloudAllowed,
            reason = reason,
            redactBeforeCloud = redactBeforeCloud,
            receiptImageUploadAllowed = receiptImageUploadAllowed,
            bankStatementCloudAllowed = bankStatementCloudAllowed
        )
    }

    companion object {
        /**
         * Returns a fail-closed resolver for use in secondary test constructors.
         * Always redacts, never allows image upload.
         */
        fun failClosedForTest(aiSettingsRepository: AiSettingsRepository): EffectiveCloudAiPolicyResolver {
            val failClosedRepo = object : PrivacySettingsRepository {
                private val settings = PrivacySettings.FAIL_CLOSED_DEFAULTS
                override fun observeSettings() = flowOf(settings)
                override fun observeLoadState() = flowOf(PrivacySettingsLoadState.FirstRunDefault(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.FirstRunDefault(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
            return EffectiveCloudAiPolicyResolver(failClosedRepo, aiSettingsRepository)
        }

        /**
         * Fail-closed resolver when no [AiSettingsRepository] is available (test constructors).
         * Returns policy with cloudAllowed=false and redactBeforeCloud=true.
         */
        fun failClosedNoAi(): EffectiveCloudAiPolicyResolver {
            val noOpAiRepo = object : AiSettingsRepository {
                override fun settings() = flowOf(AiSettings())
                override suspend fun update(transform: (AiSettings) -> AiSettings) {}
            }
            return failClosedForTest(noOpAiRepo)
        }
    }
}