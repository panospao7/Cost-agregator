package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class GenerateDashboardBriefingUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var dashboardBriefingService: DashboardBriefingService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var inputBuilder: DashboardBriefingInputBuilder
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var useCase: GenerateDashboardBriefingUseCase

    // Processed dashboard data can be mocked because inputBuilder is also mocked
    private lateinit var processedData: ProcessedDashboardData

    private val now = 1_700_000_000_000L
    private val dateKey = "2023-11-14"

    @Before
    fun setup() {
        aiSettingsRepository   = mockk()
        aiArtifactRepository   = mockk(relaxed = true)
        dashboardBriefingService = mockk()
        aiCapabilityRouter     = mockk()
        inputBuilder           = mockk()
        timeProvider           = FakeTimeProvider(fixedTime = now)
        processedData          = mockk(relaxed = true)

        useCase = GenerateDashboardBriefingUseCase(
            aiSettingsRepository   = aiSettingsRepository,
            aiArtifactRepository   = aiArtifactRepository,
            dashboardBriefingService = dashboardBriefingService,
            aiCapabilityRouter     = aiCapabilityRouter,
            inputBuilder           = inputBuilder,
            timeProvider           = timeProvider
        )

        coEvery { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) } returns cloudRouteDecision()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun enabledSettings(
        aiEnabled: Boolean = true,
        dashboardBriefingEnabled: Boolean = true
    ) = AiSettings(
        aiEnabled                = aiEnabled,
        dashboardBriefingEnabled = dashboardBriefingEnabled
    )

    private fun fakeInput() = DashboardBriefingInput(
        dateKey              = dateKey,
        weatherHeadline      = "Sunny",
        weatherSummary       = "All good",
        discretionaryBudget  = 200.0,
        totalCommitted       = 100.0,
        totalLikely          = 150.0,
        pendingReviewCount   = 2,
        currentMonthSpent    = 300.0,
        topCategories        = listOf("Food", "Transport"),
        budgetWarnings       = emptyList(),
        upcomingItems        = emptyList()
    )

    private fun freshReadyArtifact() = AiArtifactRecord(
        id            = 5L,
        targetType    = AiTargetType.DASHBOARD,
        targetKey     = "dashboard_home:$dateKey",
        capability    = AiCapability.DASHBOARD_BRIEFING,
        status        = AiArtifactStatus.READY,
        mode          = AiMode.CLOUD,
        provider      = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER,
        modelName     = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL,
        promptVersion = AppConfig.Ai.PROMPT_VERSION_DASHBOARD,
        sourceHash    = fakeInput().hashCode().toString(),
        createdAt     = now,
        updatedAt     = now,
        expiresAt     = now + AppConfig.Ai.DASHBOARD_BRIEFING_TTL_MS
    )

    private fun cloudRouteDecision() = AiRouteDecision(
        route = AiRoute.CLOUD,
        reason = "Preferred mode is cloud and connectivity/policy allow it.",
        providerName = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER,
        modelName = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL
    )

    // ── disabled gate ─────────────────────────────────────────────────────────

    @Test
    fun `invoke returns immediately when aiEnabled is false`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings(aiEnabled = false)
        )

        useCase(processedData)

        coVerify(exactly = 0) { inputBuilder.build(any()) }
        coVerify(exactly = 0) { dashboardBriefingService.generate(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    @Test
    fun `invoke returns immediately when dashboardBriefingEnabled is false`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings(dashboardBriefingEnabled = false)
        )

        useCase(processedData)

        coVerify(exactly = 0) { inputBuilder.build(any()) }
        coVerify(exactly = 0) { dashboardBriefingService.generate(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    // ── cache hit ─────────────────────────────────────────────────────────────

    @Test
    fun `invoke skips generation when fresh READY artifact already exists`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery {
            aiArtifactRepository.getLatest(
                "dashboard_home:$dateKey",
                AiCapability.DASHBOARD_BRIEFING
            )
        } returns freshReadyArtifact()

        useCase(processedData)

        coVerify(exactly = 0) { dashboardBriefingService.generate(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    @Test
    fun `invoke regenerates when ready artifact source hash is stale`() = runTest {
        val briefing = DashboardBriefing(title = "Today's Briefing", text = "Fresh text", tone = "neutral")
        val staleInput = fakeInput().copy(weatherHeadline = "Stormy")
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns staleInput
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns freshReadyArtifact()
        coEvery { dashboardBriefingService.generate(any()) } returns AiServiceResult.Success(briefing)
        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        coVerify(exactly = 1) { dashboardBriefingService.generate(any()) }
        assertEquals(AiArtifactStatus.READY, captured.last().status)
    }

    // ── provider returns null ─────────────────────────────────────────────────

    @Test
    fun `invoke stores FAILED artifact when provider returns failure`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } returns
            AiServiceResult.Failure(AiServiceError.Unknown("provider unavailable"))

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        val finalArtifact = captured.last()
        assertEquals(AiArtifactStatus.FAILED, finalArtifact.status)
        assertTrue(finalArtifact.errorMessage?.contains("provider: ${AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER}") == true)
    }

    // ── provider succeeds ─────────────────────────────────────────────────────

    @Test
    fun `invoke stores READY artifact with briefing text when provider succeeds`() = runTest {
        val briefing = DashboardBriefing(
            title      = "Today's Briefing",
            text       = "You've spent €300 this month across 2 categories.",
            tone       = "neutral"
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } returns AiServiceResult.Success(briefing)

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        val running = captured[0]
        val ready   = captured[1]
        assertEquals(AiArtifactStatus.RUNNING, running.status)
        assertEquals(AiArtifactStatus.READY,   ready.status)
        assertEquals(AiMode.CLOUD, running.mode)
        assertEquals(AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER, running.provider)
        assertEquals(AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL, running.modelName)
        assertTrue(ready.summaryText?.isNotEmpty() == true)
        assertEquals("dashboard_home:$dateKey", ready.targetKey)
        assertEquals(AiCapability.DASHBOARD_BRIEFING, ready.capability)
    }

    @Test
    fun `invoke truncates briefing text to MAX_BRIEFING_LENGTH_CHARS`() = runTest {
        val longText = "A".repeat(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS + 100)
        val briefing = DashboardBriefing(title = "T", text = longText, tone = "neutral")
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } returns AiServiceResult.Success(briefing)

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        val ready = captured.last()
        assertEquals(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS, ready.summaryText!!.length)
    }

    // ── provider throws ───────────────────────────────────────────────────────

    @Test
    fun `invoke stores FAILED artifact when provider throws`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } throws RuntimeException("Timeout")

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        val failedArtifact = captured.last()
        assertEquals(AiArtifactStatus.FAILED, failedArtifact.status)
        assertTrue(failedArtifact.errorMessage?.contains("Timeout") == true)
    }

    // ── cancellation propagation ──────────────────────────────────────────────

    @Test
    fun `invoke propagates CancellationException without writing FAILED artifact`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } throws CancellationException("cancelled")

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        try {
            useCase(processedData)
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        // Only the RUNNING tombstone should have been written, no FAILED artifact
        assertTrue(captured.size == 1)
        assertEquals(AiArtifactStatus.RUNNING, captured.first().status)
    }

    // ── expiresAt ─────────────────────────────────────────────────────────────

    @Test
    fun `invoke sets expiresAt to now plus dashboard TTL`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any()) } returns fakeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dashboardBriefingService.generate(any()) } returns
            AiServiceResult.Success(DashboardBriefing(title = "T", text = "B", tone = "neutral"))
        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(processedData)

        val running = captured[0]
        assertEquals(now + AppConfig.Ai.DASHBOARD_BRIEFING_TTL_MS, running.expiresAt)
    }
}
