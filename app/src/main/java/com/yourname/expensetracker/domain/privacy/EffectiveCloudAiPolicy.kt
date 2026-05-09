package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class EffectiveCloudAiPolicy(
    val cloudAllowed: Boolean,
    val reason: String?,
    val redactBeforeCloud: Boolean,
    val receiptImageUploadAllowed: Boolean,
    val bankStatementCloudAllowed: Boolean
) {
    fun requireAllowed(capability: PrivacyCapability) {
        if (!cloudAllowed) {
            throw SecurityException("Cloud AI blocked by privacy policy: $reason")
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
}