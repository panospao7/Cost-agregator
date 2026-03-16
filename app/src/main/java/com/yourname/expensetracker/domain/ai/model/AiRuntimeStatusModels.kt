package com.yourname.expensetracker.domain.ai.model

data class AiCapabilityRuntimeStatus(
    val capability: AiCapability,
    val status: OnDeviceModelStatus,
    val message: String?,
    val actionLabel: String?,
    val route: AiRoute? = null,
    val routeReason: String? = null,
    val providerName: String? = null,
    val modelName: String? = null,
    val onDeviceMessage: String? = null
)

data class AiRuntimeStatusSummary(
    val capabilities: List<AiCapabilityRuntimeStatus>,
    val highestPriorityMessage: String?,
    val networkAvailable: Boolean = false,
    val wifiConnected: Boolean = false,
    val lastRefreshedAt: Long = 0L
)

fun AiCapabilityRuntimeStatus.routeDisplayText(): String? {
    val routeLabel = when (route) {
        AiRoute.ON_DEVICE -> "On-device"
        AiRoute.CLOUD -> "Cloud"
        AiRoute.DETERMINISTIC_FALLBACK -> "Deterministic fallback"
        AiRoute.DISABLED -> "Disabled"
        null -> return null
    }

    val parts = buildList {
        add(routeLabel)
        providerName?.takeIf { it.isNotBlank() }?.let(::add)
        modelName?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.joinToString(" - ")
}
