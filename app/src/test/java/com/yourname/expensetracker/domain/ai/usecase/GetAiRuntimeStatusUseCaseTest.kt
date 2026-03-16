package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetAiRuntimeStatusUseCaseTest {

    private val aiEnvironmentMonitor = mockk<AiEnvironmentMonitor>()
    private val timeProvider = mockk<TimeProvider>()
    private val useCase = GetAiRuntimeStatusUseCase(aiEnvironmentMonitor, timeProvider)

    @Test
    fun `invoke returns null highestPriorityMessage when all capabilities available`() = runTest {
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.AVAILABLE
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns true
        every { timeProvider.now() } returns 1234L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(2, result.capabilities.size)
        assertNull(result.highestPriorityMessage)
        assertEquals(null, result.capabilities.first().actionLabel)
        assertEquals(true, result.networkAvailable)
        assertEquals(true, result.wifiConnected)
        assertEquals(1234L, result.lastRefreshedAt)
    }

    @Test
    fun `invoke returns first non-null runtime message`() = runTest {
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.NOT_INSTALLED
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.AVAILABLE
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns false
        every { aiEnvironmentMonitor.isWifiConnected() } returns false
        every { timeProvider.now() } returns 5678L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(
            "On-device AI is available but the model is not installed yet.",
            result.highestPriorityMessage
        )
        assertEquals("Install required", result.capabilities.first().actionLabel)
    }

    @Test
    fun `invoke returns unavailable guidance when runtime is missing`() = runTest {
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.UNAVAILABLE
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns false
        every { timeProvider.now() } returns 9999L

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION))

        assertEquals(
            "On-device AI is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor.",
            result.highestPriorityMessage
        )
        assertEquals("Check device support", result.capabilities.first().actionLabel)
    }
}
