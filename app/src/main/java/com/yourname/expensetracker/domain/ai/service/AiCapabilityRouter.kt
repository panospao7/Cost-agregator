package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus

interface AiCapabilityRouter {
    suspend fun decide(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus? = null
    ): AiRouteDecision
}
