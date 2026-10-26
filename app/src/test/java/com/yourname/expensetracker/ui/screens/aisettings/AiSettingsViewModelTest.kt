package com.yourname.expensetracker.ui.screens.aisettings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.ai.service.CloudProviderConnectionTester
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
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest : ViewModelTestUtils() {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase
    private lateinit var secureKeyStorage: SecureKeyStorage
    private lateinit var settingsFlow: MutableStateFlow<AiSettings>
    private lateinit var viewModel: AiSettingsViewModel
    private val connectionTester = mockk<CloudProviderConnectionTester>()
    private lateinit var notificationPermissionChecker: NotificationPermissionChecker

    @Before
    override fun setup() {
        super.setup()
        aiSettingsRepository = mockk(relaxed = true)
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        aiRuntimeDiagnostics = mockk(relaxed = true)
        syncProactiveBriefingWorkUseCase = mockk(relaxed = true)
        secureKeyStorage = mockk(relaxed = true)
        notificationPermissionChecker = mockk()
        every { notificationPermissionChecker.areNotificationsEnabled() } returns true
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
            privacyGate = mockk(relaxed = true),
            connectionTester = connectionTester,
            notificationPermissionChecker = notificationPermissionChecker
        )
    }

    @org.junit.After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                if (::viewModel.isInitialized) viewModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        } finally {
            super.tearDown()
        }
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

    // ai-fixes B3a / INV-25: allowCloudAi is now an input of the briefing arming predicate.
    @Test
    fun `setAllowCloudAi syncs proactive briefing work with the updated settings`() = runTest(testDispatcher) {
        viewModel.setAllowCloudAi(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { aiSettingsRepository.update(any()) }
        coVerify(exactly = 1) {
            syncProactiveBriefingWorkUseCase(match { it.allowCloudAi })
        }
    }

    @Test
    fun `setAllowCloudAi false syncs proactive briefing work with cloud off`() = runTest(testDispatcher) {
        viewModel.setAllowCloudAi(false)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            syncProactiveBriefingWorkUseCase(match { !it.allowCloudAi })
        }
    }

    @Test
    fun `setAllowCloudAi write failure does not sync proactive briefing work`() = runTest(testDispatcher) {
        coEvery { aiSettingsRepository.update(any()) } throws IOException(rawPath)

        viewModel.setAllowCloudAi(true)
        advanceUntilIdle()

        assertEquals(
            UiText.StringResource(R.string.ai_error_settings_save_failed),
            viewModel.uiState.value.settingsWriteError
        )
        coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
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
    fun `runtime capability list excludes WARRANTY_EXTRACTION so the hidden row cannot drive guidance`() = runTest(testDispatcher) {
        val requested = mutableListOf<List<AiCapability>>()
        coEvery { getAiRuntimeStatusUseCase(capture(requested)) } returns AiRuntimeStatusSummary(
            capabilities = emptyList(),
            highestPriorityMessage = null,
            networkAvailable = true,
            wifiConnected = true,
            lastRefreshedAt = 1L
        )

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertTrue(requested.isNotEmpty())
        requested.forEach { capabilities ->
            assertFalse(AiCapability.WARRANTY_EXTRACTION in capabilities)
            assertTrue(AiCapability.QUERY_INTERPRETATION in capabilities)
        }
    }

    @Test
    fun `saveApiKey requires successful connection test before storing typed key`() = runTest(testDispatcher) {
        viewModel.updateApiKeyInput("AIza12345678901234567890")

        viewModel.saveApiKey()
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) { secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, any()) }
        assertEquals(UiText.StringResource(R.string.ai_key_test_before_save), viewModel.uiState.value.connectionTestMessage)
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
        assertEquals(UiText.StringResource(R.string.ai_key_enable_ai_first), viewModel.uiState.value.connectionTestMessage)
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
        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true)
        coEvery { connectionTester.testGemini("AIza12345678901234567890") } returns null
        advanceUntilIdle()

        viewModel.updateApiKeyInput("AIza12345678901234567890")
        viewModel.testConnection()
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.isConnectionTestSuccess)
        coVerify(exactly = 1) { connectionTester.testGemini("AIza12345678901234567890") }
        io.mockk.verify(exactly = 0) { secureKeyStorage.storeKey(any(), any()) }
        viewModel.saveApiKey()
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) {
            secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, "AIza12345678901234567890")
        }
        assertTrue(viewModel.uiState.value.hasStoredApiKey)
        assertEquals("", viewModel.uiState.value.apiKeyInput)
        assertEquals(UiText.StringResource(R.string.ai_key_saved), viewModel.uiState.value.connectionTestMessage)
        assertEquals(true, viewModel.uiState.value.isConnectionTestSuccess)
    }

    // ---- ai-fixes Part A: bounded, localized errors (INV-2/3/4; AE-2, AE-4, AE-5) ----

    /** Captures what Timber receives so tests can prove logs carry no throwable and no raw exception text. */
    private class CapturingTree : Timber.Tree() {
        val messages = mutableListOf<String>()
        val throwables = mutableListOf<Throwable>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
            if (t != null) throwables += t
        }
    }

    private inline fun withCapturedLogs(block: (CapturingTree) -> Unit) {
        val tree = CapturingTree()
        Timber.plant(tree)
        try {
            block(tree)
        } finally {
            Timber.uproot(tree)
        }
    }

    private val rawPath = "/data/user/0/x/files/datastore/ai_settings.preferences_pb"

    // AE-2
    @Test
    fun `settings write failure shows localized resource without path or exception text and logs class name only`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { aiSettingsRepository.update(any()) } throws IOException(rawPath)

            viewModel.setDashboardBriefingEnabled(true)
            advanceUntilIdle()

            val error = viewModel.uiState.value.settingsWriteError
            assertEquals(UiText.StringResource(R.string.ai_error_settings_save_failed), error)
            assertTrue((error as UiText.StringResource).args.isEmpty())
            assertTrue(tree.throwables.isEmpty())
            assertTrue(tree.messages.any { it.contains("IOException") })
            assertTrue(tree.messages.none { it.contains(rawPath) || it.contains("datastore") })
            // The failed write must not arm/disarm the proactive-briefing work.
            coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
        }
    }

    // AE-4
    @Test
    fun `settings write cancellation propagates and sets no settings error`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { aiSettingsRepository.update(any()) } throws CancellationException("cancelled")

            viewModel.setAiEnabled(false)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.settingsWriteError)
            assertTrue(tree.messages.none { it.contains("write failed") })
            coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
        }
    }

    // AE-4 (sync step after a successful write)
    @Test
    fun `proactive briefing sync cancellation propagates and sets no settings error`() = runTest(testDispatcher) {
        coEvery { syncProactiveBriefingWorkUseCase(any()) } throws CancellationException("cancelled")

        viewModel.setProactiveBriefingsEnabled(true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.settingsWriteError)
    }

    // ---- ai-fixes B2e: insight setter (AE-32, INV-2/4) ----

    // AE-32 / B2e: the setter persists through the repository and only flips its own field.
    @Test
    fun `setTransactionInsightEnabled persists only the insight flag via repository update`() = runTest(testDispatcher) {
        val transform = slot<(AiSettings) -> AiSettings>()
        coEvery { aiSettingsRepository.update(capture(transform)) } returns Unit
        val before = AiSettings(
            aiEnabled = true,
            dashboardBriefingEnabled = true,
            proactiveBriefingsEnabled = true,
            transactionInsightEnabled = false
        )

        viewModel.setTransactionInsightEnabled(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { aiSettingsRepository.update(any()) }
        assertEquals(before.copy(transactionInsightEnabled = true), transform.captured(before))
        assertNull(viewModel.uiState.value.settingsWriteError)
    }

    @Test
    fun `setTransactionInsightEnabled false clears the flag and leaves briefing settings untouched`() = runTest(testDispatcher) {
        val transform = slot<(AiSettings) -> AiSettings>()
        coEvery { aiSettingsRepository.update(capture(transform)) } returns Unit
        val before = AiSettings(
            dashboardBriefingEnabled = true,
            proactiveBriefingsEnabled = true,
            transactionInsightEnabled = true
        )

        viewModel.setTransactionInsightEnabled(false)
        advanceUntilIdle()

        assertEquals(before.copy(transactionInsightEnabled = false), transform.captured(before))
    }

    // AE-32: insight toggling never arms or disarms the daily briefing work.
    @Test
    fun `setTransactionInsightEnabled never invokes SyncProactiveBriefingWorkUseCase`() = runTest(testDispatcher) {
        viewModel.setTransactionInsightEnabled(true)
        advanceUntilIdle()
        viewModel.setTransactionInsightEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 2) { aiSettingsRepository.update(any()) }
        coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
    }

    // INV-2/3: bounded error, class name only in logs, no work sync.
    @Test
    fun `setTransactionInsightEnabled write failure shows bounded resource and logs class name only`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { aiSettingsRepository.update(any()) } throws IOException(rawPath)

            viewModel.setTransactionInsightEnabled(true)
            advanceUntilIdle()

            val error = viewModel.uiState.value.settingsWriteError
            assertEquals(UiText.StringResource(R.string.ai_error_settings_save_failed), error)
            assertTrue((error as UiText.StringResource).args.isEmpty())
            assertTrue(tree.throwables.isEmpty())
            assertTrue(tree.messages.any { it.contains("IOException") })
            assertTrue(tree.messages.none { it.contains(rawPath) || it.contains("datastore") })
            coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
        }
    }

    // INV-4: cancellation is not a settings error.
    @Test
    fun `setTransactionInsightEnabled cancellation propagates and sets no settings error`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { aiSettingsRepository.update(any()) } throws CancellationException("cancelled")

            viewModel.setTransactionInsightEnabled(true)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.settingsWriteError)
            assertTrue(tree.messages.none { it.contains("write failed") })
            coVerify(exactly = 0) { syncProactiveBriefingWorkUseCase(any()) }
        }
    }

    // B2e: the insight row needs a runtime status, appended last (the use case excludes it from highestPriorityMessage).
    @Test
    fun `runtime capability list ends with TRANSACTION_INSIGHT`() = runTest(testDispatcher) {
        val requested = mutableListOf<List<AiCapability>>()
        coEvery { getAiRuntimeStatusUseCase(capture(requested)) } returns AiRuntimeStatusSummary(
            capabilities = emptyList(),
            highestPriorityMessage = null,
            networkAvailable = true,
            wifiConnected = true,
            lastRefreshedAt = 1L
        )

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertTrue(requested.isNotEmpty())
        requested.forEach { capabilities ->
            assertEquals(AiCapability.TRANSACTION_INSIGHT, capabilities.last())
            assertEquals(1, capabilities.count { it == AiCapability.TRANSACTION_INSIGHT })
        }
    }

    @Test
    fun `clearSettingsWriteError clears the localized error`() = runTest(testDispatcher) {
        coEvery { aiSettingsRepository.update(any()) } throws IOException(rawPath)
        viewModel.setAllowCloudAi(true)
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_error_settings_save_failed), viewModel.uiState.value.settingsWriteError)

        viewModel.clearSettingsWriteError()

        assertNull(viewModel.uiState.value.settingsWriteError)
    }

    // AE-5 (VM half: a failing status refresh surfaces no exception text anywhere in UI state or logs)
    @Test
    fun `runtime refresh failure keeps exception text out of ui state and logs`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { getAiRuntimeStatusUseCase(any()) } throws RuntimeException("token=abc123")

            viewModel.refreshRuntimeStatus()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isRefreshingRuntime)
            assertNull(state.runtimeSummary.highestPriorityMessage)
            assertFalse(state.toString().contains("abc123"))
            assertTrue(tree.throwables.isEmpty())
            assertTrue(tree.messages.none { it.contains("abc123") })
            assertTrue(tree.messages.any { it.contains("RuntimeException") })
        }
    }

    @Test
    fun `runtime refresh cancellation propagates and clears refreshing flag without logging`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            coEvery { getAiRuntimeStatusUseCase(any()) } throws CancellationException("cancelled")

            viewModel.refreshRuntimeStatus()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRefreshingRuntime)
            assertTrue(tree.messages.none { it.contains("Runtime refresh failed") })
        }
    }

    // AE-2 closed set: cloud-blocked reasons are string resources, one per cause
    @Test
    fun `effective cloud blocked reasons are string resources`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = false)
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_blocked_ai_off), viewModel.uiState.value.effectiveCloudBlocked)

        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = false)
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_blocked_cloud_off), viewModel.uiState.value.effectiveCloudBlocked)

        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true)
        every { secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI) } returns false
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_blocked_no_key), viewModel.uiState.value.effectiveCloudBlocked)

        every { secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI) } returns true
        coEvery { getAiRuntimeStatusUseCase(any()) } returns offlineSummary(networkAvailable = false, wifiConnected = false)
        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_blocked_no_network), viewModel.uiState.value.effectiveCloudBlocked)

        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true, wifiOnlyForCloud = true)
        coEvery { getAiRuntimeStatusUseCase(any()) } returns offlineSummary(networkAvailable = true, wifiConnected = false)
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_blocked_wifi_only), viewModel.uiState.value.effectiveCloudBlocked)
    }

    // AE-2 closed set: connection-test failures are string resources and never carry exception text
    @Test
    fun `testConnection unexpected failure shows localized resource and logs class name only`() = runTest(testDispatcher) {
        withCapturedLogs { tree ->
            settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true)
            coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } throws IllegalStateException("token=abc123")
            advanceUntilIdle()

            viewModel.updateApiKeyInput("AIza12345678901234567890")
            viewModel.testConnection()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(UiText.StringResource(R.string.ai_test_failed), state.connectionTestMessage)
            assertEquals(false, state.isConnectionTestSuccess)
            assertFalse(state.isTestingConnection)
            assertTrue(tree.throwables.isEmpty())
            assertTrue(tree.messages.none { it.contains("abc123") })
            assertTrue(tree.messages.any { it.contains("IllegalStateException") })
        }
    }

    // AE-4: cancellation during the connection test is not turned into a failure message
    @Test
    fun `testConnection cancellation propagates and is not converted to a failure message`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true)
        coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } throws CancellationException("cancelled")
        advanceUntilIdle()

        viewModel.updateApiKeyInput("AIza12345678901234567890")
        viewModel.testConnection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.connectionTestMessage)
        assertNull(state.isConnectionTestSuccess)
        assertFalse(state.isTestingConnection)
    }

    @Test
    fun `testConnection runtime failures map to string resources`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = false)
        advanceUntilIdle()
        viewModel.updateApiKeyInput("AIza12345678901234567890")
        viewModel.testConnection()
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_key_enable_cloud_to_test), viewModel.uiState.value.connectionTestMessage)

        settingsFlow.value = AiSettings(aiEnabled = true, allowCloudAi = true)
        coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } returns
            offlineSummary(networkAvailable = false, wifiConnected = false)
        advanceUntilIdle()
        viewModel.testConnection()
        advanceUntilIdle()
        assertEquals(UiText.StringResource(R.string.ai_test_no_network), viewModel.uiState.value.connectionTestMessage)
        assertEquals(false, viewModel.uiState.value.isConnectionTestSuccess)
    }

    @Test
    fun `api key validation and removal messages are string resources`() = runTest(testDispatcher) {
        viewModel.updateApiKeyInput("short")
        assertEquals(UiText.StringResource(R.string.ai_key_too_short), viewModel.uiState.value.apiKeyValidationMessage)

        viewModel.updateApiKeyInput("BBBB5678901234567890123")
        assertEquals(UiText.StringResource(R.string.ai_key_format_invalid), viewModel.uiState.value.apiKeyValidationMessage)

        viewModel.removeApiKey()
        assertEquals(UiText.StringResource(R.string.ai_key_removed), viewModel.uiState.value.connectionTestMessage)
        assertNull(viewModel.uiState.value.apiKeyValidationMessage)
    }

    // ai-fixes B3b (G2-10 / UX-Q-3): state read only; surfaced at the existing refresh points.
    @Test
    fun `notificationsBlocked is false when the checker reports notifications enabled`() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.notificationsBlocked)
    }

    @Test
    fun `notificationsBlocked surfaces when the checker returns false`() = runTest(testDispatcher) {
        every { notificationPermissionChecker.areNotificationsEnabled() } returns false

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.notificationsBlocked)
    }

    @Test
    fun `refresh re-reads notification state in both directions`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.notificationsBlocked)

        every { notificationPermissionChecker.areNotificationsEnabled() } returns false
        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.notificationsBlocked)

        every { notificationPermissionChecker.areNotificationsEnabled() } returns true
        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.notificationsBlocked)
    }

    @Test
    fun `settings emission re-reads notification state through the existing refresh point`() = runTest(testDispatcher) {
        advanceUntilIdle()
        every { notificationPermissionChecker.areNotificationsEnabled() } returns false

        settingsFlow.value = settingsFlow.value.copy(proactiveBriefingsEnabled = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.notificationsBlocked)
    }

    @Test
    fun `notification state read failure reports not blocked and does not break refresh`() = runTest(testDispatcher) {
        every { notificationPermissionChecker.areNotificationsEnabled() } throws IllegalStateException(rawPath)

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.notificationsBlocked)
        assertEquals(1234L, viewModel.uiState.value.runtimeSummary.lastRefreshedAt)
        assertFalse(viewModel.uiState.value.isRefreshingRuntime)
    }

    @Test
    fun `notification state is read only through areNotificationsEnabled`() = runTest(testDispatcher) {
        every { notificationPermissionChecker.areNotificationsEnabled() } returns false

        viewModel.refreshRuntimeStatus()
        advanceUntilIdle()

        verify(atLeast = 1) { notificationPermissionChecker.areNotificationsEnabled() }
        confirmVerified(notificationPermissionChecker)
    }

    private fun offlineSummary(networkAvailable: Boolean, wifiConnected: Boolean) = AiRuntimeStatusSummary(
        capabilities = emptyList(),
        highestPriorityMessage = null,
        networkAvailable = networkAvailable,
        wifiConnected = wifiConnected,
        lastRefreshedAt = 1L
    )
}
