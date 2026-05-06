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
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest : ViewModelTestUtils() {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase
    private lateinit var secureKeyStorage: SecureKeyStorage
    private lateinit var settingsFlow: MutableStateFlow<AiSettings>
    private lateinit var viewModel: AiSettingsViewModel

    @Before
    override fun setup() {
        super.setup()
        aiSettingsRepository = mockk(relaxed = true)
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        aiRuntimeDiagnostics = mockk(relaxed = true)
        syncProactiveBriefingWorkUseCase = mockk(relaxed = true)
        secureKeyStorage = mockk(relaxed = true)
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
            aiRuntimeDiagnostics = aiRuntimeDiagnostics,
            syncProactiveBriefingWorkUseCase = syncProactiveBriefingWorkUseCase,
            secureKeyStorage = secureKeyStorage,
            privacyGate = mockk(relaxed = true)
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
    fun `setReceiptImageCloudEnabled updates repository`() = runTest(testDispatcher) {
        viewModel.setReceiptImageCloudEnabled(true)
        advanceUntilIdle()

        coVerify { aiSettingsRepository.update(any()) }
    }

    @Test
    fun `setProactiveBriefingsEnabled syncs work scheduling`() = runTest(testDispatcher) {
        viewModel.setProactiveBriefingsEnabled(true)
        advanceUntilIdle()

        coVerify { aiSettingsRepository.update(any()) }
        coVerify {
            syncProactiveBriefingWorkUseCase(match { it.proactiveBriefingsEnabled })
        }
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

    @Test
    fun `saveApiKey requires successful connection test before storing typed key`() = runTest(testDispatcher) {
        viewModel.updateApiKeyInput("AIza12345678901234567890")

        viewModel.saveApiKey()
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) { secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, any()) }
        assertEquals("Run a successful connection test before saving this API key.", viewModel.uiState.value.connectionTestMessage)
        assertEquals(false, viewModel.uiState.value.isConnectionTestSuccess)
        assertEquals("AIza12345678901234567890", viewModel.uiState.value.apiKeyInput)
    }

    @Test
    fun `testConnection does not persist typed key when connection test fails`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = false, allowCloudAi = true)
        viewModel.updateApiKeyInput("AIza12345678901234567890")

        viewModel.testConnection()
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) { secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, any()) }
        assertEquals("Enable AI first, then run connection test again.", viewModel.uiState.value.connectionTestMessage)
        assertEquals(false, viewModel.uiState.value.isConnectionTestSuccess)
        assertEquals("AIza12345678901234567890", viewModel.uiState.value.apiKeyInput)
        assertFalse(viewModel.uiState.value.hasStoredApiKey)
    }

    @Test
    fun `saveApiKey stores typed key after successful connection test`() = runTest(testDispatcher) {
        val summary = AiRuntimeStatusSummary(
            capabilities = listOf(
                AiCapabilityRuntimeStatus(
                    capability = AiCapability.QUERY_INTERPRETATION,
                    status = OnDeviceModelStatus.UNAVAILABLE,
                    message = null,
                    actionLabel = null,
                    route = AiRoute.CLOUD,
                    providerName = "google-ai-studio",
                    modelName = "gemini-2.5-flash"
                )
            ),
            highestPriorityMessage = null,
            networkAvailable = true,
            wifiConnected = true,
            lastRefreshedAt = 9999L
        )
        coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } returns summary

        viewModel.updateApiKeyInput("AIza12345678901234567890")
        viewModel.testConnection()
        advanceUntilIdle()
        viewModel.saveApiKey()
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) {
            secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, "AIza12345678901234567890")
        }
        assertTrue(viewModel.uiState.value.hasStoredApiKey)
        assertEquals("", viewModel.uiState.value.apiKeyInput)
        assertEquals("API key saved securely.", viewModel.uiState.value.connectionTestMessage)
        assertEquals(true, viewModel.uiState.value.isConnectionTestSuccess)
    }
}
