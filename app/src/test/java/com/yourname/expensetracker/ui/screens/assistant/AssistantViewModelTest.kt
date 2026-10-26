package com.yourname.expensetracker.ui.screens.assistant

import android.app.Application
import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AnswerMode
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.ClarificationCompletion
import com.yourname.expensetracker.domain.ai.model.ClarificationSlot
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import com.yourname.expensetracker.domain.ai.model.PresentationChoice
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.assertNull
import timber.log.Timber
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CompleteFinancialQueryClarificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.InterpretFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.MapFinancialQueryToNavigationUseCase
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
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
    private lateinit var completeFinancialQueryClarificationUseCase: CompleteFinancialQueryClarificationUseCase
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
        completeFinancialQueryClarificationUseCase = CompleteFinancialQueryClarificationUseCase(FakeTimeProvider())

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
            completeFinancialQueryClarificationUseCase,
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
            completeFinancialQueryClarificationUseCase,
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
            completeFinancialQueryClarificationUseCase,
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
            completeFinancialQueryClarificationUseCase,
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
            completeFinancialQueryClarificationUseCase,
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
            AssistantUnsupportedReason.METRIC_NOT_SUPPORTED
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
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.METRIC_NOT_SUPPORTED)
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
            completeFinancialQueryClarificationUseCase,
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

    @Test
    fun `AE-C9 INV-C6 executor NO_MATCHING_TRANSACTIONS persists localized text and code-only payload`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useHistoryEnabledViewModel()
            val store = stubSessionStore(emptyList())
            val localizedMessage = "No matching transactions found."
            every {
                application.getString(R.string.assistant_unsupported_no_matching_transactions)
            } returns localizedMessage
            val intent = FinancialQueryIntent(
                rawQuery = "largest purchase last week",
                normalizedQuery = "largest purchase last week",
                filters = ExpenseQueryFilters(),
                metric = QueryMetric.MAX
            )
            coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                FinancialQueryInterpretationResult.Structured(intent)
            coEvery { executeFinancialQueryUseCase(intent) } returns
                FinancialQueryResult.Unsupported(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS)
            coEvery { mapFinancialQueryToNavigationUseCase(intent) } returns null

            viewModel.submitQuery("largest purchase last week")
            advanceUntilIdle()

            // Rendered state carries the bounded code.
            val resultItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
            assertEquals(
                FinancialQueryResult.Unsupported(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS),
                resultItem.result
            )

            // Persisted ASSISTANT row: kind ERROR, localized text resolved from the code's resource.
            coVerify(exactly = 1) {
                aiChatRepository.appendMessage(
                    5L, AssistantMessageRole.ASSISTANT, AssistantMessageKind.ERROR, localizedMessage, any()
                )
            }
            verify { application.getString(R.string.assistant_unsupported_no_matching_transactions) }
            val assistantRow = store.last()
            assertEquals(AssistantMessageRole.ASSISTANT, assistantRow.role)
            assertEquals(AssistantMessageKind.ERROR, assistantRow.kind)
            assertEquals(localizedMessage, assistantRow.text)

            // Payload is {"type":"unsupported","reason":"NO_MATCHING_TRANSACTIONS"} and nothing else.
            val payload = JSONObject(assistantRow.payloadJson!!)
            assertEquals(setOf("type", "reason"), payload.keys().asSequence().toSet())
            assertEquals("unsupported", payload.getString("type"))
            assertEquals("NO_MATCHING_TRANSACTIONS", payload.getString("reason"))
            assertFalse(assistantRow.payloadJson!!.contains(localizedMessage))
            assertFalse(assistantRow.payloadJson!!.contains("largest purchase"))
        }

    @Test
    fun `AE-C9 INV-C6 interpretation unsupported persists code name only and never the log diagnostic`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useHistoryEnabledViewModel()
            val store = stubSessionStore(emptyList())
            every {
                application.getString(R.string.assistant_unsupported_response_invalid)
            } returns "I could not understand the assistant response."
            coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                FinancialQueryInterpretationResult.Unsupported(
                    reason = AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID,
                    diagnostic = DiagnosticReasonCode.UNKNOWN_ERROR
                )

            viewModel.submitQuery("something odd")
            advanceUntilIdle()

            val assistantRow = store.last()
            assertEquals(AssistantMessageRole.ASSISTANT, assistantRow.role)
            assertEquals(AssistantMessageKind.ERROR, assistantRow.kind)
            assertEquals("I could not understand the assistant response.", assistantRow.text)
            val payload = JSONObject(assistantRow.payloadJson!!)
            assertEquals(setOf("type", "reason"), payload.keys().asSequence().toSet())
            assertEquals("unsupported", payload.getString("type"))
            assertEquals("PROVIDER_RESPONSE_INVALID", payload.getString("reason"))
            assertFalse(assistantRow.payloadJson!!.contains("UNKNOWN_ERROR"))
        }

    @Test
    fun `INV-C6 every unsupported code persists its own code name and a message from its own resource`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useHistoryEnabledViewModel()
            // Independent expectation table: code -> its dedicated string resource.
            val expectedResources = mapOf(
                AssistantUnsupportedReason.INTERPRETATION_DISABLED to R.string.assistant_unsupported_interpretation_disabled,
                AssistantUnsupportedReason.INTERPRETATION_FAILED to R.string.assistant_unsupported_interpretation_failed,
                AssistantUnsupportedReason.PROVIDER_UNAVAILABLE to R.string.assistant_unsupported_provider_unavailable,
                AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID to R.string.assistant_unsupported_response_invalid,
                AssistantUnsupportedReason.MODEL_DECLINED to R.string.assistant_unsupported_model_declined,
                AssistantUnsupportedReason.METRIC_NOT_SUPPORTED to R.string.assistant_unsupported_metric_not_supported,
                AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS to R.string.assistant_unsupported_no_matching_transactions
            )
            assertEquals(AssistantUnsupportedReason.values().toSet(), expectedResources.keys)
            expectedResources.forEach { (reason, resId) ->
                every { application.getString(resId) } returns "message-for-${reason.name}"
            }

            expectedResources.keys.forEach { reason ->
                val store = stubSessionStore(emptyList())
                coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                    FinancialQueryInterpretationResult.Unsupported(reason)

                viewModel.submitQuery("query for ${reason.name}")
                advanceUntilIdle()

                val assistantRow = store.last()
                assertEquals("message-for-${reason.name}", assistantRow.text)
                assertEquals(reason.name, JSONObject(assistantRow.payloadJson!!).getString("reason"))
            }
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
            completeFinancialQueryClarificationUseCase,
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
            completeFinancialQueryClarificationUseCase,
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

    // ---- ai-fixes Part C, C4: structured clarification completion (INV-C13..C16; AE-C20..AE-C26) ----

    /** Captures the formatted Timber messages so tests can assert the bounded expiry token and the absence of option text. */
    private class ClarificationLogTree : Timber.Tree() {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    private var clarificationLogTree: ClarificationLogTree? = null

    /** Every intent the mocked executor received, in call order. */
    private val executedIntents = mutableListOf<FinancialQueryIntent>()

    @After
    fun uprootClarificationLogTree() {
        clarificationLogTree?.let { Timber.uproot(it) }
        clarificationLogTree = null
    }

    private fun captureClarificationLogs(): ClarificationLogTree {
        val tree = ClarificationLogTree()
        Timber.plant(tree)
        clarificationLogTree = tree
        return tree
    }

    /** Tap-time clock: 2026-10-15 12:00 in the system zone. */
    private val clarificationNowMs: Long = FakeTimeProvider.forDate(2026, 10, 15, 12, 0).now()

    private fun startOfDayMillis(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Base intent of the period clarification. Non-default values everywhere so any dropped field is visible. */
    private val foodIntent = FinancialQueryIntent(
        rawQuery = "how much on food",
        normalizedQuery = "how much on food",
        filters = ExpenseQueryFilters(
            categoryIds = setOf(1L),
            merchants = setOf("Lidl"),
            transactionTypes = setOf(DomainTransactionType.PURCHASE),
            ownership = QueryOwnershipScope.MINE,
            minAmount = 5.0,
            currency = "EUR"
        ),
        metric = QueryMetric.TOTAL,
        answerMode = AnswerMode.INLINE_ANSWER
    )

    /** Base intent of the presentation clarification (October 2026, AVERAGE per merchant). */
    private val merchantAverageIntent = FinancialQueryIntent(
        rawQuery = "average spend per merchant this month",
        normalizedQuery = "average spend per merchant this month",
        filters = ExpenseQueryFilters(
            period = PeriodRange(start = startOfDayMillis(2026, 10, 1), end = startOfDayMillis(2026, 11, 1)),
            periodGranularity = PeriodGranularity.MONTH,
            transactionTypes = setOf(DomainTransactionType.PURCHASE)
        ),
        metric = QueryMetric.AVERAGE,
        grouping = QueryGrouping.MERCHANT
    )

    private val periodChoices: List<ClarificationChoice> = listOf(
        ClarificationChoice.Period(PeriodChoice.THIS_MONTH),
        ClarificationChoice.Period(PeriodChoice.THIS_WEEK),
        ClarificationChoice.Period(PeriodChoice.LAST_MONTH)
    )

    private val presentationChoices: List<ClarificationChoice> = listOf(
        ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
        ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST)
    )

    private fun periodClarification(base: FinancialQueryIntent = foodIntent) = FinancialQueryResult.Clarification(
        prompt = "Which period?",
        options = emptyList(),
        completion = ClarificationCompletion(base, ClarificationSlot.PERIOD, periodChoices)
    )

    private fun presentationClarification(base: FinancialQueryIntent) = FinancialQueryResult.Clarification(
        prompt = "How should I show this?",
        options = emptyList(),
        completion = ClarificationCompletion(base, ClarificationSlot.PRESENTATION, presentationChoices)
    )

    private fun totalSummary() = FinancialQueryResult.Summary(
        title = UiText.DynamicString("Food total"),
        primaryText = "10.00 EUR"
    )

    private fun indexOfLastMonth(): Int = periodChoices.indexOf(ClarificationChoice.Period(PeriodChoice.LAST_MONTH))

    private fun enabledSettings(storeHistory: Boolean = false) = AiSettings(
        aiEnabled = true,
        assistantEnabled = true,
        queryInterpretationEnabled = true,
        storeConversationHistory = storeHistory
    )

    private fun stubClarificationLabels() {
        every { application.getString(R.string.assistant_clarification_period_this_month) } returns "Label:this-month"
        every { application.getString(R.string.assistant_clarification_period_this_week) } returns "Label:this-week"
        every { application.getString(R.string.assistant_clarification_period_last_month) } returns "Label:last-month"
        every { application.getString(R.string.assistant_clarification_presentation_breakdown) } returns "Label:breakdown"
        every { application.getString(R.string.assistant_clarification_presentation_transaction_list) } returns "Label:transaction-list"
    }

    /** Rebuilds the VM with a completer whose clock is [clarificationNowMs] and with the clarification labels stubbed. */
    private fun useClarificationViewModel(settingsFlow: Flow<AiSettings> = flowOf(enabledSettings())) {
        completeFinancialQueryClarificationUseCase =
            CompleteFinancialQueryClarificationUseCase(FakeTimeProvider(clarificationNowMs))
        every { aiSettingsRepository.settings() } returns settingsFlow
        coEvery { mapFinancialQueryToNavigationUseCase(any()) } returns null
        coEvery { aiChatRepository.createSession(any()) } returns null
        stubClarificationLabels()
        executedIntents.clear()
        viewModel = AssistantViewModel(
            application,
            aiSettingsRepository,
            aiChatRepository,
            getAiRuntimeStatusUseCase,
            interpretFinancialQueryUseCase,
            executeFinancialQueryUseCase,
            completeFinancialQueryClarificationUseCase,
            mapFinancialQueryToNavigationUseCase,
            monotonicTimeProvider
        )
    }

    /** Stubs the executor; records every received intent. [handler] may throw to simulate failures or cancellation. */
    private fun stubExecutor(handler: (FinancialQueryIntent) -> FinancialQueryResult) {
        coEvery { executeFinancialQueryUseCase(any()) } coAnswers {
            val intent = firstArg<FinancialQueryIntent>()
            executedIntents += intent
            handler(intent)
        }
    }

    /**
     * Submits "how much on food": the interpreter yields [foodIntent] and the executor yields the period
     * clarification, which becomes the live pending clarification. Returns its item id.
     */
    private fun submitPeriodClarification(
        completedResult: () -> FinancialQueryResult = { totalSummary() }
    ): String {
        coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
            FinancialQueryInterpretationResult.Structured(foodIntent)
        stubExecutor { intent -> if (intent == foodIntent) periodClarification() else completedResult() }
        viewModel.submitQuery("how much on food")
        testDispatcher.scheduler.advanceUntilIdle()
        return liveClarificationId()
    }

    private fun liveClarificationId(): String {
        val pending = viewModel.uiState.value.livePendingClarification
        assertNotNull("a structured clarification must be live", pending)
        return pending!!.first
    }

    private fun userItems() =
        viewModel.uiState.value.messages.filterIsInstance<AssistantConversationItem.User>()

    private fun errorItems() =
        viewModel.uiState.value.messages.filterIsInstance<AssistantConversationItem.Error>()

    @Test
    fun `AE-C20 INV-C13 tapping Last month completes the Food intent without re-interpretation`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()
            val clarificationItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
            assertEquals(clarificationId, clarificationItem.id)

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // The interpreter ran for the initial submit only.
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }

            // The executor saw the base intent, then the completed one: Food filter kept, period = last month.
            assertEquals(2, executedIntents.size)
            val completed = executedIntents[1]
            assertEquals(startOfDayMillis(2026, 9, 1), completed.filters.period!!.start)
            assertEquals(startOfDayMillis(2026, 10, 1), completed.filters.period!!.end)
            assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
            assertEquals(setOf(1L), completed.filters.categoryIds)
            // Every other field equals the base intent byte for byte.
            assertEquals(
                foodIntent,
                completed.copy(filters = completed.filters.copy(period = null, periodGranularity = null))
            )

            // Transcript: User(query), Result(clarification), User(label, completedIntent), Result(summary).
            val messages = viewModel.uiState.value.messages
            assertEquals(4, messages.size)
            val tapUser = messages[2] as AssistantConversationItem.User
            assertEquals("Label:last-month", tapUser.text)
            assertEquals(completed, tapUser.completedIntent)
            val resultItem = messages[3] as AssistantConversationItem.Result
            assertEquals(totalSummary(), resultItem.result)
            assertEquals("Label:last-month", resultItem.queryText)

            // Consumed, and the summary carries no completion.
            assertNull(viewModel.uiState.value.livePendingClarification)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `AE-C20 INV-C13 tapping This month resolves the current month at tap time and keeps the Food intent`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()

            viewModel.onClarificationOptionSelected(clarificationId, 0)
            advanceUntilIdle()

            val completed = executedIntents[1]
            assertEquals(startOfDayMillis(2026, 10, 1), completed.filters.period!!.start)
            assertEquals(startOfDayMillis(2026, 11, 1), completed.filters.period!!.end)
            assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
            assertEquals(
                foodIntent,
                completed.copy(filters = completed.filters.copy(period = null, periodGranularity = null))
            )
            assertEquals("Label:this-month", userItems().last().text)
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
        }

    @Test
    fun `AE-C21 INV-C13 presentation clarification Breakdown tap executes TOTAL keeping grouping and period`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                FinancialQueryInterpretationResult.Clarification(
                    prompt = "How should I show this?",
                    completion = presentationClarification(merchantAverageIntent).completion
                )
            stubExecutor { totalSummary() }
            viewModel.submitQuery("average spend per merchant this month")
            advanceUntilIdle()
            val clarificationId = liveClarificationId()

            viewModel.onClarificationOptionSelected(
                clarificationId,
                presentationChoices.indexOf(ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN))
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertEquals(listOf(merchantAverageIntent.copy(metric = QueryMetric.TOTAL)), executedIntents)
            val tapUser = userItems().last()
            assertEquals("Label:breakdown", tapUser.text)
            assertEquals(executedIntents.single(), tapUser.completedIntent)
            assertNull(viewModel.uiState.value.livePendingClarification)
        }

    @Test
    fun `AE-C21 INV-C13 presentation clarification Transaction list tap executes LIST with no grouping`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                FinancialQueryInterpretationResult.Clarification(
                    prompt = "How should I show this?",
                    completion = presentationClarification(merchantAverageIntent).completion
                )
            stubExecutor { totalSummary() }
            viewModel.submitQuery("average spend per merchant this month")
            advanceUntilIdle()
            val clarificationId = liveClarificationId()

            viewModel.onClarificationOptionSelected(
                clarificationId,
                presentationChoices.indexOf(ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST))
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertEquals(
                listOf(merchantAverageIntent.copy(metric = QueryMetric.LIST, grouping = QueryGrouping.NONE)),
                executedIntents
            )
            assertEquals("Label:transaction-list", userItems().last().text)
            assertNull(viewModel.uiState.value.livePendingClarification)
        }

    @Test
    fun `AE-C22 INV-C14 a new submitQuery expires the old clarification and a tap on it is a logged no-op`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            val oldId = submitPeriodClarification()

            coEvery { interpretFinancialQueryUseCase(any(), any()) } returns
                FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.METRIC_NOT_SUPPORTED)
            viewModel.submitQuery("something else entirely")
            advanceUntilIdle()
            assertNotEquals(oldId, viewModel.uiState.value.livePendingClarification?.first)
            assertNull(viewModel.uiState.value.livePendingClarification)
            val messagesBeforeTap = viewModel.uiState.value.messages

            viewModel.onClarificationOptionSelected(oldId, indexOfLastMonth())
            advanceUntilIdle()

            // No interpreter call beyond the two submits, no executor call beyond the first base execution.
            coVerify(exactly = 2) { interpretFinancialQueryUseCase(any(), any()) }
            assertEquals(listOf(foodIntent), executedIntents)
            assertEquals(messagesBeforeTap, viewModel.uiState.value.messages)
            assertNotEquals(oldId, viewModel.uiState.value.livePendingClarification?.first)

            // Bounded log token only; never option text.
            assertTrue(logs.messages.any { it.contains("CLARIFICATION_EXPIRED") })
            assertTrue(logs.messages.none { it.contains("Label:") })
            assertTrue(logs.messages.none { it.contains("last month", ignoreCase = true) })
        }

    @Test
    fun `AE-C22 INV-C14 a tap on an unknown item id is a logged no-op and leaves the live clarification usable`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            val liveId = submitPeriodClarification()
            val pendingBefore = viewModel.uiState.value.livePendingClarification

            viewModel.onClarificationOptionSelected("clarification-not-a-real-item", indexOfLastMonth())
            advanceUntilIdle()

            assertTrue(logs.messages.any { it.contains("CLARIFICATION_EXPIRED") })
            assertEquals(listOf(foodIntent), executedIntents)
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertEquals(pendingBefore, viewModel.uiState.value.livePendingClarification)

            // Control: the live card still completes normally afterwards.
            viewModel.onClarificationOptionSelected(liveId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(2, executedIntents.size)
        }

    @Test
    fun `AE-C22 INV-C14 an out-of-range option index on the live card executes nothing`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val liveId = submitPeriodClarification()

            viewModel.onClarificationOptionSelected(liveId, periodChoices.size)
            viewModel.onClarificationOptionSelected(liveId, -1)
            advanceUntilIdle()

            assertEquals(listOf(foodIntent), executedIntents)
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertTrue(userItems().none { it.completedIntent != null })
        }

    @Test
    fun `AE-C23 INV-C14 a double tap executes the completion once`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()

            // Both taps arrive before the dispatcher runs the first completion.
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // Base execution plus exactly one completion.
            assertEquals(2, executedIntents.size)
            assertEquals(1, userItems().count { it.completedIntent != null })
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
        }

    @Test
    fun `AE-C23 INV-C14 a tap after the clarification was consumed is a logged no-op`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            val clarificationId = submitPeriodClarification()

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(2, executedIntents.size)
            assertTrue(logs.messages.none { it.contains("CLARIFICATION_EXPIRED") })

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            assertEquals(2, executedIntents.size)
            assertEquals(1, userItems().count { it.completedIntent != null })
            assertTrue(logs.messages.any { it.contains("CLARIFICATION_EXPIRED") })
        }

    @Test
    fun `AE-C24 INV-C15 a model clarification without completion keeps the legacy text resubmission`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            val answerIntent = foodIntent.copy(rawQuery = "This month", normalizedQuery = "this month")
            coEvery { interpretFinancialQueryUseCase(any(), any()) } returnsMany listOf(
                FinancialQueryInterpretationResult.Clarification(
                    prompt = "Which month?",
                    options = listOf("This month", "Last month")
                ),
                FinancialQueryInterpretationResult.Structured(answerIntent)
            )
            stubExecutor { totalSummary() }

            viewModel.submitQuery("compare spending")
            advanceUntilIdle()

            // A model clarification never becomes a live structured clarification.
            val cardItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
            assertNull((cardItem.result as FinancialQueryResult.Clarification).completion)
            assertNull(viewModel.uiState.value.livePendingClarification)

            // The structured tap API does nothing for it.
            viewModel.onClarificationOptionSelected(cardItem.id, 0)
            advanceUntilIdle()
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertTrue(executedIntents.isEmpty())
            assertTrue(logs.messages.any { it.contains("CLARIFICATION_EXPIRED") })

            // The legacy text path resubmits the option text as a new query.
            viewModel.onClarificationSelected("This month")
            advanceUntilIdle()

            coVerify(exactly = 1) { interpretFinancialQueryUseCase("This month", any()) }
            assertEquals(listOf(answerIntent), executedIntents)
            assertEquals(listOf("compare spending", "This month"), userItems().map { it.text })
            assertTrue(userItems().all { it.completedIntent == null })
        }

    @Test
    fun `AE-C25 INV-C16 retry after an executor IOException re-executes the same completed intent without interpretation`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            var completedCalls = 0
            val clarificationId = submitPeriodClarification(completedResult = {
                completedCalls++
                if (completedCalls == 1) throw IOException("synthetic failure at /secret/path/receipt.jpg")
                totalSummary()
            })

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // Bounded Error item, no raw exception text, pending not revived, completedIntent kept for retry.
            val errorItem = viewModel.uiState.value.messages.last() as AssistantConversationItem.Error
            assertFalse(errorItem.text.contains("secret"))
            assertNull(viewModel.uiState.value.livePendingClarification)
            val tapUser = userItems().last()
            assertEquals(executedIntents[1], tapUser.completedIntent)
            assertEquals("Label:last-month", tapUser.text)

            viewModel.retryLast()
            advanceUntilIdle()

            // Base + failed completion + retried completion (same intent); interpreter only ever ran once.
            assertEquals(3, executedIntents.size)
            assertEquals(executedIntents[1], executedIntents[2])
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            val last = viewModel.uiState.value.messages.last() as AssistantConversationItem.Result
            assertEquals(totalSummary(), last.result)
            assertNull(viewModel.uiState.value.livePendingClarification)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `INV-C16 retryLast on a plain query still re-interprets the query text`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            var interpretations = 0
            coEvery { interpretFinancialQueryUseCase(any(), any()) } coAnswers {
                interpretations++
                if (interpretations == 1) throw RuntimeException("synthetic")
                FinancialQueryInterpretationResult.Structured(foodIntent.copy(rawQuery = "legacy retry query"))
            }
            stubExecutor { totalSummary() }

            viewModel.submitQuery("legacy retry query")
            advanceUntilIdle()
            assertEquals(1, errorItems().size)

            viewModel.retryLast()
            advanceUntilIdle()

            coVerify(exactly = 2) { interpretFinancialQueryUseCase("legacy retry query", any()) }
            assertTrue(userItems().all { it.completedIntent == null })
            assertTrue(viewModel.uiState.value.messages.last() is AssistantConversationItem.Result)
        }

    @Test
    fun `AE-C26 INV-C16 INV-4 cancellation during the completion adds no Error item and leaves retry possible`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            var completedCalls = 0
            val clarificationId = submitPeriodClarification(completedResult = {
                completedCalls++
                if (completedCalls == 1) throw CancellationException("synthetic cancellation")
                totalSummary()
            })

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // Cancellation is not a failure: no Error item, no error message, no pipeline-failure log.
            assertTrue(errorItems().isEmpty())
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(logs.messages.none { it.contains("pipeline failed") })
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.livePendingClarification)

            // The completed intent stays on the User item, and the VM is not wedged in the submitting state.
            val tapUser = userItems().last()
            assertEquals(executedIntents[1], tapUser.completedIntent)
            assertFalse(getPrivateSubmittingFlow().value)

            viewModel.retryLast()
            advanceUntilIdle()

            assertEquals(3, executedIntents.size)
            assertEquals(executedIntents[1], executedIntents[2])
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertTrue(viewModel.uiState.value.messages.last() is AssistantConversationItem.Result)
        }

    @Test
    fun `INV-C14 a tap while a query is submitting is ignored and the pending clarification stays`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()
            val pendingBefore = viewModel.uiState.value.livePendingClarification
            val messagesBefore = viewModel.uiState.value.messages

            getPrivateSubmittingFlow().value = true
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            assertEquals(listOf(foodIntent), executedIntents)
            assertEquals(pendingBefore, viewModel.uiState.value.livePendingClarification)
            assertEquals(messagesBefore, viewModel.uiState.value.messages)

            // Control: once the in-flight query is done the same tap completes.
            getPrivateSubmittingFlow().value = false
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            assertEquals(2, executedIntents.size)
            assertNull(viewModel.uiState.value.livePendingClarification)
        }

    @Test
    fun `INV-C14 a tap while the assistant is disabled executes nothing and leaves no pending clarification`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            val settingsFlow = MutableStateFlow(enabledSettings())
            useClarificationViewModel(settingsFlow)
            val clarificationId = submitPeriodClarification()

            settingsFlow.value = AiSettings(
                aiEnabled = false,
                assistantEnabled = false,
                queryInterpretationEnabled = false
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isDisabled)

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            assertEquals(listOf(foodIntent), executedIntents)
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }
            assertNull(viewModel.uiState.value.livePendingClarification)
            assertTrue(viewModel.uiState.value.isDisabled)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `INV-C14 clearSession clears the pending clarification`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()

            viewModel.clearSession()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.livePendingClarification)
            assertTrue(viewModel.uiState.value.messages.isEmpty())
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(listOf(foodIntent), executedIntents)
        }

    @Test
    fun `INV-C14 clearAllHistory clears the pending clarification`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val clarificationId = submitPeriodClarification()

            viewModel.clearAllHistory()
            advanceUntilIdle()

            coVerify(exactly = 1) { aiChatRepository.clearAllHistory() }
            assertNull(viewModel.uiState.value.livePendingClarification)
            assertTrue(viewModel.uiState.value.messages.isEmpty())
            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(listOf(foodIntent), executedIntents)
        }

    @Test
    fun `INV-C14 a later Result carrying a new completion replaces the pending clarification`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val firstId = submitPeriodClarification(completedResult = { presentationClarification(foodIntent) })
            val secondBase = foodIntent

            viewModel.onClarificationOptionSelected(firstId, indexOfLastMonth())
            advanceUntilIdle()

            val pending = viewModel.uiState.value.livePendingClarification
            assertNotNull(pending)
            assertNotEquals(firstId, pending!!.first)
            assertEquals(ClarificationSlot.PRESENTATION, pending.second.slot)
            assertEquals(secondBase, pending.second.baseIntent)

            // The first card is expired now.
            viewModel.onClarificationOptionSelected(firstId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(2, executedIntents.size)
        }

    @Test
    fun `INV-C14 a later Error item clears the pending clarification`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val oldId = submitPeriodClarification()

            var interpretations = 0
            coEvery { interpretFinancialQueryUseCase(any(), any()) } coAnswers {
                interpretations++
                throw RuntimeException("synthetic failure")
            }
            viewModel.submitQuery("this will fail")
            advanceUntilIdle()

            assertEquals(1, errorItems().size)
            assertEquals(1, interpretations)
            assertNull(viewModel.uiState.value.livePendingClarification)
            viewModel.onClarificationOptionSelected(oldId, indexOfLastMonth())
            advanceUntilIdle()
            assertEquals(listOf(foodIntent), executedIntents)
        }

    @Test
    fun `INV-C14 pending state is never persisted and persisted clarification payload keeps only the legacy keys`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel(flowOf(enabledSettings(storeHistory = true)))
            val store = stubSessionStore(emptyList())
            val clarificationId = submitPeriodClarification()

            // The ASSISTANT clarification row written for the live card.
            val clarificationRow = store.last()
            assertEquals(AssistantMessageRole.ASSISTANT, clarificationRow.role)
            assertEquals(AssistantMessageKind.CLARIFICATION, clarificationRow.kind)
            assertEquals("Which period?", clarificationRow.text)
            val payload = JSONObject(clarificationRow.payloadJson!!)
            assertEquals(setOf("type", "clarificationPrompt", "options"), payload.keys().asSequence().toSet())
            assertEquals("clarification", payload.getString("type"))
            assertEquals("Which period?", payload.getString("clarificationPrompt"))
            assertEquals(0, payload.getJSONArray("options").length())

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // The tap is persisted as a plain USER query turn with the localized label and no payload.
            assertEquals(
                listOf(
                    AssistantMessageRole.USER to AssistantMessageKind.QUERY,
                    AssistantMessageRole.ASSISTANT to AssistantMessageKind.CLARIFICATION,
                    AssistantMessageRole.USER to AssistantMessageKind.QUERY,
                    AssistantMessageRole.ASSISTANT to AssistantMessageKind.RESULT
                ),
                store.map { it.role to it.kind }
            )
            assertEquals("Label:last-month", store[2].text)
            assertNull(store[2].payloadJson)

            // Nothing about the pending intent or its choices reaches any persisted row.
            val forbiddenFragments = listOf(
                "completion", "baseIntent", "choices", "completedIntent",
                "THIS_MONTH", "THIS_WEEK", "LAST_MONTH", "Lidl", "categoryIds"
            )
            store.forEach { row ->
                forbiddenFragments.forEach { fragment ->
                    assertFalse("row text must not contain $fragment", row.text.contains(fragment))
                    assertFalse("row payload must not contain $fragment", row.payloadJson.orEmpty().contains(fragment))
                }
            }
            // The tap read no history: only the initial submit snapshotted the session.
            verify(exactly = 1) { aiChatRepository.observeMessages(any()) }
        }

    @Test
    fun `C4 lockup a throwing label lookup leaves the assistant usable and pending intact`() =
        runTest(testDispatcher, timeout = 30.seconds) {
            useClarificationViewModel()
            val logs = captureClarificationLogs()
            val clarificationId = submitPeriodClarification()
            val pendingBefore = viewModel.uiState.value.livePendingClarification
            val messagesBefore = viewModel.uiState.value.messages

            // The label lookup for one choice fails with a non-cancellation exception.
            every { application.getString(R.string.assistant_clarification_period_last_month) } throws
                IllegalStateException("synthetic label lookup failure")

            viewModel.onClarificationOptionSelected(clarificationId, indexOfLastMonth())
            advanceUntilIdle()

            // Nothing executed; pending is live and unchanged; the submit guard was never taken.
            assertEquals(listOf(foodIntent), executedIntents)
            assertEquals(pendingBefore, viewModel.uiState.value.livePendingClarification)
            assertEquals(clarificationId, liveClarificationId())
            assertEquals(messagesBefore, viewModel.uiState.value.messages)
            assertFalse(getPrivateSubmittingFlow().value)
            assertFalse(viewModel.uiState.value.isLoading)
            coVerify(exactly = 1) { interpretFinancialQueryUseCase(any(), any()) }

            // Bounded reason code only; never option text or the exception message.
            assertTrue(logs.messages.any { it.contains("CLARIFICATION_COMPLETION_UNAVAILABLE") })
            assertTrue(logs.messages.none { it.contains("Label:") })
            assertTrue(logs.messages.none { it.contains("last month", ignoreCase = true) })
            assertTrue(logs.messages.none { it.contains("synthetic label lookup failure") })

            // Control: a valid option on the same live card still executes.
            viewModel.onClarificationOptionSelected(clarificationId, 0)
            advanceUntilIdle()

            assertEquals(2, executedIntents.size)
            assertEquals(startOfDayMillis(2026, 10, 1), executedIntents[1].filters.period!!.start)
            assertEquals("Label:this-month", userItems().last().text)
            assertNull(viewModel.uiState.value.livePendingClarification)
            assertFalse(getPrivateSubmittingFlow().value)
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
