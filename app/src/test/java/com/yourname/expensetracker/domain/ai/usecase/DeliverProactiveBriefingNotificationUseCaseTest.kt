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
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        // Use the concrete diagnostics object. A class mock cannot safely
        // evaluate the concrete method's default time-provider argument.
        aiRuntimeDiagnostics = AiRuntimeDiagnostics(FakeTimeProvider(1_234L))
        coEvery {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        } returns DeliveryResult.DELIVERED
        useCase = DeliverProactiveBriefingNotificationUseCase(
            context,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEngagementRepository,
            notificationService,
            aiRuntimeDiagnostics
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
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L)

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify {
            notificationService.sendAiBriefingReadyForKind(
                kind = NotificationKind.BRIEFING,
                notificationId = 40123,
                title = "Your AI briefing is ready",
                message = any(),
                targetKey = "dashboard_home:2026-03-17"
            )
        }
        coVerify { aiEngagementRepository.setLastDeliveredDashboardBriefingKey("dashboard_home:2026-03-17") }
        val event = aiRuntimeDiagnostics.getRecentEvents().single()
        assertEquals("phase4_delivery", event.type)
        assertEquals(1_234L, event.timestamp)
    }

    @Test
    fun `invoke does not record delivery when notification service does not deliver`() = runTest {
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
        coEvery {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        } returns DeliveryResult.NOT_DELIVERED

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify(exactly = 1) {
            notificationService.sendAiBriefingReadyForKind(
                kind = NotificationKind.BRIEFING,
                notificationId = 40123,
                title = "Your AI briefing is ready",
                message = any(),
                targetKey = "dashboard_home:2026-03-17"
            )
        }
        coVerify(exactly = 0) {
            aiEngagementRepository.setLastDeliveredDashboardBriefingKey(any())
        }
        assertTrue(aiRuntimeDiagnostics.getRecentEvents().isEmpty())
    }

    // S6 / INV-12 / AE-11: PERMISSION_DENIED is never a delivery. Nothing is recorded, so the same
    // briefing stays eligible for the next run, exactly like NOT_DELIVERED.
    @Test
    fun `INV-12 PERMISSION_DENIED records no delivery key and no diagnostics`() = runTest {
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
        coEvery {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        } returns DeliveryResult.PERMISSION_DENIED

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        // The service is asked exactly once with the BRIEFING kind, the unchanged id and targetKey.
        coVerify(exactly = 1) {
            notificationService.sendAiBriefingReadyForKind(
                kind = NotificationKind.BRIEFING,
                notificationId = 40123,
                title = "Your AI briefing is ready",
                message = any(),
                targetKey = "dashboard_home:2026-03-17"
            )
        }
        coVerify(exactly = 0) {
            aiEngagementRepository.setLastDeliveredDashboardBriefingKey(any())
        }
        assertTrue(aiRuntimeDiagnostics.getRecentEvents().isEmpty())
    }

    @Test
    fun `INV-12 AE-11 PERMISSION_DENIED and NOT_DELIVERED have identical recorded effects while DELIVERED differs`() = runTest {
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

        // Per result: (delivered-key writes, recorded diagnostic events).
        val recordedByResult = mutableMapOf<DeliveryResult, Pair<Int, Int>>()
        val results = listOf(DeliveryResult.NOT_DELIVERED, DeliveryResult.PERMISSION_DENIED, DeliveryResult.DELIVERED)
        for ((index, result) in results.withIndex()) {
            val engagement = mockk<AiEngagementRepository>(relaxed = true)
            val writtenKeys = mutableListOf<String>()
            coEvery { engagement.getLastDeliveredDashboardBriefingKey() } returns null
            coEvery { engagement.getLastOpenedDashboardBriefingKey() } returns null
            coEvery { engagement.setLastDeliveredDashboardBriefingKey(capture(writtenKeys)) } returns Unit
            val diagnostics = AiRuntimeDiagnostics(FakeTimeProvider(1_234L))
            coEvery {
                notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
            } returns result
            val perResultUseCase = DeliverProactiveBriefingNotificationUseCase(
                context,
                aiSettingsRepository,
                aiArtifactRepository,
                engagement,
                notificationService,
                diagnostics
            )

            perResultUseCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

            // The notification service mock is shared, so its call count accumulates one per iteration.
            coVerify(exactly = index + 1) {
                notificationService.sendAiBriefingReadyForKind(
                    NotificationKind.BRIEFING, 40123, "Your AI briefing is ready", any(), "dashboard_home:2026-03-17"
                )
            }
            if (result == DeliveryResult.DELIVERED) {
                assertEquals(listOf("dashboard_home:2026-03-17"), writtenKeys)
            }
            recordedByResult[result] = writtenKeys.size to diagnostics.getRecentEvents().size
        }
        assertEquals("row count guard", 3, recordedByResult.size)

        assertEquals(0 to 0, recordedByResult[DeliveryResult.NOT_DELIVERED])
        assertEquals(
            "PERMISSION_DENIED must record exactly what NOT_DELIVERED records",
            recordedByResult[DeliveryResult.NOT_DELIVERED],
            recordedByResult[DeliveryResult.PERMISSION_DENIED]
        )
        assertEquals(1 to 1, recordedByResult[DeliveryResult.DELIVERED])
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

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify(exactly = 0) {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        }
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

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        coVerify(exactly = 0) {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        }
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

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        coVerify(exactly = 0) {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        }
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

        useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        coVerify(exactly = 0) {
            notificationService.sendAiBriefingReadyForKind(any(), any(), any(), any(), any())
        }
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
