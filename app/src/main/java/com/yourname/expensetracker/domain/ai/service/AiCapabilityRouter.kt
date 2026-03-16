package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings

interface AiCapabilityRouter {
    suspend fun decide(capability: AiCapability, settings: AiSettings): AiRouteDecision
}
