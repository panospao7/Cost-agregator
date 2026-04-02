package com.yourname.expensetracker.domain.ai.usecase

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.service.NotificationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DeliverProactiveBriefingNotificationUseCase @Inject constructor(
    private val context: Context,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiEngagementRepository: AiEngagementRepository,
    private val notificationService: NotificationService,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics
) {

    suspend operator fun invoke(dateKey: String, startedAt: Long) {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.dashboardBriefingEnabled || !settings.proactiveBriefingsEnabled) {
            return
        }

        val targetKey = "dashboard_home:$dateKey"
        val lastDeliveredKey = aiEngagementRepository.getLastDeliveredDashboardBriefingKey()
        val lastOpenedKey = aiEngagementRepository.getLastOpenedDashboardBriefingKey()
        if (targetKey == lastDeliveredKey || targetKey == lastOpenedKey) {
            return
        }

        val artifact = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING) ?: return
        if (artifact.status != AiArtifactStatus.READY) return
        if (artifact.updatedAt < startedAt) return

        val summary = artifact.summaryText?.trim()?.takeIf { it.isNotBlank() } ?: return
        notificationService.sendAiBriefingReady(
            notificationId = targetKey.hashCode(),
            title = context.getString(R.string.notification_briefing_ready_title),
            message = summary.take(180),
            targetKey = targetKey
        )
        aiEngagementRepository.setLastDeliveredDashboardBriefingKey(targetKey)
        val providerLabel = artifact.provider ?: "unknown"
        val modelLabel = artifact.modelName ?: "unknown"
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_delivery",
            message = "dashboard_briefing delivered via notification ($providerLabel/$modelLabel)"
        )
    }
}
