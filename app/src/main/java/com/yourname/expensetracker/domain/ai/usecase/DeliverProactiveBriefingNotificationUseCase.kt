package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.service.NotificationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DeliverProactiveBriefingNotificationUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val notificationService: NotificationService
) {

    suspend operator fun invoke(dateKey: String, startedAt: Long) {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.dashboardBriefingEnabled || !settings.proactiveBriefingsEnabled) {
            return
        }

        val targetKey = "dashboard_home:$dateKey"
        val artifact = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING) ?: return
        if (artifact.status != AiArtifactStatus.READY) return
        if (artifact.updatedAt < startedAt) return

        val summary = artifact.summaryText?.trim()?.takeIf { it.isNotBlank() } ?: return
        notificationService.sendAiBriefingReady(
            notificationId = targetKey.hashCode(),
            title = "Your AI briefing is ready",
            message = summary.take(180)
        )
    }
}
