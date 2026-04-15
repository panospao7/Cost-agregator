package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeGenerationResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JudgePendingReviewDuplicateUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var dedupeJudgeService: DedupeJudgeService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var inputBuilder: DedupeJudgeInputBuilder
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var useCase: JudgePendingReviewDuplicateUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        aiArtifactRepository = mockk(relaxed = true)
        dedupeJudgeService = mockk()
        aiCapabilityRouter = mockk()
        inputBuilder = mockk()
        timeProvider = FakeTimeProvider(1_000L)

        useCase = JudgePendingReviewDuplicateUseCase(
            aiSettingsRepository,
            aiArtifactRepository,
            dedupeJudgeService,
            aiCapabilityRouter,
            inputBuilder,
            timeProvider
        )
        coEvery {
            aiCapabilityRouter.decide(AiCapability.DEDUPE_JUDGE, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "cloud allowed",
            providerName = AppConfig.Ai.DEDUPE_JUDGE_CLOUD_PROVIDER,
            modelName = AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL
        )
    }

    @Test
    fun `invoke returns NotNeeded when builder says not needed`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, dedupeJudgeEnabled = true))
        coEvery { inputBuilder.build(any(), any()) } returns DedupeJudgeBuildResult.NotNeeded("not needed")

        val result = useCase(makeItem())

        assertTrue(result is DedupeJudgeGenerationResult.NotNeeded)
    }

    @Test
    fun `invoke stores READY artifact on success`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, dedupeJudgeEnabled = true))
        coEvery { inputBuilder.build(any(), any()) } returns DedupeJudgeBuildResult.Ready(makeInput())
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dedupeJudgeService.judge(any()) } returns AiServiceResult.Success(
            DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.UNCERTAIN,
                rationale = "Two nearby matches look similar"
            )
        )
        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(makeItem())

        assertTrue(result is DedupeJudgeGenerationResult.Success)
        assertEquals(AiArtifactStatus.READY, captured.last().status)
        assertEquals(AiCapability.DEDUPE_JUDGE, captured.last().capability)
        assertEquals(AiMode.CLOUD, captured.first().mode)
        assertEquals(AppConfig.Ai.DEDUPE_JUDGE_CLOUD_PROVIDER, captured.first().provider)
        assertEquals(AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL, captured.first().modelName)
        assertTrue(captured.last().explanationText?.contains("Route: CLOUD") == true)
    }

    @Test
    fun `invoke stores ON_DEVICE metadata when local dedupe provider succeeds`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, dedupeJudgeEnabled = true))
        coEvery { inputBuilder.build(any(), any()) } returns DedupeJudgeBuildResult.Ready(makeInput())
        coEvery {
            aiCapabilityRouter.decide(AiCapability.DEDUPE_JUDGE, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
            modelName = AppConfig.Ai.ON_DEVICE_DEDUPE_MODEL
        )
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dedupeJudgeService.judge(any()) } returns AiServiceResult.Success(
            DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.UNCERTAIN,
                rationale = "Two nearby matches look similar"
            )
        )
        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(makeItem())

        assertTrue(result is DedupeJudgeGenerationResult.Success)
        assertEquals(AiMode.ON_DEVICE, captured.first().mode)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, captured.first().provider)
        assertEquals(AppConfig.Ai.ON_DEVICE_DEDUPE_MODEL, captured.first().modelName)
        assertTrue(captured.last().explanationText?.contains("Route: ON_DEVICE") == true)
    }

    @Test
    fun `invoke clears invalid matched target outside candidate set`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, dedupeJudgeEnabled = true))
        coEvery { inputBuilder.build(any(), any()) } returns DedupeJudgeBuildResult.Ready(makeInput())
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dedupeJudgeService.judge(any()) } returns AiServiceResult.Success(
            DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.LIKELY_DUPLICATE,
                matchedTargetType = AiTargetType.EXPENSE,
                matchedTargetId = 99L,
                rationale = "looks similar"
            )
        )
        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(makeItem())

        assertTrue(result is DedupeJudgeGenerationResult.Success)
        val suggestion = (result as DedupeJudgeGenerationResult.Success).suggestion
        assertEquals(DuplicateVerdict.UNCERTAIN, suggestion.verdict)
        assertEquals(null, suggestion.matchedTargetId)
        assertEquals(null, suggestion.matchedTargetType)
        assertTrue(captured.last().payloadJson?.contains("UNCERTAIN") == true)
    }

    @Test
    fun `invoke preserves matched target inside candidate set`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, dedupeJudgeEnabled = true))
        coEvery { inputBuilder.build(any(), any()) } returns DedupeJudgeBuildResult.Ready(makeInput())
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { dedupeJudgeService.judge(any()) } returns AiServiceResult.Success(
            DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.LIKELY_DUPLICATE,
                matchedTargetType = AiTargetType.EXPENSE,
                matchedTargetId = 3L,
                rationale = "exact match"
            )
        )
        coEvery { aiArtifactRepository.upsert(any()) } returns 1L

        val result = useCase(makeItem())

        assertTrue(result is DedupeJudgeGenerationResult.Success)
        val suggestion = (result as DedupeJudgeGenerationResult.Success).suggestion
        assertEquals(DuplicateVerdict.LIKELY_DUPLICATE, suggestion.verdict)
        assertEquals(3L, suggestion.matchedTargetId)
        assertEquals(AiTargetType.EXPENSE, suggestion.matchedTargetType)
    }

    private fun makeItem() = PendingReviewWithReceipt(
        PendingReview(
            id = 2L,
            rawNotificationId = null,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Lidl",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            suggestedDate = 1_000L,
            confidence = 0.8f,
            packageName = "pkg",
            notificationTitle = null,
            notificationText = null
        ),
        null
    )

    private fun makeInput() = DedupeJudgeInput(
        subject = DedupeCandidateSummary(AiTargetType.PENDING_REVIEW, 2L, "Lidl", 10.0, "EUR", 1_000L, "pkg"),
        candidates = listOf(
            DedupeCandidateSummary(AiTargetType.EXPENSE, 3L, "Lidl", 10.0, "EUR", 1_020L, "expense")
        )
    )
}
