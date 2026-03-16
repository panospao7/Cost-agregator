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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAiCapabilityRouter @Inject constructor(
    private val aiPolicy: AiPolicy,
    private val environmentMonitor: AiEnvironmentMonitor
) : AiCapabilityRouter {

    override fun decide(capability: AiCapability, settings: AiSettings): AiRouteDecision {
        if (!settings.aiEnabled) {
            return AiRouteDecision(AiRoute.DISABLED, "AI is disabled in settings.")
        }

        if (!isCapabilityEnabled(capability, settings)) {
            return AiRouteDecision(AiRoute.DISABLED, "$capability is disabled in settings.")
        }

        return when (settings.preferredMode) {
            AiMode.ON_DEVICE -> chooseOnDevicePreferred(capability, settings)
            AiMode.CLOUD -> chooseCloudPreferred(capability, settings)
            AiMode.AUTO -> chooseAuto(capability, settings)
        }
    }

    private fun chooseOnDevicePreferred(
        capability: AiCapability,
        settings: AiSettings
    ): AiRouteDecision {
        if (canUseOnDevice(capability, settings)) {
            return AiRouteDecision(
                route = AiRoute.ON_DEVICE,
                reason = "Preferred mode is on-device and the local model is available.",
                providerName = "on-device",
                modelName = capability.defaultOnDeviceModelName()
            )
        }

        return AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = onDeviceUnavailableReason(capability, settings)
        )
    }

    private fun chooseCloudPreferred(
        capability: AiCapability,
        settings: AiSettings
    ): AiRouteDecision {
        if (canUseCloud(capability, settings)) {
            return AiRouteDecision(
                route = AiRoute.CLOUD,
                reason = "Preferred mode is cloud and connectivity/policy allow it.",
                providerName = capability.defaultCloudProviderName(),
                modelName = capability.defaultCloudModelName()
            )
        }

        if (isLowRiskOnDeviceFallback(capability) && canUseOnDevice(capability, settings)) {
            return AiRouteDecision(
                route = AiRoute.ON_DEVICE,
                reason = "Cloud was preferred but unavailable, so using on-device fallback.",
                providerName = "on-device",
                modelName = capability.defaultOnDeviceModelName()
            )
        }

        return AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = "Cloud was preferred but unavailable, so using deterministic fallback."
        )
    }

    private fun chooseAuto(
        capability: AiCapability,
        settings: AiSettings
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
            } else if (canUseOnDevice(capability, settings)) {
                AiRouteDecision(
                    route = AiRoute.ON_DEVICE,
                    reason = "AUTO mode fell back from cloud to on-device.",
                    providerName = "on-device",
                    modelName = capability.defaultOnDeviceModelName()
                )
            } else {
                AiRouteDecision(
                    route = AiRoute.DETERMINISTIC_FALLBACK,
                    reason = combinedUnavailableReason(capability, settings)
                )
            }
        } else {
            if (canUseOnDevice(capability, settings)) {
                AiRouteDecision(
                    route = AiRoute.ON_DEVICE,
                    reason = "AUTO mode selected the capability's on-device-first route.",
                    providerName = "on-device",
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
                    reason = combinedUnavailableReason(capability, settings)
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

    private fun canUseOnDevice(capability: AiCapability, settings: AiSettings): Boolean {
        if (!aiPolicy.shouldAllowOnDevice(settings, capability)) return false
        return environmentMonitor.getOnDeviceModelStatus(capability) == OnDeviceModelStatus.AVAILABLE
    }

    private fun onDeviceUnavailableReason(capability: AiCapability, settings: AiSettings): String {
        if (!aiPolicy.shouldAllowOnDevice(settings, capability)) {
            return "On-device AI is disabled by settings or policy for this capability."
        }

        return when (environmentMonitor.getOnDeviceModelStatus(capability)) {
            OnDeviceModelStatus.AVAILABLE -> "On-device model is available."
            OnDeviceModelStatus.NOT_INSTALLED -> "On-device model is not installed on this device."
            OnDeviceModelStatus.DOWNLOADING -> "On-device model is still downloading."
            OnDeviceModelStatus.UNAVAILABLE -> "On-device model is currently unavailable on this device."
            OnDeviceModelStatus.UNSUPPORTED_DEVICE -> "This device does not support the on-device model."
            OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION -> "This Android version does not support the on-device model."
            OnDeviceModelStatus.DISABLED_BY_POLICY -> "On-device model is disabled by policy."
            OnDeviceModelStatus.UNKNOWN -> "On-device model availability is unknown."
        }
    }

    private fun combinedUnavailableReason(capability: AiCapability, settings: AiSettings): String {
        val cloudUnavailable = !canUseCloud(capability, settings)
        val onDeviceUnavailable = !canUseOnDevice(capability, settings)

        return when {
            cloudUnavailable && onDeviceUnavailable ->
                "Neither cloud nor on-device AI is currently available. ${onDeviceUnavailableReason(capability, settings)}"
            onDeviceUnavailable -> onDeviceUnavailableReason(capability, settings)
            else -> "Cloud routing is unavailable for the current settings or connectivity."
        }
    }

    private fun isCapabilityEnabled(capability: AiCapability, settings: AiSettings): Boolean {
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

    private fun isLowRiskOnDeviceFallback(capability: AiCapability): Boolean {
        return capability in setOf(
            AiCapability.REVIEW_EXPLANATION,
            AiCapability.CATEGORIZATION_FALLBACK
        )
    }

    private fun AiCapability.defaultCloudProviderName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING,
        AiCapability.REVIEW_EXPLANATION,
        AiCapability.QUERY_INTERPRETATION,
        AiCapability.RECEIPT_EXTRACTION,
        AiCapability.CATEGORIZATION_FALLBACK,
        AiCapability.DEDUPE_JUDGE,
        AiCapability.LOCATION_SUMMARY -> "google-ai-studio"
    }

    private fun AiCapability.defaultCloudModelName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> "gemini-cloud-briefing"
        AiCapability.REVIEW_EXPLANATION -> AppConfig.Ai.REVIEW_EXPLANATION_CLOUD_MODEL
        AiCapability.QUERY_INTERPRETATION -> "gemini-cloud-query"
        AiCapability.RECEIPT_EXTRACTION -> AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL
        AiCapability.CATEGORIZATION_FALLBACK -> AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL
        AiCapability.DEDUPE_JUDGE -> AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL
        AiCapability.LOCATION_SUMMARY -> "gemini-cloud-location"
    }

    private fun AiCapability.defaultOnDeviceModelName(): String = when (this) {
        AiCapability.DASHBOARD_BRIEFING -> "gemini-nano-briefing"
        AiCapability.REVIEW_EXPLANATION -> "gemini-nano-review"
        AiCapability.QUERY_INTERPRETATION -> "gemini-nano-query"
        AiCapability.RECEIPT_EXTRACTION -> "gemini-nano-receipt"
        AiCapability.CATEGORIZATION_FALLBACK -> "gemini-nano-category"
        AiCapability.DEDUPE_JUDGE -> "gemini-nano-dedupe"
        AiCapability.LOCATION_SUMMARY -> "gemini-nano-location"
    }

    private companion object {
        val CLOUD_FIRST_CAPABILITIES = setOf(
            AiCapability.DASHBOARD_BRIEFING,
            AiCapability.REVIEW_EXPLANATION,
            AiCapability.DEDUPE_JUDGE
        )
    }
}
