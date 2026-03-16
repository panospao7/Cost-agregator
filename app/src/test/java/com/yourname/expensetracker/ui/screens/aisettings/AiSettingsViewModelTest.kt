package com.yourname.expensetracker.ui.screens.aisettings

import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AiSettingsViewModelTest : ViewModelTestUtils() {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var settingsFlow: MutableStateFlow<AiSettings>
    private lateinit var viewModel: AiSettingsViewModel

    @Before
    override fun setup() {
        super.setup()
        aiSettingsRepository = mockk(relaxed = true)
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        aiRuntimeDiagnostics = mockk(relaxed = true)
        settingsFlow = MutableStateFlow(AiSettings(aiEnabled = true, allowOnDeviceAi = true))

        every { aiSettingsRepository.settings() } returns settingsFlow
        coEvery { getAiRuntimeStatusUseCase(any()) } returns AiRuntimeStatusSummary(
            capabilities = emptyList(),
            highestPriorityMessage = null,
            networkAvailable = true,
            wifiConnected = true,
            lastRefreshedAt = 1234L
        )

        viewModel = AiSettingsViewModel(
            aiSettingsRepository = aiSettingsRepository,
            getAiRuntimeStatusUseCase = getAiRuntimeStatusUseCase,
            aiRuntimeDiagnostics = aiRuntimeDiagnostics
        )
    }

    @Test
    fun `uiState reflects repository settings`() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.settings.aiEnabled)
        assertEquals(true, viewModel.uiState.value.settings.allowOnDeviceAi)
    }

    @Test
    fun `setPreferredMode updates repository`() = runTest(testDispatcher) {
        viewModel.setPreferredMode(com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE)
        advanceUntilIdle()

        coVerify { aiSettingsRepository.update(any()) }
    }

    @Test
    fun `refreshRuntimeStatus updates runtime summary`() = runTest(testDispatcher) {
        val summary = AiRuntimeStatusSummary(
            capabilities = listOf(
                AiCapabilityRuntimeStatus(
                    capability = AiCapability.DASHBOARD_BRIEFING,
                    status = OnDeviceModelStatus.UNAVAILABLE,
                    message = null,
                    actionLabel = null,
                    route = AiRoute.CLOUD,
                    providerName = "google-ai-studio",
                    modelName = "gemini-2.5-flash"
                )
            ),
            highestPriorityMessage = "Runtime info",
            networkAvailable = true,
            wifiConnected = false,
            lastRefreshedAt = 4321L
        )
        coEvery { getAiRuntimeStatusUseCase(any()) } returns summary

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertEquals("Runtime info", viewModel.uiState.value.runtimeSummary.highestPriorityMessage)
        assertEquals(4321L, viewModel.uiState.value.runtimeSummary.lastRefreshedAt)
        assertEquals(AiRoute.CLOUD, viewModel.uiState.value.runtimeSummary.capabilities.first().route)
    }
}
