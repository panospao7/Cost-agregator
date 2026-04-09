package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

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
            aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any(), any())
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

        val captured = mutableListOf<AiArtifactRecord>()
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
            aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any(), any())
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

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Success)
        assertEquals(AiMode.ON_DEVICE, captured.first().mode)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, captured.first().provider)
        assertEquals(AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MODEL, captured.first().modelName)
        assertTrue(captured.last().explanationText?.contains("Route: ON_DEVICE") == true)
        coVerify { aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, any(), any()) }
    }

    @Test
    fun `invoke stores route diagnostics in FAILED category artifact`() = runTest {
        val item = makeItem()
        val input = makeInput()
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { categorizationAssistService.suggest(input) } returns null

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Error)
        assertEquals(AiArtifactStatus.FAILED, captured.last().status)
        assertTrue(captured.last().errorMessage?.contains("cloud allowed") == true)
        assertTrue(captured.last().errorMessage?.contains("Route: CLOUD") == true)
    }

    @Test
    fun `invoke for receipt stores scanned receipt artifact when provider returns supported category`() = runTest {
        val receipt = makeReceipt()
        val input = CategorizationAssistInput(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receipt.id,
            merchant = "Lidl",
            amount = 10.0,
            currency = "EUR",
            transactionType = com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
            date = receipt.parsedDate,
            currentCategoryId = null,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = listOf(CategoryOption(2L, "Groceries"))
        )
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery {
            inputBuilder.build(receipt, "Lidl", 10.0, receipt.parsedDate, null, any())
        } returns input
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns null
        coEvery { categoryRepository.getAll() } returns listOf(Category(id = 2L, name = "Groceries", icon = "G", color = "#00FF00"))
        coEvery { categorizationAssistService.suggest(input) } returns CategoryAssistSuggestion(
            categoryId = 2L,
            categoryName = "Groceries",
            rationale = "Receipt text looks like supermarket shopping"
        )

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        val result = useCase(
            receipt = receipt,
            draftMerchant = "Lidl",
            draftAmount = 10.0,
            draftDate = receipt.parsedDate,
            currentCategoryId = null,
            force = false
        )

        assertTrue(result is CategoryAssistGenerationResult.Success)
        assertEquals(AiTargetType.SCANNED_RECEIPT, captured.first().targetType)
        assertEquals("scanned_receipt:7", captured.first().targetKey)
        assertEquals(AppConfig.Ai.RECEIPT_ASSIST_TTL_MS + 1_000L, captured.first().expiresAt)
    }

    @Test
    fun `invoke for receipt returns NotNeeded when confidence is strong and category exists`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { categoryRepository.getAll() } returns listOf(Category(id = 4L, name = "Groceries", icon = "G", color = "#00FF00"))

        val result = useCase(
            receipt = makeReceipt(confidence = 0.95f),
            draftMerchant = "Lidl",
            draftAmount = 10.0,
            draftDate = 123L,
            currentCategoryId = 4L,
            force = false
        )

        assertTrue(result is CategoryAssistGenerationResult.NotNeeded)
    }

    @Test
    fun `invoke for review still allows fallback when deterministic category is Uncategorized`() = runTest {
        val item = PendingReviewWithReceipt(
            makeItem().review.copy(
                suggestedCategoryId = 99L,
                confidence = 0.95f,
                matchType = null
            ),
            null
        )
        val input = makeInput().copy(currentCategoryId = 99L)
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery {
            categoryRepository.getAll()
        } returns listOf(
            Category(id = 99L, name = "Uncategorized", icon = "?", color = "#BDBDBD"),
            Category(id = 2L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { categorizationAssistService.suggest(input) } returns CategoryAssistSuggestion(
            categoryId = 2L,
            categoryName = "Groceries"
        )

        val result = useCase(item)

        assertTrue(result is CategoryAssistGenerationResult.Success)
    }

    @Test
    fun `invoke for receipt still allows fallback when current category is Uncategorized`() = runTest {
        val receipt = makeReceipt(confidence = 0.95f)
        val input = CategorizationAssistInput(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receipt.id,
            merchant = "Lidl",
            amount = 10.0,
            currency = "EUR",
            transactionType = com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
            date = receipt.parsedDate,
            currentCategoryId = 99L,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = listOf(CategoryOption(2L, "Groceries"))
        )
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery {
            inputBuilder.build(receipt, "Lidl", 10.0, receipt.parsedDate, 99L, any())
        } returns input
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns null
        coEvery {
            categoryRepository.getAll()
        } returns listOf(
            Category(id = 99L, name = "Uncategorized", icon = "?", color = "#BDBDBD"),
            Category(id = 2L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { categorizationAssistService.suggest(input) } returns CategoryAssistSuggestion(
            categoryId = 2L,
            categoryName = "Groceries"
        )

        val result = useCase(
            receipt = receipt,
            draftMerchant = "Lidl",
            draftAmount = 10.0,
            draftDate = receipt.parsedDate,
            currentCategoryId = 99L,
            force = false
        )

        assertTrue(result is CategoryAssistGenerationResult.Success)
    }

    @Test
    fun `invoke propagates CancellationException without writing FAILED artifact`() = runTest {
        val item = makeItem()
        val input = makeInput()
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, categorizationFallbackEnabled = true))
        coEvery { inputBuilder.build(item, any()) } returns input
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null
        coEvery { categorizationAssistService.suggest(input) } throws CancellationException("cancelled")

        val captured = mutableListOf<AiArtifactRecord>()
        coEvery { aiArtifactRepository.upsert(capture(captured)) } returns 1L

        try {
            useCase(item)
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        // Only the RUNNING tombstone should have been written, no FAILED artifact
        assertTrue(captured.size == 1)
        assertEquals(AiArtifactStatus.RUNNING, captured.first().status)
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
        transactionType = com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
        date = null,
        currentCategoryId = null,
        deterministicMatchType = "FALLBACK",
        deterministicExplanation = "weak",
        candidateCategories = listOf(CategoryOption(2L, "Groceries"))
    )

    private fun makeReceipt(confidence: Float = 0.4f) = ScannedReceipt(
        id = 7L,
        imagePath = "receipt.jpg",
        rawOcrText = "LIDL TOTAL 10.00",
        parsedTotal = 10.0,
        parsedMerchant = "Lidl",
        parsedDate = 123L,
        parsedItems = null,
        parsedTaxAmount = null,
        currency = "EUR",
        confidence = confidence
    )
}
