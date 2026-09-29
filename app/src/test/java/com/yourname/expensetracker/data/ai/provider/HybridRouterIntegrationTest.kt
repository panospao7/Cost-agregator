package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HybridRouterIntegrationTest(private val route: AiRoute) {
    private val settings = mockk<AiSettings>()
    private val settingsRepository = mockk<AiSettingsRepository> {
        every { settings() } returns flowOf(this@HybridRouterIntegrationTest.settings)
    }
    private val router = mockk<AiCapabilityRouter> {
        coEvery { decide(any(), settings) } returns AiRouteDecision(route, "test_route")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun routes(): List<Array<Any>> = AiRoute.values().map { arrayOf<Any>(it) }
    }

    @Test
    fun categorizationAssistUsesOnlyTheSelectedRoute() = runTest {
        val cloud = mockk<CloudCategorizationAssistService>()
        val device = mockk<OnDeviceCategorizationAssistService>()
        val fallback = mockk<NoOpCategorizationAssistService>()
        val input = mockk<CategorizationAssistInput>()
        val cloudResult = mockk<CategoryAssistSuggestion>()
        val deviceResult = mockk<CategoryAssistSuggestion>()
        val fallbackResult = mockk<CategoryAssistSuggestion>()
        coEvery { cloud.suggest(input) } returns cloudResult
        coEvery { device.suggest(input) } returns deviceResult
        coEvery { fallback.suggest(input) } returns fallbackResult
        val service = HybridCategorizationAssistService(settingsRepository, router, cloud, device, fallback)

        val actual = service.suggest(input)

        val expected = when (route) {
            AiRoute.CLOUD -> cloudResult
            AiRoute.ON_DEVICE -> deviceResult
            else -> fallbackResult
        }
        assertSame(expected, actual)
        coVerify(exactly = 1) { router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings) }
        coVerify(exactly = if (route == AiRoute.CLOUD) 1 else 0) { cloud.suggest(any()) }
        coVerify(exactly = if (route == AiRoute.ON_DEVICE) 1 else 0) { device.suggest(any()) }
        coVerify(exactly = if (route == AiRoute.DISABLED || route == AiRoute.DETERMINISTIC_FALLBACK) 1 else 0) { fallback.suggest(any()) }
    }

    @Test
    fun dashboardBriefingUsesOnlyTheSelectedRoute() = runTest {
        val cloud = mockk<CloudDashboardBriefingService>()
        val device = mockk<OnDeviceDashboardBriefingService>()
        val fallback = mockk<NoOpDashboardBriefingService>()
        val input = mockk<DashboardBriefingInput>()
        val cloudResult = AiServiceResult.Success(mockk<DashboardBriefing>())
        val deviceResult = AiServiceResult.Success(mockk<DashboardBriefing>())
        val fallbackResult = AiServiceResult.Success(mockk<DashboardBriefing>())
        coEvery { cloud.generate(input) } returns cloudResult
        coEvery { device.generate(input) } returns deviceResult
        coEvery { fallback.generate(input) } returns fallbackResult
        val service = HybridDashboardBriefingService(settingsRepository, router, cloud, device, fallback)

        val actual = service.generate(input)

        val expected = when (route) {
            AiRoute.CLOUD -> cloudResult
            AiRoute.ON_DEVICE -> deviceResult
            else -> fallbackResult
        }
        assertSame(expected, actual)
        coVerify(exactly = 1) { router.decide(AiCapability.DASHBOARD_BRIEFING, settings) }
        coVerify(exactly = if (route == AiRoute.CLOUD) 1 else 0) { cloud.generate(any()) }
        coVerify(exactly = if (route == AiRoute.ON_DEVICE) 1 else 0) { device.generate(any()) }
        coVerify(exactly = if (route == AiRoute.DISABLED || route == AiRoute.DETERMINISTIC_FALLBACK) 1 else 0) { fallback.generate(any()) }
    }

    @Test
    fun reviewExplanationUsesOnlyTheSelectedRoute() = runTest {
        val cloud = mockk<CloudReviewExplanationService>()
        val device = mockk<OnDeviceReviewExplanationService>()
        val fallback = mockk<NoOpReviewExplanationService>()
        val input = mockk<ReviewExplanationInput>()
        val cloudResult = AiServiceResult.Success(mockk<ReviewExplanation>())
        val deviceResult = AiServiceResult.Success(mockk<ReviewExplanation>())
        val fallbackResult = AiServiceResult.Success(mockk<ReviewExplanation>())
        coEvery { cloud.generate(input) } returns cloudResult
        coEvery { device.generate(input) } returns deviceResult
        coEvery { fallback.generate(input) } returns fallbackResult
        val service = HybridReviewExplanationService(settingsRepository, router, cloud, device, fallback)

        val actual = service.generate(input)

        val expected = when (route) {
            AiRoute.CLOUD -> cloudResult
            AiRoute.ON_DEVICE -> deviceResult
            else -> fallbackResult
        }
        assertSame(expected, actual)
        coVerify(exactly = 1) { router.decide(AiCapability.REVIEW_EXPLANATION, settings) }
        coVerify(exactly = if (route == AiRoute.CLOUD) 1 else 0) { cloud.generate(any()) }
        coVerify(exactly = if (route == AiRoute.ON_DEVICE) 1 else 0) { device.generate(any()) }
        coVerify(exactly = if (route == AiRoute.DISABLED || route == AiRoute.DETERMINISTIC_FALLBACK) 1 else 0) { fallback.generate(any()) }
    }

    @Test
    fun queryInterpretationUsesOnlyTheSelectedRoute() = runTest {
        val cloud = mockk<CloudQueryInterpretationService>()
        val device = mockk<OnDeviceQueryInterpretationService>()
        val fallback = mockk<NoOpQueryInterpretationService>()
        val input = mockk<FinancialQueryInterpretationInput>()
        val cloudResult = FinancialQueryInterpretationResult.Unsupported("test_result")
        val deviceResult = FinancialQueryInterpretationResult.Unsupported("test_result")
        val fallbackResult = FinancialQueryInterpretationResult.Unsupported("test_result")
        coEvery { cloud.interpret(input) } returns cloudResult
        coEvery { device.interpret(input) } returns deviceResult
        coEvery { fallback.interpret(input) } returns fallbackResult
        val service = HybridQueryInterpretationService(settingsRepository, router, cloud, device, fallback)

        val actual = service.interpret(input)

        val expected = when (route) {
            AiRoute.CLOUD -> cloudResult
            AiRoute.ON_DEVICE -> deviceResult
            else -> fallbackResult
        }
        assertSame(expected, actual)
        coVerify(exactly = 1) { router.decide(AiCapability.QUERY_INTERPRETATION, settings) }
        coVerify(exactly = if (route == AiRoute.CLOUD) 1 else 0) { cloud.interpret(any()) }
        coVerify(exactly = if (route == AiRoute.ON_DEVICE) 1 else 0) { device.interpret(any()) }
        coVerify(exactly = if (route == AiRoute.DISABLED || route == AiRoute.DETERMINISTIC_FALLBACK) 1 else 0) { fallback.interpret(any()) }
    }

    @Test
    fun receiptItemCategorizationUsesOnlyTheSelectedRoute() = runTest {
        val cloud = mockk<CloudReceiptItemCategorizationService>()
        val device = mockk<OnDeviceReceiptItemCategorizationService>()
        val input = mockk<ReceiptItemCategorizationInput>()
        val cloudResult = mockk<ReceiptItemCategorizationResult>()
        val deviceResult = mockk<ReceiptItemCategorizationResult>()
        coEvery { cloud.categorizeItems(input) } returns cloudResult
        coEvery { device.categorizeItems(input) } returns deviceResult
        val service = HybridReceiptItemCategorizationService(device, cloud, settingsRepository, router)

        val actual = service.categorizeItems(input)

        val expected = when (route) {
            AiRoute.CLOUD -> cloudResult
            AiRoute.ON_DEVICE -> deviceResult
            else -> null
        }
        assertSame(expected, actual)
        coVerify(exactly = 1) { router.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, settings) }
        coVerify(exactly = if (route == AiRoute.CLOUD) 1 else 0) { cloud.categorizeItems(any()) }
        coVerify(exactly = if (route == AiRoute.ON_DEVICE) 1 else 0) { device.categorizeItems(any()) }
    }
}

