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
import com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        every { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) } returns DeliveryResult.DELIVERED
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

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.DELIVERED, outcome)
        verify {
            notificationService.sendAiBriefingReadyWithResult(
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
        every {
            notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any())
        } returns DeliveryResult.NOT_DELIVERED

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.NOT_DELIVERED, outcome)
        verify(exactly = 1) {
            notificationService.sendAiBriefingReadyWithResult(
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

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT, outcome)
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
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

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_SETTINGS_OFF, outcome)
        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
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

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_DUPLICATE, outcome)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
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

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_DUPLICATE, outcome)

        coVerify(exactly = 0) { aiArtifactRepository.getLatest(any(), any()) }
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke returns settings off when ai or dashboard briefing is disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = false, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = true)
        )
        assertEquals(
            BriefingDeliveryOutcome.SKIPPED_SETTINGS_OFF,
            useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)
        )

        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = false, proactiveBriefingsEnabled = true)
        )
        assertEquals(
            BriefingDeliveryOutcome.SKIPPED_SETTINGS_OFF,
            useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)
        )
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke returns no fresh artifact when missing, not ready, or summary blank`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = true)
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null

        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns null
        assertEquals(
            BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT,
            useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)
        )

        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L).copy(status = AiArtifactStatus.FAILED)
        assertEquals(
            BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT,
            useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)
        )

        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L).copy(summaryText = "   ")
        assertEquals(
            BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT,
            useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = true)
        )
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    // ai-fixes B3a / INV-24, INV-27: permission is checked only after settings, dedupe and artifact checks.
    @Test
    fun `permission denied with proactive briefings off returns settings off`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = false)
        )

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = false)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_SETTINGS_OFF, outcome)
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    @Test
    fun `permission denied with duplicate briefing returns duplicate`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = true)
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns "dashboard_home:2026-03-17"
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = false)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_DUPLICATE, outcome)
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    @Test
    fun `permission denied with no fresh artifact returns no fresh artifact`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = true)
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 900L)

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = false)

        assertEquals(BriefingDeliveryOutcome.SKIPPED_NO_FRESH_ARTIFACT, outcome)
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
    }

    @Test
    fun `permission denied with fresh artifact returns permission denied and posts nothing`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dashboardBriefingEnabled = true, proactiveBriefingsEnabled = true)
        )
        coEvery { aiEngagementRepository.getLastDeliveredDashboardBriefingKey() } returns null
        coEvery { aiEngagementRepository.getLastOpenedDashboardBriefingKey() } returns null
        coEvery {
            aiArtifactRepository.getLatest("dashboard_home:2026-03-17", AiCapability.DASHBOARD_BRIEFING)
        } returns briefingArtifact(updatedAt = 1_100L)

        val outcome = useCase(dateKey = "2026-03-17", startedAt = 1_000L, notificationId = 40123, notificationsAllowed = false)

        assertEquals(BriefingDeliveryOutcome.NOTIFICATION_PERMISSION_DENIED, outcome)
        verify(exactly = 0) { notificationService.sendAiBriefingReadyWithResult(any(), any(), any(), any()) }
        coVerify(exactly = 0) { aiEngagementRepository.setLastDeliveredDashboardBriefingKey(any()) }
        assertTrue(aiRuntimeDiagnostics.getRecentEvents().isEmpty())
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
