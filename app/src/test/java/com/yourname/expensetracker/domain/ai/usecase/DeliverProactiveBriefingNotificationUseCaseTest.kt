package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
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

class DeliverProactiveBriefingNotificationUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var notificationService: NotificationService
    private lateinit var useCase: DeliverProactiveBriefingNotificationUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        useCase = DeliverProactiveBriefingNotificationUseCase(
            aiSettingsRepository,
            aiArtifactRepository,
            notificationService
        )
    }

    @Test
    fun `invoke sends briefing notification when fresh ready artifact exists`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L)

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        verify {
            notificationService.sendAiBriefingReady(
                notificationId = any(),
                title = "Your AI briefing is ready",
                message = any()
            )
        }
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
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 900L)

        useCase(dateKey = "2026-03-17", startedAt = 1_000L)

        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any()) }
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
        verify(exactly = 0) { notificationService.sendAiBriefingReady(any(), any(), any()) }
    }

    private fun briefingArtifact(updatedAt: Long) = AiArtifactEntity(
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
