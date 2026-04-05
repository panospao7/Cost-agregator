package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridServiceDelegationTest {

    @Test
    fun `cloud mode delegates all hybrid services to cloud providers`() = runTest {
        val settings = aiSettings(mode = AiMode.CLOUD, aiEnabled = true)

        val receipt = receiptHarness(settings)
        val categorization = categorizationHarness(settings)
        val query = queryHarness(settings)

        val receiptCloudResult = AiServiceResult.Success(ReceiptAssistSuggestion(notes = listOf("cloud")))
        val categorizationCloudResult = CategoryAssistSuggestion(
            categoryId = 10L,
            categoryName = "Groceries",
            confidence = 0.91f,
            rationale = "cloud"
        )
        val queryCloudResult = FinancialQueryInterpretationResult.Unsupported("cloud")

        coEvery {
            receipt.router.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(AiRoute.CLOUD, "Cloud mode")
        coEvery {
            categorization.router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings, any())
        } returns AiRouteDecision(AiRoute.CLOUD, "Cloud mode")
        coEvery {
            query.router.decide(AiCapability.QUERY_INTERPRETATION, settings, any())
        } returns AiRouteDecision(AiRoute.CLOUD, "Cloud mode")

        every { receipt.cloudService.usedImageInput(receiptInput) } returns false
        coEvery { receipt.cloudService.suggest(receiptInput) } returns receiptCloudResult
        coEvery { categorization.cloudService.suggest(categorizationInput) } returns categorizationCloudResult
        coEvery { query.cloudService.interpret(queryInput) } returns queryCloudResult

        val receiptResult = receipt.service.suggest(receiptInput)
        val categorizationResult = categorization.service.suggest(categorizationInput)
        val queryResult = query.service.interpret(queryInput)

        assertEquals(receiptCloudResult, receiptResult)
        assertEquals(categorizationCloudResult, categorizationResult)
        assertEquals(queryCloudResult, queryResult)

        coVerify(exactly = 1) { receipt.cloudService.suggest(receiptInput) }
        coVerify(exactly = 1) { categorization.cloudService.suggest(categorizationInput) }
        coVerify(exactly = 1) { query.cloudService.interpret(queryInput) }

        coVerify(exactly = 0) { receipt.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { categorization.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { query.onDeviceService.interpret(any()) }

        coVerify(exactly = 0) { receipt.noOpService.suggest(any()) }
        coVerify(exactly = 0) { categorization.noOpService.suggest(any()) }
        coVerify(exactly = 0) { query.noOpService.interpret(any()) }
    }

    @Test
    fun `on-device mode delegates all hybrid services to on-device providers`() = runTest {
        val settings = aiSettings(mode = AiMode.ON_DEVICE, aiEnabled = true)

        val receipt = receiptHarness(settings)
        val categorization = categorizationHarness(settings)
        val query = queryHarness(settings)

        val receiptOnDeviceResult = AiServiceResult.Success(ReceiptAssistSuggestion(notes = listOf("on-device")))
        val categorizationOnDeviceResult = CategoryAssistSuggestion(
            categoryId = 20L,
            categoryName = "Transport",
            confidence = 0.88f,
            rationale = "on-device"
        )
        val queryOnDeviceResult = FinancialQueryInterpretationResult.Unsupported("on-device")

        coEvery {
            receipt.router.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(AiRoute.ON_DEVICE, "On-device mode")
        coEvery {
            categorization.router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings, any())
        } returns AiRouteDecision(AiRoute.ON_DEVICE, "On-device mode")
        coEvery {
            query.router.decide(AiCapability.QUERY_INTERPRETATION, settings, any())
        } returns AiRouteDecision(AiRoute.ON_DEVICE, "On-device mode")

        coEvery { receipt.onDeviceService.suggest(receiptInput) } returns receiptOnDeviceResult
        coEvery { categorization.onDeviceService.suggest(categorizationInput) } returns categorizationOnDeviceResult
        coEvery { query.onDeviceService.interpret(queryInput) } returns queryOnDeviceResult

        val receiptResult = receipt.service.suggest(receiptInput)
        val categorizationResult = categorization.service.suggest(categorizationInput)
        val queryResult = query.service.interpret(queryInput)

        assertEquals(receiptOnDeviceResult, receiptResult)
        assertEquals(categorizationOnDeviceResult, categorizationResult)
        assertEquals(queryOnDeviceResult, queryResult)

        coVerify(exactly = 1) { receipt.onDeviceService.suggest(receiptInput) }
        coVerify(exactly = 1) { categorization.onDeviceService.suggest(categorizationInput) }
        coVerify(exactly = 1) { query.onDeviceService.interpret(queryInput) }

        coVerify(exactly = 0) { receipt.cloudService.suggest(any()) }
        coVerify(exactly = 0) { categorization.cloudService.suggest(any()) }
        coVerify(exactly = 0) { query.cloudService.interpret(any()) }

        coVerify(exactly = 0) { receipt.noOpService.suggest(any()) }
        coVerify(exactly = 0) { categorization.noOpService.suggest(any()) }
        coVerify(exactly = 0) { query.noOpService.interpret(any()) }
    }

    @Test
    fun `fallback mode delegates all hybrid services to deterministic fallback providers`() = runTest {
        val settings = aiSettings(mode = AiMode.AUTO, aiEnabled = true)

        val receipt = receiptHarness(settings)
        val categorization = categorizationHarness(settings)
        val query = queryHarness(settings)

        val receiptFallbackResult = AiServiceResult.Failure(AiServiceError.Disabled("fallback"))
        val queryFallbackResult = FinancialQueryInterpretationResult.Unsupported("fallback")

        coEvery {
            receipt.router.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(AiRoute.DETERMINISTIC_FALLBACK, "Fallback mode")
        coEvery {
            categorization.router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings, any())
        } returns AiRouteDecision(AiRoute.DETERMINISTIC_FALLBACK, "Fallback mode")
        coEvery {
            query.router.decide(AiCapability.QUERY_INTERPRETATION, settings, any())
        } returns AiRouteDecision(AiRoute.DETERMINISTIC_FALLBACK, "Fallback mode")

        coEvery { receipt.noOpService.suggest(receiptInput) } returns receiptFallbackResult
        coEvery { categorization.noOpService.suggest(categorizationInput) } returns null
        coEvery { query.noOpService.interpret(queryInput) } returns queryFallbackResult

        val receiptResult = receipt.service.suggest(receiptInput)
        val categorizationResult = categorization.service.suggest(categorizationInput)
        val queryResult = query.service.interpret(queryInput)

        assertEquals(receiptFallbackResult, receiptResult)
        assertNull(categorizationResult)
        assertEquals(queryFallbackResult, queryResult)

        coVerify(exactly = 1) { receipt.noOpService.suggest(receiptInput) }
        coVerify(exactly = 1) { categorization.noOpService.suggest(categorizationInput) }
        coVerify(exactly = 1) { query.noOpService.interpret(queryInput) }

        coVerify(exactly = 0) { receipt.cloudService.suggest(any()) }
        coVerify(exactly = 0) { categorization.cloudService.suggest(any()) }
        coVerify(exactly = 0) { query.cloudService.interpret(any()) }

        coVerify(exactly = 0) { receipt.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { categorization.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { query.onDeviceService.interpret(any()) }
    }

    @Test
    fun `disabled mode skips cloud and on-device providers for all hybrid services`() = runTest {
        val settings = aiSettings(mode = AiMode.AUTO, aiEnabled = false)

        val receipt = receiptHarness(settings)
        val categorization = categorizationHarness(settings)
        val query = queryHarness(settings)

        val receiptDisabledResult = AiServiceResult.Failure(AiServiceError.Disabled("disabled"))
        val queryDisabledResult = FinancialQueryInterpretationResult.Unsupported("disabled")

        coEvery {
            receipt.router.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(AiRoute.DISABLED, "Disabled")
        coEvery {
            categorization.router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings, any())
        } returns AiRouteDecision(AiRoute.DISABLED, "Disabled")
        coEvery {
            query.router.decide(AiCapability.QUERY_INTERPRETATION, settings, any())
        } returns AiRouteDecision(AiRoute.DISABLED, "Disabled")

        coEvery { receipt.noOpService.suggest(receiptInput) } returns receiptDisabledResult
        coEvery { categorization.noOpService.suggest(categorizationInput) } returns null
        coEvery { query.noOpService.interpret(queryInput) } returns queryDisabledResult

        val receiptResult = receipt.service.suggest(receiptInput)
        val categorizationResult = categorization.service.suggest(categorizationInput)
        val queryResult = query.service.interpret(queryInput)

        assertEquals(receiptDisabledResult, receiptResult)
        assertNull(categorizationResult)
        assertEquals(queryDisabledResult, queryResult)

        coVerify(exactly = 0) { receipt.cloudService.suggest(any()) }
        coVerify(exactly = 0) { categorization.cloudService.suggest(any()) }
        coVerify(exactly = 0) { query.cloudService.interpret(any()) }

        coVerify(exactly = 0) { receipt.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { categorization.onDeviceService.suggest(any()) }
        coVerify(exactly = 0) { query.onDeviceService.interpret(any()) }

        coVerify(exactly = 1) { receipt.noOpService.suggest(receiptInput) }
        coVerify(exactly = 1) { categorization.noOpService.suggest(categorizationInput) }
        coVerify(exactly = 1) { query.noOpService.interpret(queryInput) }

        verify(exactly = 1) { receipt.aiSettingsRepository.settings() }
        verify(exactly = 1) { categorization.aiSettingsRepository.settings() }
        verify(exactly = 1) { query.aiSettingsRepository.settings() }
    }

    private fun aiSettings(mode: AiMode, aiEnabled: Boolean): AiSettings {
        return AiSettings(
            aiEnabled = aiEnabled,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            categorizationFallbackEnabled = true,
            queryInterpretationEnabled = true,
            preferredMode = mode
        )
    }

    private fun receiptHarness(settings: AiSettings): ReceiptHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudReceiptAssistService>()
        val onDeviceService = mockk<OnDeviceReceiptAssistService>()
        val noOpService = mockk<NoOpReceiptAssistService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridReceiptAssistService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudReceiptAssistService = cloudService,
            onDeviceReceiptAssistService = onDeviceService,
            noOpReceiptAssistService = noOpService
        )

        return ReceiptHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private fun categorizationHarness(settings: AiSettings): CategorizationHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudCategorizationAssistService>()
        val onDeviceService = mockk<OnDeviceCategorizationAssistService>()
        val noOpService = mockk<NoOpCategorizationAssistService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridCategorizationAssistService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudCategorizationAssistService = cloudService,
            onDeviceCategorizationAssistService = onDeviceService,
            noOpCategorizationAssistService = noOpService
        )

        return CategorizationHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private fun queryHarness(settings: AiSettings): QueryHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudQueryInterpretationService>()
        val onDeviceService = mockk<OnDeviceQueryInterpretationService>()
        val noOpService = mockk<NoOpQueryInterpretationService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridQueryInterpretationService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudQueryInterpretationService = cloudService,
            onDeviceQueryInterpretationService = onDeviceService,
            noOpQueryInterpretationService = noOpService
        )

        return QueryHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private data class ReceiptHarness(
        val service: HybridReceiptAssistService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudReceiptAssistService,
        val onDeviceService: OnDeviceReceiptAssistService,
        val noOpService: NoOpReceiptAssistService
    )

    private data class CategorizationHarness(
        val service: HybridCategorizationAssistService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudCategorizationAssistService,
        val onDeviceService: OnDeviceCategorizationAssistService,
        val noOpService: NoOpCategorizationAssistService
    )

    private data class QueryHarness(
        val service: HybridQueryInterpretationService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudQueryInterpretationService,
        val onDeviceService: OnDeviceQueryInterpretationService,
        val noOpService: NoOpQueryInterpretationService
    )

    private companion object {
        private val receiptInput = ReceiptAssistInput(
            receiptId = 1L,
            rawOcrText = "MILK 2.50",
            imagePath = null,
            imageMimeType = null,
            isImageAnalysisMode = false,
            redactBeforeCloud = false,
            parsedMerchant = "Mini Market",
            parsedTotal = null,
            parsedDate = null,
            parsedTaxAmount = null,
            currency = "EUR",
            lineItemsJson = null,
            currentTimeMs = 1_700_000_000_000
        )

        private val categorizationInput = CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 44L,
            merchant = "Metro",
            amount = 25.0,
            currency = "EUR",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000,
            currentCategoryId = null,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = listOf(
                CategoryOption(id = 10L, name = "Groceries"),
                CategoryOption(id = 20L, name = "Transport")
            )
        )

        private val queryInput = FinancialQueryInterpretationInput(
            rawQuery = "How much did I spend this month?",
            currentTimeMs = 1_700_000_000_000,
            localeTag = "en-US"
        )
    }
}
