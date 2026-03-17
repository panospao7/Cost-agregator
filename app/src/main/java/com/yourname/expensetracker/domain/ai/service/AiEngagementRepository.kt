package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import kotlinx.coroutines.flow.Flow

interface AiEngagementRepository {
    fun engagementState(): Flow<AiEngagementState>
    suspend fun getLastDeliveredDashboardBriefingKey(): String?
    suspend fun setLastDeliveredDashboardBriefingKey(targetKey: String)
    suspend fun getLastOpenedDashboardBriefingKey(): String?
    suspend fun setLastOpenedDashboardBriefingKey(targetKey: String)
}
