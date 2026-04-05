package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartReceiptAssistServiceTest {

    @Test
    fun `suggest does not call cloud provider when router selects on-device`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()

        val settings = AiSettings(
            aiEnabled = true,
            receiptAssistEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptImageCloudEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device forced for current context"
        )

        coEvery { onDeviceReceiptAssistService.suggest(any()) } returns AiServiceResult.Success(
            ReceiptAssistSuggestion(
                total = SuggestedValue(value = 12.34, confidence = 0.95f)
            )
        )

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloudReceiptAssistService,
            onDeviceReceiptAssistService = onDeviceReceiptAssistService,
            noOpReceiptAssistService = noOpReceiptAssistService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy
        )

        val input = ReceiptAssistInput(
            receiptId = 1L,
            rawOcrText = "LIDL TOTAL 12.34",
            imagePath = "receipt.jpg",
            imageMimeType = "image/jpeg",
            isImageAnalysisMode = true,
            parsedMerchant = null,
            parsedTotal = null,
            parsedDate = null,
            parsedTaxAmount = null,
            currency = "EUR",
            lineItemsJson = null,
            currentTimeMs = 1_000L
        )

        val result = service.suggest(input)

        assertTrue(result is AiServiceResult.Success)
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { onDeviceReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest does not call on-device provider when router selects cloud`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "Cloud forced for current context"
        )

        coEvery { cloudReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloudReceiptAssistService,
            onDeviceReceiptAssistService = onDeviceReceiptAssistService,
            noOpReceiptAssistService = noOpReceiptAssistService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        coVerify(exactly = 1) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 0) { onDeviceReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest skips ai providers when router selects deterministic fallback`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = "Cloud and on-device unavailable in this context"
        )

        coEvery { noOpReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloudReceiptAssistService,
            onDeviceReceiptAssistService = onDeviceReceiptAssistService,
            noOpReceiptAssistService = noOpReceiptAssistService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 0) { onDeviceReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { noOpReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest skips on-device when router selects disabled`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings, any())
        } returns AiRouteDecision(
            route = AiRoute.DISABLED,
            reason = "Feature disabled"
        )

        coEvery { noOpReceiptAssistService.suggest(any()) } returns AiServiceResult.Failure(
            AiServiceError.Disabled("Receipt assist unavailable")
        )

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloudReceiptAssistService,
            onDeviceReceiptAssistService = onDeviceReceiptAssistService,
            noOpReceiptAssistService = noOpReceiptAssistService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Failure)
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 0) { onDeviceReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { noOpReceiptAssistService.suggest(any()) }
    }

    private fun successfulSuggestionResult(): AiServiceResult.Success<ReceiptAssistSuggestion> {
        return AiServiceResult.Success(
            ReceiptAssistSuggestion(
                total = SuggestedValue(value = 12.34, confidence = 0.95f)
            )
        )
    }

    private fun defaultSettings(): AiSettings {
        return AiSettings(
            aiEnabled = true,
            receiptAssistEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptImageCloudEnabled = true
        )
    }

    private fun defaultInput(): ReceiptAssistInput {
        return ReceiptAssistInput(
            receiptId = 1L,
            rawOcrText = "LIDL TOTAL 12.34",
            imagePath = "receipt.jpg",
            imageMimeType = "image/jpeg",
            isImageAnalysisMode = true,
            parsedMerchant = null,
            parsedTotal = null,
            parsedDate = null,
            parsedTaxAmount = null,
            currency = "EUR",
            lineItemsJson = null,
            currentTimeMs = 1_000L
        )
    }
}
