package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPolicyImpl @Inject constructor() : AiPolicy {

    override fun canUseCloud(settings: AiSettings): Boolean =
        settings.aiEnabled && settings.allowCloudAi

    override fun canUseCloudFor(settings: AiSettings, capability: AiCapability): Boolean {
        if (!canUseCloud(settings)) return false

        return when (capability) {
            AiCapability.DASHBOARD_BRIEFING -> settings.dashboardBriefingEnabled
            AiCapability.REVIEW_EXPLANATION -> settings.reviewExplanationEnabled
            AiCapability.QUERY_INTERPRETATION -> settings.queryInterpretationEnabled
            AiCapability.RECEIPT_EXTRACTION -> settings.receiptAssistEnabled
            AiCapability.CATEGORIZATION_FALLBACK -> settings.categorizationFallbackEnabled
            AiCapability.DEDUPE_JUDGE -> settings.dedupeJudgeEnabled
            AiCapability.LOCATION_SUMMARY -> settings.aiEnabled
        }
    }

    override fun shouldAllowOnDevice(settings: AiSettings, capability: AiCapability): Boolean {
        if (!settings.aiEnabled || !settings.allowOnDeviceAi) return false

        return when (capability) {
            AiCapability.DASHBOARD_BRIEFING -> settings.dashboardBriefingEnabled
            AiCapability.REVIEW_EXPLANATION -> settings.reviewExplanationEnabled
            AiCapability.QUERY_INTERPRETATION -> settings.queryInterpretationEnabled
            AiCapability.RECEIPT_EXTRACTION -> settings.receiptAssistEnabled
            AiCapability.CATEGORIZATION_FALLBACK -> settings.categorizationFallbackEnabled
            AiCapability.DEDUPE_JUDGE -> settings.dedupeJudgeEnabled
            AiCapability.LOCATION_SUMMARY -> settings.aiEnabled
        }
    }

    override fun shouldRedact(settings: AiSettings, capability: AiCapability): Boolean =
        settings.redactBeforeCloud
}
