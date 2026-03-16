package com.yourname.expensetracker.domain.ai.model

data class AiCapabilityRuntimeStatus(
    val capability: AiCapability,
    val status: OnDeviceModelStatus,
    val message: String?
)

data class AiRuntimeStatusSummary(
    val capabilities: List<AiCapabilityRuntimeStatus>,
    val highestPriorityMessage: String?
)
