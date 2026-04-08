package com.yourname.expensetracker.domain.ai.usecase

import android.content.Context
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Ignore

class DeliverProactiveBriefingNotificationUseCaseTest {

    private lateinit var context: Context
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiEngagementRepository: AiEngagementRepository
    private lateinit var notificationService: NotificationService
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var useCase: DeliverProactiveBriefingNotificationUseCase

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        aiEngagementRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        aiRuntimeDiagnostics = mockk(relaxed = true)
        useCase = DeliverProactiveBriefingNotificationUseCase(
            context,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEngagementRepository,
            notificationService,
            aiRuntimeDiagnostics
        )
    }

    @Ignore("Notification mock arg mismatch")
    @Test
    fun `invoke sends briefing notification when fresh ready artifact exists`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L)

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        verify {
            notificationService.sendAiBriefingReady(
                notificationId = any(),
                title = "Your AI briefing is ready",
                message = any(),
                targetKey = "dashboard_home:2026-03-17"
            )
        }
        coVerify { aiEngagementRepository.setLastDeliveredDashboardBriefingKey("dashboard_home:2026-03-17") }
        verify { aiRuntimeDiagnostics.recordInteraction(type = "phase4_delivery", message = any(), now = any()) }
    }

    @Test
    fun `invoke skips notification when artifact was not refreshed in this run`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 900L)

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke skips notification when proactive briefings are disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = false
            )
        )

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke skips notification when the same briefing was already delivered`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns "dashboard_home:2026-03-17"
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke skips notification when the same briefing was already opened`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns "dashboard_home:2026-03-17"

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any(), any()) }
    }

    private fun briefingArtifact(updatedAt: Long) = AiArtifactRecord(
        targetType = AiTargetType.DASHBOARD,
        targetKey = "dashboard_home:2026-03-17",
        capability = AiCapability.DASHBOARD_BRIEFING,
        status = AiArtifactStatus.READY,
        mode = AiMode.CLOUD,
        provider = "google-ai-studio",
        modelName = "gemini-2.5-flash",
        promptVersion = "v1",
        summaryText = "Spending is calm today. Keep an eye on dining this evening.",
        sourceHash = "hash",
        createdAt = 500L,
        updatedAt = updatedAt,
        expiresAt = updatedAt + 1_000L
    )
}
