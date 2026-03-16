package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import com.yourname.expensetracker.domain.config.AppConfig
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

class ExplainPendingReviewUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var reviewExplanationService: ReviewExplanationService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var inputBuilder: ReviewExplanationInputBuilder
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var useCase: ExplainPendingReviewUseCase

    private val now = 1_000_000L

    @Before
    fun setup() {
        aiSettingsRepository  = mockk()
        aiArtifactRepository  = mockk(relaxed = true)
        reviewExplanationService = mockk()
        aiCapabilityRouter    = mockk()
        inputBuilder          = mockk()
        timeProvider          = FakeTimeProvider(fixedTime = now)

        useCase = ExplainPendingReviewUseCase(
            aiSettingsRepository     = aiSettingsRepository,
            aiArtifactRepository     = aiArtifactRepository,
            reviewExplanationService = reviewExplanationService,
            aiCapabilityRouter       = aiCapabilityRouter,
            inputBuilder             = inputBuilder,
            timeProvider             = timeProvider
        )
        every {
            aiCapabilityRouter.decide(AiCapability.REVIEW_EXPLANATION, any())
        } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "cloud allowed",
            providerName = "google-ai-studio",
            modelName = "gemini-2.5-flash"
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makePendingReview(id: Long = 1L) = PendingReview(
        id                  = id,
        rawNotificationId   = null,
        suggestedAmount     = 10.0,
        suggestedCurrency   = "EUR",
        suggestedMerchant   = "TestMerchant",
        suggestedType       = "PURCHASE",
        suggestedCategoryId = null,
        confidence          = 0.9f,
        packageName         = "com.test.app",
        notificationTitle   = "Payment",
        notificationText    = "You paid €10",
        status              = PendingReviewStatus.PENDING
    )

    private fun makeInput(reviewId: Long = 1L) = ReviewExplanationInput(
        reviewId            = reviewId,
        merchant            = "TestMerchant",
        amount              = 10.0,
        currency            = "EUR",
        suggestedType       = "PURCHASE",
        suggestedCategoryId = null,
        confidence          = 0.9f,
        matchType           = null,
        explanation         = null,
        packageName         = "com.test.app",
        notificationTitle   = null,
        notificationText    = null
    )

    private fun enabledSettings(
        reviewExplanationEnabled: Boolean = true,
        aiEnabled: Boolean = true
    ) = AiSettings(
        aiEnabled                = aiEnabled,
        reviewExplanationEnabled = reviewExplanationEnabled
    )

    private fun freshReadyArtifact(reviewId: Long = 1L) = AiArtifactEntity(
        id            = 10L,
        targetType    = AiTargetType.PENDING_REVIEW,
        targetKey     = "pending_review:$reviewId",
        capability    = AiCapability.REVIEW_EXPLANATION,
        status        = AiArtifactStatus.READY,
        mode          = AiMode.AUTO,
        promptVersion = AppConfig.Ai.PROMPT_VERSION_REVIEW,
        sourceHash    = "existing_hash",
        createdAt     = now,
        updatedAt     = now,
        expiresAt     = now + AppConfig.Ai.REVIEW_EXPLANATION_TTL_MS
    )

    // ── disabled gate ─────────────────────────────────────────────────────────

    @Test
    fun `invoke returns immediately when aiEnabled is false`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings(aiEnabled = false)
        )

        useCase(makePendingReview())

        // inputBuilder and service must never be called
        coVerify(exactly = 0) { inputBuilder.build(any(), any()) }
        coVerify(exactly = 0) { reviewExplanationService.generate(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    @Test
    fun `invoke returns immediately when reviewExplanationEnabled is false`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings(reviewExplanationEnabled = false)
        )

        useCase(makePendingReview())

        coVerify(exactly = 0) { inputBuilder.build(any(), any()) }
        coVerify(exactly = 0) { reviewExplanationService.generate(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    // ── cache hit ─────────────────────────────────────────────────────────────

    @Test
    fun `invoke skips generation when fresh READY artifact already exists`() = runTest {
        val review = makePendingReview(id = 7L)
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput(reviewId = 7L)
        coEvery {
            aiArtifactRepository.getLatest("pending_review:7", AiCapability.REVIEW_EXPLANATION)
        } returns freshReadyArtifact(reviewId = 7L)

        useCase(review)

        // Service must NOT be called — cache hit
        coVerify(exactly = 0) { reviewExplanationService.generate(any()) }
        // No upsert after cache hit
        coVerify(exactly = 0) { aiArtifactRepository.upsert(any()) }
    }

    // ── provider returns null ─────────────────────────────────────────────────

    @Test
    fun `invoke stores FAILED artifact when provider returns null`() = runTest {
        val review = makePendingReview()
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput()
        coEvery {
            aiArtifactRepository.getLatest(any(), any())
        } returns null
        coEvery { reviewExplanationService.generate(any()) } returns null

        val slot = slot<AiArtifactEntity>()
        // Capture the last upsert call
        coEvery { aiArtifactRepository.upsert(capture(slot)) } returns 1L

        useCase(review)

        // Two upserts: RUNNING tombstone + final FAILED
        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        assertEquals(AiArtifactStatus.FAILED, slot.captured.status)
        assertEquals("cloud allowed", slot.captured.errorMessage)
    }

    // ── provider succeeds ─────────────────────────────────────────────────────

    @Test
    fun `invoke stores READY artifact with headline and body when provider succeeds`() = runTest {
        val review = makePendingReview()
        val explanation = ReviewExplanation(
            headline = "Likely food purchase",
            body     = "Based on merchant and amount, this looks like a café visit.",
            caution  = null
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { reviewExplanationService.generate(any()) } returns explanation

        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(review)

        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        val running = captured[0]
        val ready   = captured[1]
        assertEquals(AiArtifactStatus.RUNNING, running.status)
        assertEquals(AiArtifactStatus.READY,   ready.status)
        assertEquals("Likely food purchase", ready.summaryText)
        assertEquals("Based on merchant and amount, this looks like a café visit.", ready.explanationText)
        assertEquals("pending_review:1", ready.targetKey)
        assertEquals(AiCapability.REVIEW_EXPLANATION, ready.capability)
    }

    @Test
    fun `invoke sets correct targetKey for review`() = runTest {
        val review = makePendingReview(id = 42L)
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput(reviewId = 42L)
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { reviewExplanationService.generate(any()) } returns ReviewExplanation(
            headline = "H", body = "B"
        )
        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(review)

        assertTrue(captured.all { it.targetKey == "pending_review:42" })
    }

    @Test
    fun `invoke stores route metadata when provider succeeds`() = runTest {
        val review = makePendingReview()
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { reviewExplanationService.generate(any()) } returns ReviewExplanation("H", "B")
        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(review)

        val running = captured.first()
        assertEquals(AiMode.CLOUD, running.mode)
        assertEquals("google-ai-studio", running.provider)
        assertEquals("gemini-2.5-flash", running.modelName)
    }

    // ── provider throws ───────────────────────────────────────────────────────

    @Test
    fun `invoke stores FAILED artifact when provider throws`() = runTest {
        val review = makePendingReview()
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { reviewExplanationService.generate(any()) } throws RuntimeException("Network timeout")

        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(review)

        // RUNNING tombstone + exception FAILED
        coVerify(exactly = 2) { aiArtifactRepository.upsert(any()) }
        val failedArtifact = captured.last()
        assertEquals(AiArtifactStatus.FAILED, failedArtifact.status)
        assertTrue(failedArtifact.errorMessage?.contains("Network timeout") == true)
    }

    // ── expiresAt set correctly ───────────────────────────────────────────────

    @Test
    fun `invoke sets expiresAt to now plus TTL`() = runTest {
        val review = makePendingReview()
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        every { inputBuilder.build(any(), any()) } returns makeInput()
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { reviewExplanationService.generate(any()) } returns ReviewExplanation("H", "B")
        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        useCase(review)

        val runningEntity = captured[0]
        assertEquals(now + AppConfig.Ai.REVIEW_EXPLANATION_TTL_MS, runningEntity.expiresAt)
    }
}
