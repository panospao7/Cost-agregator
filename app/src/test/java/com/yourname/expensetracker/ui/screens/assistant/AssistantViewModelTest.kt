package com.yourname.expensetracker.ui.screens.assistant

import android.app.Application
import app.cash.turbine.test
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.InterpretFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.MapFinancialQueryToNavigationUseCase
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest : ViewModelTestUtils() {

    private lateinit var application: Application
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiChatRepository: AiChatRepository
    private lateinit var getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase
    private lateinit var interpretFinancialQueryUseCase: InterpretFinancialQueryUseCase
    private lateinit var executeFinancialQueryUseCase: ExecuteFinancialQueryUseCase
    private lateinit var mapFinancialQueryToNavigationUseCase: MapFinancialQueryToNavigationUseCase
    private lateinit var viewModel: AssistantViewModel

    @Before
    override fun setup() {
        super.setup()
        application = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiChatRepository = mockk(relaxed = true)
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        interpretFinancialQueryUseCase = mockk(relaxed = true)
        executeFinancialQueryUseCase = mockk(relaxed = true)
        mapFinancialQueryToNavigationUseCase = mockk(relaxed = true)

        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                assistantEnabled = true,
                queryInterpretationEnabled = true,
                storeConversationHistory = false
            )
        )
        coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } returns runtimeSummary()

        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )
    }

    @Test
    fun `uiState reflects disabled mode when assistant disabled`() = runTest(testDispatcher) {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = false, assistantEnabled = false, queryInterpretationEnabled = false)
        )

        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDisabled)
        assertNotNull(viewModel.uiState.value.disabledReason)
    }

    @Test
    fun `uiState shows runtime status when on-device model not installed`() = runTest(testDispatcher) {
        coEvery { getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION)) } returns runtimeSummary(
            route = AiRoute.DETERMINISTIC_FALLBACK,
            message = "On-device model is not installed on this device.",
            actionLabel = "Install required"
        )

        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )

        advanceUntilIdle()

        assertEquals(
            "On-device model is not installed on this device.",
            viewModel.uiState.value.runtimeStatusMessage
        )
        assertEquals("Deterministic fallback", viewModel.uiState.value.runtimeDiagnostics)
    }

    @Test
    fun `uiState hides runtime warning and shows cloud diagnostics when cloud is allowed in auto mode`() = runTest(testDispatcher) {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                assistantEnabled = true,
                queryInterpretationEnabled = true,
                allowCloudAi = true,
                allowOnDeviceAi = true
            )
        )
        coEvery { getAiRuntimeStatusUseCase(any()) } returns runtimeSummary(
            route = AiRoute.CLOUD,
            providerName = "google-ai-studio",
            modelName = "gemini-2.5-flash"
        )

        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )

        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.runtimeStatusMessage)
        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", viewModel.uiState.value.runtimeDiagnostics)
    }

    @Test
    fun `uiState hides diagnostics when runtime data is missing`() = runTest(testDispatcher) {
        coEvery { getAiRuntimeStatusUseCase(any()) } returns AiRuntimeStatusSummary(
            capabilities = emptyList(),
            highestPriorityMessage = null
        )

        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )

        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.runtimeStatusMessage)
        assertEquals(null, viewModel.uiState.value.runtimeDiagnostics)
    }

    @Test
    fun `submitQuery ignores blank input`() = runTest(testDispatcher) {
        viewModel.submitQuery("   ")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun `submitQuery adds loading and final summary result`() = runTest(testDispatcher) {
        val intent = FinancialQueryIntent(
            rawQuery = "total this month",
            normalizedQuery = "total this month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns FinancialQueryInterpretationResult.Structured(intent)
        coEvery { executeFinancialQueryUseCase(intent) } returns FinancialQueryResult.Summary(
            title = UiText.DynamicString("Total spending"),
            primaryText = "42.00 EUR"
        )
        every { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("total this month")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertTrue(viewModel.uiState.value.messages[0] is AssistantConversationItem.User)
        assertTrue(viewModel.uiState.value.messages[1] is AssistantConversationItem.Result)
    }

    @Test
    fun `submitQuery handles clarification result`() = runTest(testDispatcher) {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns FinancialQueryInterpretationResult.Clarification(
            prompt = "Which month?",
            options = listOf("This month", "Last month")
        )

        viewModel.submitQuery("compare spending")
        advanceUntilIdle()

        val resultItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
        assertTrue(resultItem.result is FinancialQueryResult.Clarification)
    }

    @Test
    fun `submitQuery handles unsupported result`() = runTest(testDispatcher) {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns FinancialQueryInterpretationResult.Unsupported(
            "Unsupported query"
        )

        viewModel.submitQuery("do something unsupported")
        advanceUntilIdle()

        val resultItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
        assertTrue(resultItem.result is FinancialQueryResult.Unsupported)
    }

    @Test
    fun `submitQuery creates no session and persists nothing when history disabled`() = runTest(testDispatcher) {
        val intent = FinancialQueryIntent(
            rawQuery = "total this month",
            normalizedQuery = "total this month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns FinancialQueryInterpretationResult.Structured(intent)
        coEvery { executeFinancialQueryUseCase(intent) } returns FinancialQueryResult.Summary(
            title = UiText.DynamicString("Total spending"),
            primaryText = "42.00 EUR"
        )
        every { mapFinancialQueryToNavigationUseCase(intent) } returns null
        coEvery { aiChatRepository.createSession(any()) } returns null

        viewModel.submitQuery("total this month")
        advanceUntilIdle()

        coVerify { aiChatRepository.createSession(any()) }
        coVerify(exactly = 0) { aiChatRepository.appendMessage(any(), any(), any(), any(), any()) }
        assertEquals(null, viewModel.uiState.value.currentSessionId)
    }

    @Test
    fun `submitQuery persists when history enabled`() = runTest(testDispatcher) {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                assistantEnabled = true,
                queryInterpretationEnabled = true,
                storeConversationHistory = true
            )
        )
        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            mapFinancialQueryToNavigationUseCase
        )

        val intent = FinancialQueryIntent(
            rawQuery = "total this month",
            normalizedQuery = "total this month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )
        coEvery { aiChatRepository.createSession(any()) } returns 5L
        every { aiChatRepository.observeMessages(5L) } returns flowOf(emptyList())
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns FinancialQueryInterpretationResult.Structured(intent)
        coEvery { executeFinancialQueryUseCase(intent) } returns FinancialQueryResult.Summary(
            title = UiText.DynamicString("Total spending"),
            primaryText = "42.00 EUR"
        )
        every { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("total this month")
        advanceUntilIdle()

        coVerify { aiChatRepository.appendMessage(5L, AssistantMessageRole.USER, AssistantMessageKind.QUERY, any(), null) }
        coVerify { aiChatRepository.appendMessage(5L, AssistantMessageRole.ASSISTANT, AssistantMessageKind.RESULT, any(), any()) }
        assertEquals(5L, viewModel.uiState.value.currentSessionId)
    }

    @Test
    fun `openDrilldown emits navigation event`() = runTest(testDispatcher) {
        val filter = TransactionFilter(merchantName = "Lidl")

        viewModel.navigationEvents.test {
            viewModel.openDrilldown(filter)
            advanceUntilIdle()
            assertEquals(AssistantNavigationEvent.OpenTransactions(filter), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearSession resets ui state`() = runTest(testDispatcher) {
        viewModel.updateInput("hello")
        viewModel.clearSession()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.input)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    private fun runtimeSummary(
        route: AiRoute = AiRoute.ON_DEVICE,
        message: String? = null,
        actionLabel: String? = null,
        providerName: String? = null,
        modelName: String? = null
    ) = AiRuntimeStatusSummary(
        capabilities = listOf(
            AiCapabilityRuntimeStatus(
                capability = AiCapability.QUERY_INTERPRETATION,
                status = OnDeviceModelStatus.AVAILABLE,
                message = message,
                actionLabel = actionLabel,
                route = route,
                providerName = providerName,
                modelName = modelName
            )
        ),
        highestPriorityMessage = message
    )
}
