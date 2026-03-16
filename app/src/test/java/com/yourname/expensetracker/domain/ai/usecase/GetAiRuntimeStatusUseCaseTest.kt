package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetAiRuntimeStatusUseCaseTest {

    private val aiEnvironmentMonitor = mockk<AiEnvironmentMonitor>()
    private val useCase = GetAiRuntimeStatusUseCase(aiEnvironmentMonitor)

    @Test
    fun `invoke returns null highestPriorityMessage when all capabilities available`() = runTest {
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.AVAILABLE

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(2, result.capabilities.size)
        assertNull(result.highestPriorityMessage)
    }

    @Test
    fun `invoke returns first non-null runtime message`() = runTest {
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.NOT_INSTALLED
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.AVAILABLE

        val result = useCase(listOf(AiCapability.QUERY_INTERPRETATION, AiCapability.DASHBOARD_BRIEFING))

        assertEquals(
            "On-device AI is available but the model is not installed yet.",
            result.highestPriorityMessage
        )
    }
}
