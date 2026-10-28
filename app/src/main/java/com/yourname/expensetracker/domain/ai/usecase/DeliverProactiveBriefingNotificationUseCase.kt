package com.yourname.expensetracker.domain.ai.usecase

import android.content.Context
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
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

    /**
     * [notificationsAllowed] is the caller's local notification-permission check result. It is
     * evaluated only AFTER the settings, dedupe and fresh-artifact checks pass, so a denied
     * permission never masks (or is recorded instead of) a skip that has another cause.
     */
    suspend operator fun invoke(
        dateKey: String,
        startedAt: Long,
        notificationId: Int,
        notificationsAllowed: Boolean
    ): BriefingDeliveryOutcome {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.dashboardBriefingEnabled || !settings.proactiveBriefingsEnabled) {
            return BriefingDeliveryOutcome.SKIPPED_SETTINGS_OFF
        }

        val targetKey = "dashboard_home:$dateKey"
        val lastDeliveredKey = aiEngagementRepository.getLastDeliveredDashboardBriefingKey()
        val lastOpenedKey = aiEngagementRepository.getLastOpenedDashboardBriefingKey()
        if (targetKey == lastDeliveredKey || targetKey == lastOpenedKey) {
            return BriefingDeliveryOutcome.SKIPPED_DUPLICATE
        }

        val artifact = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
            ?: return BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT
        if (artifact.status != AiArtifactStatus.READY) return BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT
        if (artifact.updatedAt < startedAt) return BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT

        val summary = artifact.summaryText?.trim()?.takeIf { it.isNotBlank() }
            ?: return BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT
        // INV-24/INV-27: permission gates posting only, and only a delivery that would
        // otherwise have been attempted is reported as permission-denied.
        if (!notificationsAllowed) return BriefingDeliveryOutcome.NOTIFICATION_PERMISSION_DENIED
        // S6 (INV-4, INV-12): declare the BRIEFING kind. Only DELIVERED records lastDelivered
        // (PERMISSION_DENIED, TECH-9, is never a delivery); targetKey and the id are unchanged.
        val deliveryResult = notificationService.sendAiBriefingReadyForKind(
            kind = NotificationKind.BRIEFING,
            notificationId = notificationId,
            title = BRIEFING_NOTIFICATION_TITLE,
            message = summary.take(180),
            targetKey = targetKey
        )
        // Exhaustive on purpose (no else branch): a new DeliveryResult value must be
        // mapped here explicitly (merge with the app-lock kind-aware results). PERMISSION_DENIED
        // (post refused by a SecurityException, TECH-9) maps to NOTIFICATION_PERMISSION_DENIED.
        when (deliveryResult) {
            NotificationService.DeliveryResult.DELIVERED -> Unit
            NotificationService.DeliveryResult.NOT_DELIVERED -> return BriefingDeliveryOutcome.NOT_DELIVERED
            NotificationService.DeliveryResult.PERMISSION_DENIED ->
                return BriefingDeliveryOutcome.NOTIFICATION_PERMISSION_DENIED
        }

        aiEngagementRepository.setLastDeliveredDashboardBriefingKey(targetKey)
        val providerLabel = artifact.provider ?: "unknown"
        val modelLabel = artifact.modelName ?: "unknown"
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_delivery",
            message = "dashboard_briefing delivered via notification ($providerLabel/$modelLabel)"
        )
        return BriefingDeliveryOutcome.DELIVERED
    }

    companion object {
        /** Domain-owned notification title — no Android R import needed in domain code. */
        const val BRIEFING_NOTIFICATION_TITLE = "Your AI briefing is ready"
    }
}
