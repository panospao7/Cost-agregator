package com.yourname.expensetracker.domain.ai.service

interface AiEngagementRepository {
    suspend fun getLastDeliveredDashboardBriefingKey(): String?
    suspend fun setLastDeliveredDashboardBriefingKey(targetKey: String)
    suspend fun getLastOpenedDashboardBriefingKey(): String?
    suspend fun setLastOpenedDashboardBriefingKey(targetKey: String)
}
