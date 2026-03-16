package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.toRuntimeStatusMessage
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import javax.inject.Inject

class GetAiRuntimeStatusUseCase @Inject constructor(
    private val aiEnvironmentMonitor: AiEnvironmentMonitor
) {

    suspend operator fun invoke(
        capabilities: List<AiCapability>
    ): AiRuntimeStatusSummary {
        val statuses = capabilities.map { capability ->
            val status = aiEnvironmentMonitor.getOnDeviceModelStatus(capability)
            AiCapabilityRuntimeStatus(
                capability = capability,
                status = status,
                message = status.toRuntimeStatusMessage(capability.runtimeLabel()),
                actionLabel = status.actionLabel()
            )
        }

        val highestPriorityMessage = statuses
            .firstOrNull { it.message != null }
            ?.message

        return AiRuntimeStatusSummary(
            capabilities = statuses,
            highestPriorityMessage = highestPriorityMessage
        )
    }
}

private fun OnDeviceModelStatus.actionLabel(): String? = when (this) {
    OnDeviceModelStatus.AVAILABLE -> null
    OnDeviceModelStatus.NOT_INSTALLED -> "Install required"
    OnDeviceModelStatus.DOWNLOADING -> "Wait for download"
    OnDeviceModelStatus.UNAVAILABLE -> "Try again later"
    OnDeviceModelStatus.UNSUPPORTED_DEVICE -> "Unsupported device"
    OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION -> "Update Android"
    OnDeviceModelStatus.DISABLED_BY_POLICY -> "Enable on-device AI"
    OnDeviceModelStatus.UNKNOWN -> "Refresh status"
}

private fun AiCapability.runtimeLabel(): String = when (this) {
    AiCapability.DASHBOARD_BRIEFING -> "briefing"
    AiCapability.REVIEW_EXPLANATION -> "review explanations"
    AiCapability.QUERY_INTERPRETATION -> "AI"
    AiCapability.RECEIPT_EXTRACTION -> "receipt assist"
    AiCapability.CATEGORIZATION_FALLBACK -> "categorization"
    AiCapability.DEDUPE_JUDGE -> "duplicate detection"
    AiCapability.LOCATION_SUMMARY -> "location summaries"
}
