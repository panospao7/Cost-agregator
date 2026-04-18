package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAiCapabilityRouter @Inject constructor(
    private val aiPolicy: AiPolicy,
    private val environmentMonitor: AiEnvironmentMonitor,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics
) : AiCapabilityRouter {

    override suspend fun decide(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision {
        if (!settings.aiEnabled) {
            return AiRouteDecision(AiRoute.DISABLED, "AI is disabled in settings.")
        }

        if (!isCapabilityEnabled(capability, settings)) {
            return AiRouteDecision(AiRoute.DISABLED, "$capability is disabled in settings.")
        }

        val resolvedOnDeviceStatus = onDeviceStatus ?: resolveOnDeviceStatus(capability, settings)

        val decision = when (settings.preferredMode) {
            AiMode.ON_DEVICE -> chooseOnDevicePreferred(capability, settings, resolvedOnDeviceStatus)
            AiMode.CLOUD -> chooseCloudPreferred(capability, settings, resolvedOnDeviceStatus)
            AiMode.AUTO -> chooseAuto(capability, settings, resolvedOnDeviceStatus)
        }

        aiRuntimeDiagnostics.recordRouteDecision(capability, decision)
        return decision
    }

    private suspend fun chooseOnDevicePreferred(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision {
        if (canUseOnDevice(capability, settings, onDeviceStatus)) {
            return AiRouteDecision(
                route = AiRoute.ON_DEVICE,
                reason = "Preferred mode is on-device and the local model is available.",
                providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
                modelName = capability.defaultOnDeviceModelName()
            )
        }

        if (canUseCloud(capability, settings)) {
            return AiRouteDecision(
                route = AiRoute.CLOUD,
                reason = "On-device was preferred but unavailable, so using cloud fallback.",
                providerName = capability.defaultCloudProviderName(),
                modelName = capability.defaultCloudModelName()
            )
        }

        return AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = combinedUnavailableReason(capability, settings, onDeviceStatus)
        )
    }

    private suspend fun chooseCloudPreferred(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision {
        if (canUseCloud(capability, settings)) {
            return AiRouteDecision(
                route = AiRoute.CLOUD,
                reason = "Preferred mode is cloud and connectivity/policy allow it.",
                providerName = capability.defaultCloudProviderName(),
                modelName = capability.defaultCloudModelName()
            )
        }

        if (canUseOnDevice(capability, settings, onDeviceStatus)) {
            return AiRouteDecision(
                route = AiRoute.ON_DEVICE,
                reason = "Cloud was preferred but unavailable, so using on-device fallback.",
                providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
                modelName = capability.defaultOnDeviceModelName()
            )
        }

        return AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = combinedUnavailableReason(capability, settings, onDeviceStatus)
        )
    }

    private suspend fun chooseAuto(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision {
        val prefersCloud = capability in CLOUD_FIRST_CAPABILITIES

        return if (prefersCloud) {
            if (canUseCloud(capability, settings)) {
                AiRouteDecision(
                    route = AiRoute.CLOUD,
                    reason = "AUTO mode selected the capability's cloud-first route.",
                    providerName = capability.defaultCloudProviderName(),
                    modelName = capability.defaultCloudModelName()
                )
            } else if (canUseOnDevice(capability, settings, onDeviceStatus)) {
                AiRouteDecision(
                    route = AiRoute.ON_DEVICE,
                    reason = "AUTO mode fell back from cloud to on-device.",
                    providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
                    modelName = capability.defaultOnDeviceModelName()
                )
            } else {
                AiRouteDecision(
                    route = AiRoute.DETERMINISTIC_FALLBACK,
                    reason = combinedUnavailableReason(capability, settings, onDeviceStatus)
                )
            }
        } else {
            if (canUseOnDevice(capability, settings, onDeviceStatus)) {
                AiRouteDecision(
                    route = AiRoute.ON_DEVICE,
                    reason = "AUTO mode selected the capability's on-device-first route.",
                    providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
                    modelName = capability.defaultOnDeviceModelName()
                )
            } else if (canUseCloud(capability, settings)) {
                AiRouteDecision(
                    route = AiRoute.CLOUD,
                    reason = "AUTO mode fell back from on-device to cloud.",
                    providerName = capability.defaultCloudProviderName(),
                    modelName = capability.defaultCloudModelName()
                )
            } else {
                AiRouteDecision(
                    route = AiRoute.DETERMINISTIC_FALLBACK,
                    reason = combinedUnavailableReason(capability, settings, onDeviceStatus)
                )
            }
        }
    }

    private fun canUseCloud(capability: AiCapability, settings: AiSettings): Boolean {
        if (!aiPolicy.canUseCloudFor(settings, capability)) return false
        if (!environmentMonitor.isNetworkAvailable()) return false
        if (settings.wifiOnlyForCloud && !environmentMonitor.isWifiConnected()) return false
        return true
    }

    private suspend fun canUseOnDevice(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): Boolean {
        if (!isOnDeviceImplemented(capability)) return false
        if (!aiPolicy.shouldAllowOnDevice(settings, capability)) return false
        return onDeviceStatus == OnDeviceModelStatus.AVAILABLE
    }

    private suspend fun onDeviceUnavailableReason(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): String {
        if (!isOnDeviceImplemented(capability)) {
            return "On-device AI is not implemented yet for this capability."
        }

        if (!aiPolicy.shouldAllowOnDevice(settings, capability)) {
            return "On-device AI is disabled by settings or policy for this capability."
        }

        return when (onDeviceStatus ?: OnDeviceModelStatus.UNKNOWN) {
            OnDeviceModelStatus.AVAILABLE -> "On-device model is available."
            OnDeviceModelStatus.NOT_INSTALLED -> "On-device model is not installed on this device."
            OnDeviceModelStatus.DOWNLOADING -> "On-device model is still downloading."
            OnDeviceModelStatus.UNAVAILABLE -> "On-device model is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor."
            OnDeviceModelStatus.UNSUPPORTED_DEVICE -> "This device does not support the on-device model."
            OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION -> "This Android version does not support the on-device model."
            OnDeviceModelStatus.DISABLED_BY_POLICY -> "On-device model is disabled by policy."
            OnDeviceModelStatus.UNKNOWN -> "On-device model availability is unknown."
        }
    }

    private suspend fun combinedUnavailableReason(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): String {
        val cloudUnavailable = !canUseCloud(capability, settings)
        val onDeviceUnavailable = !canUseOnDevice(capability, settings, onDeviceStatus)
        val cloudReason = if (cloudUnavailable) cloudUnavailableReason(capability, settings) else null
        val onDeviceReason = if (onDeviceUnavailable) {
            onDeviceUnavailableReason(capability, settings, onDeviceStatus)
        } else {
            null
        }

        return when {
            cloudReason != null && onDeviceReason != null -> "$cloudReason $onDeviceReason"
            onDeviceReason != null -> onDeviceReason
            cloudReason != null -> cloudReason
            else -> "Cloud routing is unavailable for the current settings or connectivity."
        }
    }

    private fun cloudUnavailableReason(
        capability: AiCapability,
        settings: AiSettings
    ): String {
        return when {
            !settings.aiEnabled -> "AI is disabled in settings."
            !isCapabilityEnabled(capability, settings) -> "${capability.displayName()} is disabled in settings."
            !settings.allowCloudAi -> "Cloud AI is disabled in settings."
            !aiPolicy.canUseCloudFor(settings, capability) -> "Cloud AI is disabled by policy for this capability."
            !environmentMonitor.isNetworkAvailable() -> "Cloud AI needs an internet connection."
            settings.wifiOnlyForCloud && !environmentMonitor.isWifiConnected() -> "Cloud AI is limited to Wi-Fi by settings."
            else -> "Cloud AI is unavailable right now."
        }
    }

    private suspend fun resolveOnDeviceStatus(
        capability: AiCapability,
        settings: AiSettings
    ): OnDeviceModelStatus? {
        if (!isOnDeviceImplemented(capability)) return null
        if (!aiPolicy.shouldAllowOnDevice(settings, capability)) return null
        return environmentMonitor.getOnDeviceModelStatus(capability)
    }

    private fun isCapabilityEnabled(capability: AiCapability, settings: AiSettings): Boolean {
        return when (capability) {
            AiCapability.DASHBOARD_BRIEFING -> settings.dashboardBriefingEnabled
            AiCapability.REVIEW_EXPLANATION -> settings.reviewExplanationEnabled
            AiCapability.QUERY_INTERPRETATION -> settings.queryInterpretationEnabled
            AiCapability.RECEIPT_EXTRACTION -> settings.receiptAssistEnabled
            AiCapability.WARRANTY_EXTRACTION -> settings.warrantyExtractionEnabled
            AiCapability.CATEGORIZATION_FALLBACK -> settings.categorizationFallbackEnabled
            AiCapability.DEDUPE_JUDGE -> settings.dedupeJudgeEnabled
            AiCapability.LOCATION_SUMMARY -> settings.aiEnabled
            AiCapability.NOTIFICATION_PARSE -> settings.aiEnabled // Uses general AI toggle
            AiCapability.REVIEW_PRIORITIZATION -> settings.aiEnabled // Uses general AI toggle
            AiCapability.SEMANTIC_DEDUPE -> settings.aiEnabled // Uses general AI toggle
            AiCapability.RECEIPT_ITEM_CATEGORIZATION -> settings.receiptItemCategorizationEnabled
        }
    }

    private fun isOnDeviceImplemented(capability: AiCapability): Boolean {
        return capability in ON_DEVICE_IMPLEMENTED_CAPABILITIES
    }

    private fun AiCapability.displayName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> "Dashboard briefing"
        AiCapability.REVIEW_EXPLANATION -> "Review explanation"
        AiCapability.QUERY_INTERPRETATION -> "Query interpretation"
        AiCapability.RECEIPT_EXTRACTION -> "Receipt assist"
        AiCapability.WARRANTY_EXTRACTION -> "Warranty extraction"
        AiCapability.CATEGORIZATION_FALLBACK -> "Categorization fallback"
        AiCapability.DEDUPE_JUDGE -> "Duplicate detection"
        AiCapability.LOCATION_SUMMARY -> "Location summary"
        AiCapability.NOTIFICATION_PARSE -> "Notification parsing"
        AiCapability.REVIEW_PRIORITIZATION -> "Review prioritization"
        AiCapability.SEMANTIC_DEDUPE -> "Semantic duplicate detection"
        AiCapability.RECEIPT_ITEM_CATEGORIZATION -> "Receipt item categorization"
    }

    private fun AiCapability.defaultCloudProviderName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER
        AiCapability.REVIEW_EXPLANATION -> AppConfig.Ai.REVIEW_EXPLANATION_CLOUD_PROVIDER
        AiCapability.QUERY_INTERPRETATION -> AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_PROVIDER
        AiCapability.RECEIPT_EXTRACTION -> AppConfig.Ai.RECEIPT_ASSIST_CLOUD_PROVIDER
        AiCapability.WARRANTY_EXTRACTION -> AppConfig.Ai.RECEIPT_ASSIST_CLOUD_PROVIDER
        AiCapability.CATEGORIZATION_FALLBACK -> AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_PROVIDER
        AiCapability.DEDUPE_JUDGE -> AppConfig.Ai.DEDUPE_JUDGE_CLOUD_PROVIDER
        AiCapability.LOCATION_SUMMARY -> "google-ai-studio"
        AiCapability.NOTIFICATION_PARSE -> "unsupported" // On-device only for privacy
        AiCapability.REVIEW_PRIORITIZATION -> "unsupported" // On-device only for privacy
        AiCapability.SEMANTIC_DEDUPE -> "unsupported" // On-device only for privacy
        AiCapability.RECEIPT_ITEM_CATEGORIZATION -> AppConfig.Ai.RECEIPT_ITEM_CATEGORIZATION_CLOUD_PROVIDER
    }

    private fun AiCapability.defaultCloudModelName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL
        AiCapability.REVIEW_EXPLANATION -> AppConfig.Ai.REVIEW_EXPLANATION_CLOUD_MODEL
        AiCapability.QUERY_INTERPRETATION -> AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_MODEL
        AiCapability.RECEIPT_EXTRACTION -> AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL
        AiCapability.WARRANTY_EXTRACTION -> AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL
        AiCapability.CATEGORIZATION_FALLBACK -> AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL
        AiCapability.DEDUPE_JUDGE -> AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL
        AiCapability.LOCATION_SUMMARY -> "gemini-cloud-location"
        AiCapability.NOTIFICATION_PARSE -> "unsupported" // On-device only for privacy
        AiCapability.REVIEW_PRIORITIZATION -> "unsupported" // On-device only for privacy
        AiCapability.SEMANTIC_DEDUPE -> "unsupported" // On-device only for privacy
        AiCapability.RECEIPT_ITEM_CATEGORIZATION -> AppConfig.Ai.RECEIPT_ITEM_CATEGORIZATION_CLOUD_MODEL
    }

    private fun AiCapability.defaultOnDeviceModelName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> AppConfig.Ai.ON_DEVICE_BRIEFING_MODEL
        AiCapability.REVIEW_EXPLANATION -> AppConfig.Ai.ON_DEVICE_REVIEW_MODEL
        AiCapability.QUERY_INTERPRETATION -> AppConfig.Ai.ON_DEVICE_QUERY_MODEL
        AiCapability.RECEIPT_EXTRACTION -> AppConfig.Ai.ON_DEVICE_RECEIPT_MODEL
        AiCapability.WARRANTY_EXTRACTION -> AppConfig.Ai.ON_DEVICE_RECEIPT_MODEL
        AiCapability.CATEGORIZATION_FALLBACK -> AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MODEL
        AiCapability.DEDUPE_JUDGE -> AppConfig.Ai.ON_DEVICE_DEDUPE_MODEL
        AiCapability.LOCATION_SUMMARY -> "gemini-nano-location"
        AiCapability.NOTIFICATION_PARSE -> AppConfig.Ai.ON_DEVICE_NOTIFICATION_MODEL
        AiCapability.REVIEW_PRIORITIZATION -> "gemini-nano-priority" // On-device only
        AiCapability.SEMANTIC_DEDUPE -> "gemini-nano-semantic" // On-device only
        AiCapability.RECEIPT_ITEM_CATEGORIZATION -> AppConfig.Ai.ON_DEVICE_RECEIPT_ITEM_MODEL
    }

    private companion object {
        val CLOUD_FIRST_CAPABILITIES = setOf(
            AiCapability.DASHBOARD_BRIEFING,
            AiCapability.REVIEW_EXPLANATION,
            AiCapability.DEDUPE_JUDGE,
            AiCapability.WARRANTY_EXTRACTION
        )

        val ON_DEVICE_IMPLEMENTED_CAPABILITIES = setOf(
            AiCapability.DASHBOARD_BRIEFING,
            AiCapability.REVIEW_EXPLANATION,
            AiCapability.QUERY_INTERPRETATION,
            AiCapability.RECEIPT_EXTRACTION,
            AiCapability.CATEGORIZATION_FALLBACK,
            AiCapability.DEDUPE_JUDGE,
            AiCapability.NOTIFICATION_PARSE, // NEW: On-device only, no cloud for privacy
            AiCapability.REVIEW_PRIORITIZATION, // NEW: On-device only for privacy and latency
            AiCapability.SEMANTIC_DEDUPE, // NEW: On-device only for privacy
            AiCapability.RECEIPT_ITEM_CATEGORIZATION // NEW: Receipt item categorization
        )
    }
}
