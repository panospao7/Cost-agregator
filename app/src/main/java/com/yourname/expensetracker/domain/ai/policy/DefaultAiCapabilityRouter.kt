package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
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
            reason = "Preferred on-device mode is unavailable, so using deterministic fallback."
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
                    reason = "AUTO mode found neither cloud nor on-device available."
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
                    reason = "AUTO mode found neither on-device nor cloud available."
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
        return aiPolicy.shouldAllowOnDevice(settings, capability) &&
            environmentMonitor.isOnDeviceModelAvailable(capability)
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
        AiCapability.DEDUPE_JUDGE -> "gemini-cloud-dedupe"
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
