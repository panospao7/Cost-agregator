package com.yourname.expensetracker.domain.ai.usecase

import android.content.Context
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.service.NotificationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

class DeliverProactiveBriefingNotificationUseCase @Inject constructor(
    // Context is retained for DI compatibility; no Android R resource lookups are performed here.
    @Suppress("UnusedPrivateMember")
    @ApplicationContext private val context: Context,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiEngagementRepository: AiEngagementRepository,
    private val notificationService: NotificationService,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics
) {

    suspend operator fun invoke(dateKey: String, startedAt: Long, notificationId: Int) {
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
        val deliveryResult = notificationService.sendAiBriefingReadyWithResult(
            notificationId = notificationId,
            title = BRIEFING_NOTIFICATION_TITLE,
            message = summary.take(180),
            targetKey = targetKey
        )
        if (deliveryResult != NotificationService.DeliveryResult.DELIVERED) {
            return
        }

        aiEngagementRepository.setLastDeliveredDashboardBriefingKey(targetKey)
        val providerLabel = artifact.provider ?: "unknown"
        val modelLabel = artifact.modelName ?: "unknown"
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_delivery",
            message = "dashboard_briefing delivered via notification ($providerLabel/$modelLabel)"
        )
    }

    companion object {
        /** Domain-owned notification title — no Android R import needed in domain code. */
        const val BRIEFING_NOTIFICATION_TITLE = "Your AI briefing is ready"
    }
}
