package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetAiRuntimeStatusUseCaseTest {

    private val aiEnvironmentMonitor = mockk<AiEnvironmentMonitor>()
    private val aiSettingsRepository = mockk<AiSettingsRepository>()
    private val aiCapabilityRouter = mockk<AiCapabilityRouter>()
    private val timeProvider = mockk<TimeProvider>()
    private val useCase = GetAiRuntimeStatusUseCase(
        aiEnvironmentMonitor,
        aiSettingsRepository,
        aiCapabilityRouter,
        timeProvider
    )

    @Test
    fun `invoke returns null highestPriorityMessage when all capabilities available`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, allowCloudAi = true))
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.AVAILABLE
        coEvery { aiCapabilityRouter.decide(any(), any(), any()) } returns AiRouteDecision(
            AiRoute.CLOUD,
            "Preferred mode is cloud and connectivity/policy allow it.",
            "google-ai-studio",
            "gemini-2.5-flash"
        )
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns true
        every { timeProvider.now() } returns 1234L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(2, result.capabilities.size)
        assertNull(result.highestPriorityMessage)
        assertEquals(null, result.capabilities.first().actionLabel)
        assertEquals(AiRoute.CLOUD, result.capabilities.first().route)
        assertEquals("google-ai-studio", result.capabilities.first().providerName)
        assertEquals(true, result.networkAvailable)
        assertEquals(true, result.wifiConnected)
        assertEquals(1234L, result.lastRefreshedAt)
    }

    @Test
    fun `invoke returns first non-null runtime message`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, allowOnDeviceAi = true))
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.NOT_INSTALLED
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.AVAILABLE
        coEvery { aiCapabilityRouter.decide(AiCapability.QUERY_INTERPRETATION, any(), any()) } returns AiRouteDecision(
            AiRoute.DETERMINISTIC_FALLBACK,
            "On-device model is not installed on this device."
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) } returns AiRouteDecision(
            AiRoute.ON_DEVICE,
            "AUTO mode selected the capability's on-device-first route.",
            "mlkit-genai-nano",
            "gemini-nano"
        )
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns false
        every { aiEnvironmentMonitor.isWifiConnected() } returns false
        every { timeProvider.now() } returns 5678L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(
            "On-device model is not installed on this device.",
            result.highestPriorityMessage
        )
        assertEquals("Install required", result.capabilities.first().actionLabel)
    }

    @Test
    fun `invoke returns unavailable guidance when runtime is missing`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, allowCloudAi = false, allowOnDeviceAi = true)
        )
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.UNAVAILABLE
        coEvery { aiCapabilityRouter.decide(AiCapability.QUERY_INTERPRETATION, any(), any()) } returns AiRouteDecision(
            AiRoute.DETERMINISTIC_FALLBACK,
            "Cloud AI is disabled in settings. On-device model is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor."
        )
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns false
        every { timeProvider.now() } returns 9999L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION))

        assertEquals(
            "Cloud AI is disabled in settings. On-device model is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor.",
            result.highestPriorityMessage
        )
        assertEquals("Check device support", result.capabilities.first().actionLabel)
    }

    @Test
    fun `invoke exposes route metadata for cloud-capable status rows`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, allowCloudAi = true))
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.UNAVAILABLE
        coEvery { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) } returns AiRouteDecision(
            AiRoute.CLOUD,
            "AUTO mode fell back from on-device to cloud.",
            "google-ai-studio",
            "gemini-2.5-flash"
        )
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns true
        every { timeProvider.now() } returns 1111L

        val result = useCase(listOf(AiCapability.DASHBOARD_BRIEFING))

        assertNull(result.highestPriorityMessage)
        assertEquals("google-ai-studio", result.capabilities.first().providerName)
        assertEquals("gemini-2.5-flash", result.capabilities.first().modelName)
        assertEquals(AiRoute.CLOUD, result.capabilities.first().route)
        assertEquals(
            "On-device briefing is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor.",
            result.capabilities.first().onDeviceMessage
        )
    }
}
