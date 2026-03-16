package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.model.toRuntimeStatusMessage
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetAiRuntimeStatusUseCase @Inject constructor(
    private val aiEnvironmentMonitor: AiEnvironmentMonitor,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(
        capabilities: List<AiCapability>
    ): AiRuntimeStatusSummary {
        val settings = aiSettingsRepository.settings().first()
        val statuses = capabilities.map { capability ->
            val status = aiEnvironmentMonitor.getOnDeviceModelStatus(capability)
            val onDeviceMessage = status.toRuntimeStatusMessage(capability.runtimeLabel())
            val routeDecision = aiCapabilityRouter.decide(capability, settings, status)
            AiCapabilityRuntimeStatus(
                capability = capability,
                status = status,
                message = runtimeMessageFor(routeDecision.route, routeDecision.reason, onDeviceMessage),
                actionLabel = routeActionLabel(routeDecision.route, status),
                route = routeDecision.route,
                routeReason = routeDecision.reason,
                providerName = routeDecision.providerName,
                modelName = routeDecision.modelName,
                onDeviceMessage = onDeviceMessage
            )
        }

        val highestPriorityMessage = statuses
            .firstOrNull { it.message != null }
            ?.message

        return AiRuntimeStatusSummary(
            capabilities = statuses,
            highestPriorityMessage = highestPriorityMessage,
            networkAvailable = aiEnvironmentMonitor.isNetworkAvailable(),
            wifiConnected = aiEnvironmentMonitor.isWifiConnected(),
            lastRefreshedAt = timeProvider.now()
        )
    }
}

private fun routeActionLabel(route: AiRoute?, status: OnDeviceModelStatus): String? = when (route) {
    AiRoute.CLOUD -> null
    AiRoute.ON_DEVICE -> null
    AiRoute.DETERMINISTIC_FALLBACK,
    AiRoute.DISABLED,
    null -> status.actionLabel()
}

private fun runtimeMessageFor(
    route: AiRoute?,
    routeReason: String,
    onDeviceMessage: String?
): String? = when (route) {
    AiRoute.CLOUD -> null
    AiRoute.ON_DEVICE -> null
    AiRoute.DETERMINISTIC_FALLBACK,
    AiRoute.DISABLED -> routeReason
    null -> onDeviceMessage
}

private fun OnDeviceModelStatus.actionLabel(): String? = when (this) {
    OnDeviceModelStatus.AVAILABLE -> null
    OnDeviceModelStatus.NOT_INSTALLED -> "Install required"
    OnDeviceModelStatus.DOWNLOADING -> "Wait for download"
    OnDeviceModelStatus.UNAVAILABLE -> "Check device support"
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
