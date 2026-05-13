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
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

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
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device forced for current context"
        )
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode.name == "CLOUD" }, any())
        } returns AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = "Cloud unavailable"
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
            aiPolicy = aiPolicy,
            privacyGate = privacyGate,
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
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any())
        } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "Cloud forced for current context"
        )
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode.name == "ON_DEVICE" }, any())
        } returns AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = "On-device unavailable"
        )

        coEvery { cloudReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloudReceiptAssistService,
            onDeviceReceiptAssistService = onDeviceReceiptAssistService,
            noOpReceiptAssistService = noOpReceiptAssistService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy,
            privacyGate = privacyGate,
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
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any())
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
            aiPolicy = aiPolicy,
            privacyGate = privacyGate,
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
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any())
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
            aiPolicy = aiPolicy,
            privacyGate = privacyGate,
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Failure)
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 0) { onDeviceReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { noOpReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest falls through from cloud to on device when cloud attempt fails`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any()) } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "Cloud preferred"
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode.name == "ON_DEVICE" }, any()) } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device fallback is available"
        )
        coEvery { cloudReceiptAssistService.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.Timeout),
            AiServiceResult.Failure(AiServiceError.Timeout)
        )
        coEvery { onDeviceReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService,
            onDeviceReceiptAssistService,
            noOpReceiptAssistService,
            aiCapabilityRouter,
            aiSettingsRepository,
            aiPolicy,
            privacyGate = privacyGate,
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        val suggestion = (result as AiServiceResult.Success).value
        assertEquals(2, suggestion.attemptDetails.size)
        assertEquals("CLOUD_VISION", suggestion.attemptDetails[0].method)
        assertEquals("ON_DEVICE_VISION", suggestion.attemptDetails[1].method)
        coVerify(atLeast = 1) { cloudReceiptAssistService.suggest(any()) }
        coVerify(atLeast = 1) { onDeviceReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest falls through from on device to cloud when local attempt fails`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        every { aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        every { aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION) } returns true
        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any()) } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device preferred"
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode.name == "CLOUD" }, any()) } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "Cloud fallback is available"
        )
        coEvery { onDeviceReceiptAssistService.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse")),
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse"))
        )
        coEvery { noOpReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService,
            onDeviceReceiptAssistService,
            noOpReceiptAssistService,
            aiCapabilityRouter,
            aiSettingsRepository,
            aiPolicy,
            privacyGate = privacyGate,
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        val suggestion = (result as AiServiceResult.Success).value
        assertEquals("ON_DEVICE_VISION", suggestion.attemptDetails[0].method)
        assertEquals("ON_DEVICE_TEXT", suggestion.attemptDetails[1].method)
        assertFalse(suggestion.usedImageInput)
        coVerify(exactly = 2) { onDeviceReceiptAssistService.suggest(any()) }
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { noOpReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `suggest does not retry cloud fallback when router-selected on-device has no viable cloud route`() = runTest {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any()) } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device preferred because cloud is unavailable"
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode.name == "CLOUD" }, any()) } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "Cloud unavailable, on-device fallback only"
        )
        coEvery { onDeviceReceiptAssistService.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse")),
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse"))
        )
        coEvery { noOpReceiptAssistService.suggest(any()) } returns successfulSuggestionResult()

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService,
            onDeviceReceiptAssistService,
            noOpReceiptAssistService,
            aiCapabilityRouter,
            aiSettingsRepository,
            aiPolicy,
            privacyGate = privacyGate,
        )

        val result = service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        coVerify(exactly = 0) { cloudReceiptAssistService.suggest(any()) }
        coVerify(exactly = 2) { onDeviceReceiptAssistService.suggest(any()) }
        coVerify(exactly = 1) { noOpReceiptAssistService.suggest(any()) }
    }

    @Test
    fun `usedImageInput reflects the selected execution result`() {
        val cloudReceiptAssistService = mockk<CloudReceiptAssistService>()
        val onDeviceReceiptAssistService = mockk<OnDeviceReceiptAssistService>()
        val noOpReceiptAssistService = mockk<NoOpReceiptAssistService>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiPolicy = mockk<AiPolicy>()

        val settings = defaultSettings()
        every { aiSettingsRepository.settings() } returns flowOf(settings)
        coEvery {
            aiCapabilityRouter.decide(
                AiCapability.RECEIPT_EXTRACTION,
                match { it.preferredMode == settings.preferredMode },
                any()
            )
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device preferred"
        )
        coEvery {
            aiCapabilityRouter.decide(
                AiCapability.RECEIPT_EXTRACTION,
                match { it.preferredMode.name == "CLOUD" },
                any()
            )
        } returns AiRouteDecision(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            reason = "Cloud unavailable"
        )

        coEvery {
            onDeviceReceiptAssistService.suggest(any())
        } returns AiServiceResult.Success(
            ReceiptAssistSuggestion(
                total = SuggestedValue(value = 12.34, confidence = 0.95f),
                usedImageInput = true
            )
        )

        val service = SmartReceiptAssistService(
            cloudReceiptAssistService,
            onDeviceReceiptAssistService,
            noOpReceiptAssistService,
            aiCapabilityRouter,
            aiSettingsRepository,
            aiPolicy,
            privacyGate = mockk(),
        )

        assertTrue(service.usedImageInput(defaultInput()))
        assertFalse(service.usedImageInput(defaultInput().copy(isImageAnalysisMode = false)))
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