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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    ): AiRuntimeStatusSummary = coroutineScope {
        val settings = aiSettingsRepository.settings().first()
        val statuses = capabilities.map { capability ->
            async {
                val status = aiEnvironmentMonitor.getOnDeviceModelStatus(capability)
                val onDeviceMessage = status.toRuntimeStatusMessage(capability.runtimeLabel())
                val routeDecision = aiCapabilityRouter.decide(capability, settings, status)
                AiCapabilityRuntimeStatus(
                    capability = capability,
                    status = status,
                    message = runtimeMessageFor(capability, routeDecision.route, routeDecision.reason, onDeviceMessage),
                    actionLabel = routeActionLabel(routeDecision.route, status),
                    route = routeDecision.route,
                    routeReason = routeDecision.reason,
                    providerName = routeDecision.providerName,
                    modelName = routeDecision.modelName,
                    onDeviceMessage = onDeviceMessage
                )
            }
        }.awaitAll()

        // B2e: TRANSACTION_INSIGHT has its own settings row, so its message (for example the router's
        // DISABLED reason when the insight toggle is off) must not feed the shared summary/guidance card.
        val highestPriorityMessage = statuses
            .firstOrNull { it.capability != AiCapability.TRANSACTION_INSIGHT && it.message != null }
            ?.message

        AiRuntimeStatusSummary(
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

private const val INSIGHT_ON_DEVICE_OFF_MESSAGE = "On-device AI is turned off in settings."

private fun runtimeMessageFor(
    capability: AiCapability,
    route: AiRoute?,
    routeReason: String,
    onDeviceMessage: String?
): String? = when (route) {
    AiRoute.CLOUD -> null
    AiRoute.ON_DEVICE -> null
    // B2e (UX 2.2): the insight is on-device only, so its fallback message must never carry the
    // router's combined reason (which names cloud). Use the on-device status text instead.
    AiRoute.DETERMINISTIC_FALLBACK ->
        if (capability == AiCapability.TRANSACTION_INSIGHT) {
            onDeviceMessage ?: INSIGHT_ON_DEVICE_OFF_MESSAGE
        } else {
            routeReason
        }
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
    AiCapability.WARRANTY_EXTRACTION -> "warranty extraction"
    AiCapability.CATEGORIZATION_FALLBACK -> "categorization"
    AiCapability.DEDUPE_JUDGE -> "duplicate detection"
    AiCapability.LOCATION_SUMMARY -> "location summaries"
    AiCapability.NOTIFICATION_PARSE -> "notification parsing"
    AiCapability.REVIEW_PRIORITIZATION -> "review prioritization"
    AiCapability.SEMANTIC_DEDUPE -> "semantic duplicate detection"
    AiCapability.RECEIPT_ITEM_CATEGORIZATION -> "receipt item categorization"
    AiCapability.TRANSACTION_INSIGHT -> "transaction insight"
}
