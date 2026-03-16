package com.yourname.expensetracker.ui.screens.aisettings

import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiSettings
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
    private lateinit var settingsFlow: MutableStateFlow<AiSettings>
    private lateinit var viewModel: AiSettingsViewModel

    @Before
    override fun setup() {
        super.setup()
        aiSettingsRepository = mockk(relaxed = true)
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        settingsFlow = MutableStateFlow(AiSettings(aiEnabled = true, allowOnDeviceAi = true))

        every { aiSettingsRepository.settings() } returns settingsFlow
        coEvery { getAiRuntimeStatusUseCase(any()) } returns AiRuntimeStatusSummary(emptyList(), null)

        viewModel = AiSettingsViewModel(
            aiSettingsRepository = aiSettingsRepository,
            getAiRuntimeStatusUseCase = getAiRuntimeStatusUseCase
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
        val summary = AiRuntimeStatusSummary(emptyList(), "Runtime info")
        coEvery { getAiRuntimeStatusUseCase(any()) } returns summary

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertEquals("Runtime info", viewModel.uiState.value.runtimeSummary.highestPriorityMessage)
    }
}
