package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.config.AppConfig
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

class SuggestCategoryFallbackUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var categorizationAssistService: CategorizationAssistService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var inputBuilder: CategorizationAssistInputBuilder
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var useCase: SuggestCategoryFallbackUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        aiArtifactRepository = mockk(relaxed = true)
        categorizationAssistService = mockk()
        aiCapabilityRouter = mockk()
        inputBuilder = mockk()
        categoryRepository = mockk()
        timeProvider = FakeTimeProvider(1_000L)

        useCase = SuggestCategoryFallbackUseCase(
            aiSettingsRepository,
            aiArtifactRepository,
            categorizationAssistService,
            aiCapabilityRouter,
            inputBuilder,
            categoryRepository,
            timeProvider
        )
        coEvery {
            aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any())
        } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "cloud allowed",
            providerName = AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_PROVIDER,
            modelName = AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL
        )
    }

    @Test
    fun `invoke returns Disabled when flag off`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = false))

        val result = useCase(makeItem())

        assertTrue(result is CategoryAssistGenerationResult.Disabled)
    }

    @Test
    fun `invoke stores READY artifact when provider returns supported category`() = runTest {
        val item = makeItem()
        val input = makeInput()
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { categoryRepository.getAll() } returns listOf(Category(id = 2L, name = "Groceries", icon = "G", color = "#00FF00"))
        coEvery { categorizationAssistService.suggest(input) } returns CategoryAssistSuggestion(
            categoryId = 2L,
            categoryName = "Groceries",
            rationale = "Merchant looks like a supermarket"
        )

        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Success)
        assertEquals(AiArtifactStatus.READY, captured.last().status)
        assertEquals(AiCapability.CATEGORIZATION_FALLBACK, captured.last().capability)
        assertEquals(AiMode.CLOUD, captured.first().mode)
        assertEquals(AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_PROVIDER, captured.first().provider)
        assertEquals(AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL, captured.first().modelName)
        assertTrue(captured.last().explanationText?.contains("Route: CLOUD") == true)
    }

    @Test
    fun `invoke stores ON_DEVICE metadata when router selects local categorization`() = runTest {
        val item = makeItem()
        val input = makeInput()
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowOnDeviceAi = true,
                categorizationFallbackEnabled = true,
                preferredMode = AiMode.ON_DEVICE
            )
        )
        coEvery {
            aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
            modelName = AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MODEL
        )
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { categoryRepository.getAll() } returns listOf(Category(id = 2L, name = "Groceries", icon = "G", color = "#00FF00"))
        coEvery { categorizationAssistService.suggest(input) } returns CategoryAssistSuggestion(
            categoryId = 2L,
            categoryName = "Groceries",
            rationale = "Merchant looks like a supermarket"
        )

        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Success)
        assertEquals(AiMode.ON_DEVICE, captured.first().mode)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, captured.first().provider)
        assertEquals(AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MODEL, captured.first().modelName)
        assertTrue(captured.last().explanationText?.contains("Route: ON_DEVICE") == true)
        coVerify { aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any()) }
    }

    @Test
    fun `invoke stores route diagnostics in FAILED category artifact`() = runTest {
        val item = makeItem()
        val input = makeInput()
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { categorizationAssistService.suggest(input) } returns null

        val captured = mutableListOf<AiArtifactEntity>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Error)
        assertEquals(AiArtifactStatus.FAILED, captured.last().status)
        assertTrue(captured.last().errorMessage?.contains("cloud allowed") == true)
        assertTrue(captured.last().errorMessage?.contains("Route: CLOUD") == true)
    }

    private fun makeItem() = PendingReviewWithReceipt(
        PendingReview(
            id = 1L,
            rawNotificationId = null,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Lidl",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.4f,
            matchType = "FALLBACK",
            explanation = "weak",
            packageName = "pkg",
            notificationTitle = null,
            notificationText = null
        ),
        null
    )

    private fun makeInput() = CategorizationAssistInput(
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = 1L,
        merchant = "Lidl",
        amount = 10.0,
        currency = "EUR",
        transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
        date = null,
        currentCategoryId = null,
        deterministicMatchType = "FALLBACK",
        deterministicExplanation = "weak",
        candidateCategories = listOf(CategoryOption(2L, "Groceries"))
    )
}
