package com.yourname.expensetracker.ui.screens.assistant

import android.app.Application
import app.cash.turbine.test
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
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
import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.*
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private lateinit var monotonicTimeProvider: FakeMonotonicTimeProvider
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
        monotonicTimeProvider = FakeMonotonicTimeProvider()

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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("total this month")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertTrue(viewModel.uiState.value.messages[0] is AssistantConversationItem.User)
        assertTrue(viewModel.uiState.value.messages[1] is AssistantConversationItem.Result)
    }

    @Test
    fun `submitQuery populates runtime diagnostics with timing and without user payloads`() = runTest(testDispatcher) {
        val intent = FinancialQueryIntent(
            rawQuery = "total this month",
            normalizedQuery = "total this month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )
        coEvery { interpretFinancialQueryUseCase(any(), any()) } coAnswers {
            monotonicTimeProvider.advanceMillis(5)
            FinancialQueryInterpretationResult.Structured(intent)
        }
        coEvery { executeFinancialQueryUseCase(intent) } coAnswers {
            monotonicTimeProvider.advanceMillis(3)
            FinancialQueryResult.Summary(
                title = UiText.DynamicString("Total spending"),
                primaryText = "42.00 EUR"
            )
        }
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

        // S11-009/T1: submit a deliberately sensitive query — diagnostics must
        // never contain any user query text in any build.
        val sensitiveQuery = "SENSITIVE-QUERY-CARD-4111111111111111-SECRET"
        viewModel.submitQuery(sensitiveQuery)
        advanceUntilIdle()

        val diagnostics = viewModel.uiState.value.runtimeDiagnostics
        assertNotNull("runtime diagnostics must be populated after a query", diagnostics)
        assertTrue("diagnostics must contain total timing", diagnostics!!.contains("Total: 8ms"))
        assertTrue("diagnostics must contain interpretation timing", diagnostics.contains("Interpret: 5ms"))
        assertTrue("diagnostics must contain execution timing", diagnostics.contains("Execute: 3ms"))
        assertTrue("diagnostics must contain the result type", diagnostics.contains("Result:"))
        assertFalse("diagnostics must never contain user query text", diagnostics.contains(sensitiveQuery))
        assertFalse("diagnostics must not contain a query fragment", diagnostics.contains("4111111111111111"))
        assertFalse("diagnostics must not leak the result payload amount", diagnostics.contains("42.00 EUR"))
        assertFalse("diagnostics must not leak the result payload title", diagnostics.contains("Total spending"))
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
    fun `submitQuery generates non-empty unique ids for user and result items`() = runTest(testDispatcher) {
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
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("first query")
        advanceUntilIdle()
        viewModel.submitQuery("second query")
        advanceUntilIdle()

        val ids = viewModel.uiState.value.messages.map { (it as? AssistantConversationItem.User)?.id ?: (it as AssistantConversationItem.Result).id }
        assertEquals(4, ids.size)
        assertTrue("ids must be non-empty", ids.all { it.isNotEmpty() })
        assertTrue("ids must be unique", ids.toSet().size == ids.size)
        assertPrefixedUuid(ids[0], "user-")
        assertPrefixedUuid(ids[1], "result-")
        assertPrefixedUuid(ids[2], "user-")
        assertPrefixedUuid(ids[3], "result-")
    }

    @Test
    fun `submitQuery generates non-empty unique ids for clarification and unsupported items`() = runTest(testDispatcher) {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returnsMany listOf(
            FinancialQueryInterpretationResult.Clarification(
                prompt = "Which month?",
                options = listOf("This month", "Last month")
            ),
            FinancialQueryInterpretationResult.Unsupported("Unsupported query")
        )

        viewModel.submitQuery("first query")
        advanceUntilIdle()
        viewModel.submitQuery("second query")
        advanceUntilIdle()

        val ids = viewModel.uiState.value.messages.map { (it as? AssistantConversationItem.User)?.id ?: (it as AssistantConversationItem.Result).id }
        assertEquals(4, ids.size)
        assertTrue("ids must be non-empty", ids.all { it.isNotEmpty() })
        assertTrue("ids must be unique", ids.toSet().size == ids.size)
        assertPrefixedUuid(ids[1], "clarification-")
        assertPrefixedUuid(ids[3], "unsupported-")
    }

    @Test
    fun `submitQuery generates non-empty unique id for error item`() = runTest(testDispatcher) {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } throws RuntimeException("boom")

        viewModel.submitQuery("failing query")
        advanceUntilIdle()

        val userItem = viewModel.uiState.value.messages[0] as AssistantConversationItem.User
        val errorItem = viewModel.uiState.value.messages[1] as AssistantConversationItem.Error
        assertTrue(userItem.id.isNotEmpty())
        assertTrue(errorItem.id.isNotEmpty())
        assertNotEquals(userItem.id, errorItem.id)
        assertPrefixedUuid(userItem.id, "user-")
        assertPrefixedUuid(errorItem.id, "error-")
    }

    @Test
    fun `submitQuery failure surfaces fixed friendly message and sanitized diagnostics without raw exception text`() = runTest(testDispatcher) {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } coAnswers {
            monotonicTimeProvider.advanceMillis(5)
            throw RuntimeException("boom with sensitive payload: 42.00 EUR")
        }

        // S11-009/T1: submit a deliberately sensitive query on the failure path —
        // diagnostics must never contain user query text in any build.
        val sensitiveQuery = "SENSITIVE-QUERY-CARD-4111111111111111-SECRET"
        viewModel.submitQuery(sensitiveQuery)
        advanceUntilIdle()

        val errorItem = viewModel.uiState.value.messages[1] as AssistantConversationItem.Error
        assertEquals("Something went wrong while handling your request. Please retry.", viewModel.uiState.value.errorMessage)
        assertEquals("Something went wrong while handling your request. Please retry.", errorItem.text)
        assertFalse("error message must not leak raw exception text", viewModel.uiState.value.errorMessage?.contains("boom") == true)
        assertFalse("error item text must not leak raw exception text", errorItem.text.contains("sensitive payload"))
        assertFalse("error message must never contain the sensitive query", viewModel.uiState.value.errorMessage?.contains(sensitiveQuery) == true)
        assertFalse("error item text must never contain the sensitive query", errorItem.text.contains(sensitiveQuery))
        assertFalse("error message must never contain a card fragment", viewModel.uiState.value.errorMessage?.contains("4111111111111111") == true)
        assertFalse("error item text must never contain a card fragment", errorItem.text.contains("4111111111111111"))

        val diagnostics = viewModel.uiState.value.runtimeDiagnostics
        assertNotNull("runtime diagnostics must be populated after a failure", diagnostics)
        assertTrue("diagnostics must contain a controlled reason code", diagnostics!!.contains("UNKNOWN_ERROR"))
        assertTrue("diagnostics may contain the exception class name", diagnostics.contains("RuntimeException"))
        assertTrue("diagnostics must contain total timing", diagnostics.contains("Total: 5ms"))
        assertFalse("diagnostics must not leak raw exception message", diagnostics.contains("boom"))
        assertFalse("diagnostics must not leak user payload text", diagnostics.contains("42.00 EUR"))
        assertFalse("diagnostics must never contain user query text", diagnostics.contains(sensitiveQuery))
        assertFalse("diagnostics must not contain a card fragment", diagnostics.contains("4111111111111111"))
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
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
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
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("total this month")
        advanceUntilIdle()

        coVerify { aiChatRepository.appendMessage(5L, AssistantMessageRole.USER, AssistantMessageKind.QUERY, any(), null) }
        coVerify { aiChatRepository.appendMessage(5L, AssistantMessageRole.ASSISTANT, AssistantMessageKind.RESULT, any(), any()) }
        assertEquals(5L, viewModel.uiState.value.currentSessionId)
    }

    // PC-7: the VM-level "strip payloadJson under privacy redaction" test was removed because the VM
    // no longer shapes history; the builder owns it (see FinancialQueryInterpretationInputBuilderTest
    // AE-C1 / AE-C4 coverage).

    @Test
    fun `AE-C1 INV-C3 submitQuery passes the full unfiltered prior snapshot and excludes the current query`() = runTest(testDispatcher) {
        useHistoryEnabledViewModel()
        val seeded = seededSessionRows()
        val store = stubSessionStore(seeded)
        val capturedHistories = stubStructuredInterpretation()

        viewModel.submitQuery("transport last month")
        advanceUntilIdle()

        val history = capturedHistories.single()
        assertEquals(12, history.size)
        assertEquals(seeded, history)
        assertTrue(history.any { it.role == AssistantMessageRole.ASSISTANT })
        assertTrue(history.any { it.role == AssistantMessageRole.USER })
        assertTrue(history.any { it.payloadJson != null })
        assertTrue(history.none { it.text == "transport last month" })
        // The snapshot was taken before persistence: the store has since grown, the snapshot has not.
        assertEquals(14, store.size)
    }

    @Test
    fun `AE-C5 INV-C3 submitQuery passes empty history when storeConversationHistory is false`() = runTest(testDispatcher) {
        // setUp settings: storeConversationHistory = false. A session id exists but must not be read.
        val seeded = seededSessionRows()
        stubSessionStore(seeded)
        val capturedHistories = stubStructuredInterpretation()

        viewModel.submitQuery("transport last month")
        advanceUntilIdle()

        assertTrue(capturedHistories.single().isEmpty())
        verify(exactly = 0) { aiChatRepository.observeMessages(any()) }
    }

    @Test
    fun `AE-C5 INV-C3 submitQuery passes empty history when history is enabled but no session exists`() = runTest(testDispatcher) {
        useHistoryEnabledViewModel()
        coEvery { aiChatRepository.createSession(any()) } returns null
        val capturedHistories = stubStructuredInterpretation(createSession = false)

        viewModel.submitQuery("transport last month")
        advanceUntilIdle()

        assertTrue(capturedHistories.single().isEmpty())
        verify(exactly = 0) { aiChatRepository.observeMessages(any()) }
    }

    @Test
    fun `AE-C6 INV-C4 submitQuery persists exactly one USER and one ASSISTANT row`() = runTest(testDispatcher) {
        useHistoryEnabledViewModel()
        val store = stubSessionStore(seededSessionRows())
        stubStructuredInterpretation()

        viewModel.submitQuery("transport last month")
        advanceUntilIdle()

        coVerify(exactly = 2) { aiChatRepository.appendMessage(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            aiChatRepository.appendMessage(
                5L, AssistantMessageRole.USER, AssistantMessageKind.QUERY, "transport last month", null
            )
        }
        coVerify(exactly = 1) {
            aiChatRepository.appendMessage(5L, AssistantMessageRole.ASSISTANT, AssistantMessageKind.RESULT, any(), any())
        }
        val added = store.drop(12)
        assertEquals(listOf(AssistantMessageRole.USER, AssistantMessageRole.ASSISTANT), added.map { it.role })
        assertEquals("transport last month", added[0].text)
        assertEquals(null, added[0].payloadJson)
        assertEquals("Total spending\n42.00 EUR", added[1].text)
        val payload = added[1].payloadJson
        assertNotNull(payload)
        assertTrue(payload!!.contains("\"type\":\"summary\""))
        assertTrue(payload.contains("42.00 EUR"))
    }

    private fun useHistoryEnabledViewModel() {
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
        )
    }

    /** Session 5 with 6 USER and 6 ASSISTANT rows (AE-C1 Given, scaled to 12 rows). */
    private fun seededSessionRows(): List<AiChatMessage> = (1..12).map { index ->
        val isUser = index % 2 == 1
        AiChatMessage(
            id = index.toLong(),
            sessionId = 5L,
            role = if (isUser) AssistantMessageRole.USER else AssistantMessageRole.ASSISTANT,
            kind = if (isUser) AssistantMessageKind.QUERY else AssistantMessageKind.RESULT,
            text = if (isUser) "user query $index" else "Total spending\n123.45 EUR (7)",
            payloadJson = if (isUser) null else "{\"type\":\"summary\",\"primaryText\":\"123.45 EUR\"}",
            createdAt = 100L + index
        )
    }

    /**
     * Backs session 5 with an in-memory store: observeMessages returns a cold flow whose snapshot
     * is taken at collection time (like Room) and appendMessage appends to the store, so a snapshot taken after persistence would
     * visibly contain the current query.
     */
    private fun stubSessionStore(seed: List<AiChatMessage>): MutableList<AiChatMessage> {
        val store = seed.toMutableList()
        coEvery { aiChatRepository.createSession(any()) } returns 5L
        every { aiChatRepository.observeMessages(5L) } answers { flow { emit(store.toList()) } }
        coEvery { aiChatRepository.appendMessage(any(), any(), any(), any(), any()) } coAnswers {
            store += AiChatMessage(
                id = store.size + 1L,
                sessionId = firstArg(),
                role = secondArg(),
                kind = thirdArg(),
                text = arg(3),
                payloadJson = arg(4),
                createdAt = 1_000L + store.size
            )
            store.size.toLong()
        }
        return store
    }

    private fun stubStructuredInterpretation(createSession: Boolean = true): MutableList<List<AiChatMessage>> {
        val capturedHistories = mutableListOf<List<AiChatMessage>>()
        val intent = FinancialQueryIntent(
            rawQuery = "transport last month",
            normalizedQuery = "transport last month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )
        if (createSession) {
            coEvery { aiChatRepository.createSession(any()) } returns 5L
        }
        coEvery { interpretFinancialQueryUseCase(any(), capture(capturedHistories)) } returns
            FinancialQueryInterpretationResult.Structured(intent)
        coEvery { executeFinancialQueryUseCase(intent) } returns FinancialQueryResult.Summary(
            title = UiText.DynamicString("Total spending"),
            primaryText = "42.00 EUR"
        )
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null
        return capturedHistories
    }

    @Test
    fun `clarification reply keeps conversation history when history enabled`() = runTest(testDispatcher) {
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
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
        )

        val expectedHistory = listOf(
            AiChatMessage(
                id = 1L,
                sessionId = 5L,
                role = AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "compare spending",
                createdAt = 100L
            ),
            AiChatMessage(
                id = 2L,
                sessionId = 5L,
                role = AssistantMessageRole.ASSISTANT,
                kind = AssistantMessageKind.CLARIFICATION,
                text = "Which month?",
                createdAt = 101L
            )
        )
        val capturedHistories = mutableListOf<List<AiChatMessage>>()
        val intent = FinancialQueryIntent(
            rawQuery = "This month",
            normalizedQuery = "this month",
            filters = ExpenseQueryFilters(),
            metric = QueryMetric.TOTAL
        )

        coEvery { aiChatRepository.createSession(any()) } returns 5L
        every { aiChatRepository.observeMessages(5L) } returns flowOf(expectedHistory)
        coEvery { interpretFinancialQueryUseCase(any(), capture(capturedHistories)) } returnsMany listOf(
            FinancialQueryInterpretationResult.Clarification(
                prompt = "Which month?",
                options = listOf("This month", "Last month")
            ),
            FinancialQueryInterpretationResult.Structured(intent)
        )
        coEvery { executeFinancialQueryUseCase(intent) } returns FinancialQueryResult.Summary(
            title = UiText.DynamicString("Total spending"),
            primaryText = "42.00 EUR"
        )
        coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

        viewModel.submitQuery("compare spending")
        advanceUntilIdle()
        viewModel.onClarificationSelected("This month")
        advanceUntilIdle()

        assertEquals(2, capturedHistories.size)
        assertEquals(expectedHistory, capturedHistories[1])
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

    @Test
    fun `clearSession cancels in-flight query job`() = runTest(testDispatcher) {
        val activeJob = backgroundScope.launch { delay(Long.MAX_VALUE) }
        setPrivateCurrentQueryJob(activeJob)
        getPrivateSubmittingFlow().value = true

        viewModel.clearSession()
        advanceUntilIdle()

        assertTrue(activeJob.isCancelled)
        assertFalse(getPrivateSubmittingFlow().value)
    }

    @Test
    fun `clearAllHistory cancels in-flight query job`() = runTest(testDispatcher) {
        val activeJob = backgroundScope.launch { delay(Long.MAX_VALUE) }
        setPrivateCurrentQueryJob(activeJob)
        getPrivateSubmittingFlow().value = true

        viewModel.clearAllHistory()
        advanceUntilIdle()

        assertTrue(activeJob.isCancelled)
        assertFalse(getPrivateSubmittingFlow().value)
        coVerify { aiChatRepository.clearAllHistory() }
    }

    @Test
    fun `clearSession with active session clears repository session and resets state`() = runTest(testDispatcher) {
        setPrivateUiState(
            AssistantUiState(
                messages = listOf(AssistantConversationItem.User(id = "u1", text = "hello")),
                input = "draft",
                isLoading = true,
                errorMessage = "old",
                currentSessionId = 77L
            )
        )

        viewModel.clearSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { aiChatRepository.clearSession(77L) }
        assertEquals(null, viewModel.uiState.value.currentSessionId)
        assertEquals("", viewModel.uiState.value.input)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `clearSession blocked by write barrier keeps session and sets bounded error message`() = runTest(testDispatcher) {
        every { application.getString(com.yourname.expensetracker.R.string.ui_write_blocked_maintenance) } returns "maintenance"
        coEvery { aiChatRepository.clearSession(any()) } throws
            com.yourname.expensetracker.ui.util.blockedWriteException()
        setPrivateUiState(
            AssistantUiState(
                messages = listOf(AssistantConversationItem.User(id = "u1", text = "hello")),
                currentSessionId = 77L
            )
        )

        viewModel.clearSession()
        advanceUntilIdle()

        assertEquals(77L, viewModel.uiState.value.currentSessionId)
        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals("maintenance", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `clearSession with null session does not call repository clearSession`() = runTest(testDispatcher) {
        setPrivateUiState(AssistantUiState(currentSessionId = null))

        viewModel.clearSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { aiChatRepository.clearSession(any()) }
        assertEquals(null, viewModel.uiState.value.currentSessionId)
    }

    @Test
    fun `clearAllHistory clears history and active session`() = runTest(testDispatcher) {
        setPrivateUiState(
            AssistantUiState(
                currentSessionId = 99L,
                messages = listOf(AssistantConversationItem.User(id = "u2", text = "q"))
            )
        )

        viewModel.clearAllHistory()
        advanceUntilIdle()

        coVerify(exactly = 1) { aiChatRepository.clearAllHistory() }
        coVerify(exactly = 1) { aiChatRepository.clearSession(99L) }
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals(null, viewModel.uiState.value.currentSessionId)
    }

    @Test
    fun `concurrent clearSession calls keep state consistent`() = runTest(testDispatcher) {
        setPrivateUiState(
            AssistantUiState(
                currentSessionId = 321L,
                input = "hello",
                messages = listOf(AssistantConversationItem.User(id = "u3", text = "x"))
            )
        )

        backgroundScope.launch { viewModel.clearSession() }
        backgroundScope.launch { viewModel.clearSession() }
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.currentSessionId)
        assertEquals("", viewModel.uiState.value.input)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `concurrent clearAllHistory calls keep state consistent`() = runTest(testDispatcher) {
        setPrivateUiState(
            AssistantUiState(
                currentSessionId = 654L,
                input = "pending",
                messages = listOf(AssistantConversationItem.User(id = "u4", text = "y"))
            )
        )

        backgroundScope.launch { viewModel.clearAllHistory() }
        backgroundScope.launch { viewModel.clearAllHistory() }
        advanceUntilIdle()

        coVerify(atLeast = 1) { aiChatRepository.clearAllHistory() }
        assertEquals(null, viewModel.uiState.value.currentSessionId)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals("", viewModel.uiState.value.input)
    }

    private fun setPrivateCurrentQueryJob(job: Job?) {
        val field = AssistantViewModel::class.java.getDeclaredField("_currentQueryJob")
        field.isAccessible = true
        field.set(viewModel, job)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getPrivateSubmittingFlow(): MutableStateFlow<Boolean> {
        val field = AssistantViewModel::class.java.getDeclaredField("_isSubmitting")
        field.isAccessible = true
        return field.get(viewModel) as MutableStateFlow<Boolean>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPrivateUiState(state: AssistantUiState) {
        val field = AssistantViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(viewModel) as MutableStateFlow<AssistantUiState>
        flow.value = state
    }

    private fun assertPrefixedUuid(id: String, prefix: String) {
        assertTrue("id must start with '$prefix'", id.startsWith(prefix))
        val suffix = id.removePrefix(prefix)
        assertTrue("suffix after '$prefix' must be non-empty", suffix.isNotEmpty())
        assertNotNull("suffix after '$prefix' must be a valid UUID", UUID.fromString(suffix))
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
