package com.yourname.expensetracker.domain.ai.model

data class AiCapabilityRuntimeStatus(
    val capability: AiCapability,
    val status: OnDeviceModelStatus,
    val message: String?,
    val actionLabel: String?
)

data class AiRuntimeStatusSummary(
    val capabilities: List<AiCapabilityRuntimeStatus>,
    val highestPriorityMessage: String?,
    val networkAvailable: Boolean = false,
    val wifiConnected: Boolean = false,
    val lastRefreshedAt: Long = 0L
)
