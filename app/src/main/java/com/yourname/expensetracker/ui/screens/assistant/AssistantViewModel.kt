package com.yourname.expensetracker.ui.screens.assistant

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.routeDisplayText
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.InterpretFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.MapFinancialQueryToNavigationUseCase
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.ui.mappers.toUi
import com.yourname.expensetracker.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

data class AssistantUiState(
    val messages: List<AssistantConversationItem> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val isDisabled: Boolean = false,
    val disabledReason: String? = null,
    /** S3-011: typed privacy blocked state — shown as PrivacyBlockedCard when cloud AI is denied */
    val privacyBlocked: com.yourname.expensetracker.domain.privacy.PrivacyBlocked? = null,
    val runtimeStatusMessage: String? = null,
    val runtimeDiagnostics: String? = null,
    val errorMessage: String? = null,
    val canPersistHistory: Boolean = false,
    val currentSessionId: Long? = null,
    /** S11-020: true when clear-all-history confirmation dialog should be shown */
    val showClearHistoryConfirm: Boolean = false
)

private data class AssistantRuntimePresentation(
    val message: String?,
    val diagnostics: String?
)

sealed interface AssistantConversationItem {
    data class User(
        val id: String,
        val text: String
    ) : AssistantConversationItem

    data class Result(
        val id: String,
        val queryText: String,
        val result: FinancialQueryResult,
        val drilldownFilter: TransactionFilter? = null
    ) : AssistantConversationItem

    data class Error(
        val id: String,
        val text: String
    ) : AssistantConversationItem
}

sealed interface AssistantNavigationEvent {
    data class OpenTransactions(
        val filter: TransactionFilter
    ) : AssistantNavigationEvent
}

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val application: Application,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiChatRepository: AiChatRepository,
    private val getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase,
    private val interpretFinancialQueryUseCase: InterpretFinancialQueryUseCase,
    private val executeFinancialQueryUseCase: ExecuteFinancialQueryUseCase,
    private val mapFinancialQueryToNavigationUseCase: MapFinancialQueryToNavigationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _navigationEvents = Channel<AssistantNavigationEvent>(Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    private val _isSubmitting = MutableStateFlow(false)
    private var _currentQueryJob: Job? = null

    val settings = aiSettingsRepository.settings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = com.yourname.expensetracker.domain.ai.model.AiSettings()
    )

    init {
        viewModelScope.launch {
            aiSettingsRepository.settings().collect { settings ->
                val isDisabled = !settings.aiEnabled || !settings.assistantEnabled || !settings.queryInterpretationEnabled
                val disabledReason = when {
                    !settings.aiEnabled -> "AI is disabled"
                    !settings.assistantEnabled -> "Assistant is disabled"
                    !settings.queryInterpretationEnabled -> "Query interpretation is disabled"
                    else -> null
                }
                // S3-011: Expose typed privacy blocked state when cloud AI is the reason
                val privacyBlocked: com.yourname.expensetracker.domain.privacy.PrivacyBlocked? =
                    if (!settings.aiEnabled) com.yourname.expensetracker.domain.privacy.PrivacyBlocked.CloudAiDisabled()
                    else null
                // S11-013: Cancel in-flight query when settings disable AI
                if (isDisabled && _currentQueryJob?.isActive == true) {
                    cancelCurrentQuery()
                }
                val runtimePresentation = if (isDisabled) {
                    AssistantRuntimePresentation(message = null, diagnostics = null)
                } else {
                    buildRuntimePresentation(settings)
                }

                _uiState.value = _uiState.value.copy(
                    isDisabled = isDisabled,
                    disabledReason = disabledReason,
                    privacyBlocked = privacyBlocked,
                    canPersistHistory = settings.storeConversationHistory,
                    runtimeStatusMessage = runtimePresentation.message,
                    runtimeDiagnostics = runtimePresentation.diagnostics
                )
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.value = _uiState.value.copy(input = value, errorMessage = null)
    }

    /**
     * AID-10: Per-request diagnostics tracking.
     * Captures phase-level timing, routing info, and error details for each query.
     */
    private data class QueryDiagnostics(
        val query: String,
        val totalDurationMs: Long,
        val interpretationDurationMs: Long? = null,
        val executionDurationMs: Long? = null,
        val resultType: String? = null,
        val route: String? = null,
        val error: String? = null
    ) {
        fun toDisplayString(): String = buildString {
            // S11-009: Never expose raw query text in release builds
            if (com.yourname.expensetracker.BuildConfig.DEBUG) {
                appendLine("Query: ${query.take(50)}")
            }
            appendLine("Total: ${totalDurationMs}ms")
            interpretationDurationMs?.let { appendLine("Interpret: ${it}ms") }
            executionDurationMs?.let { appendLine("Execute: ${it}ms") }
            resultType?.let { appendLine("Result: $it") }
            route?.let { appendLine("Route: $it") }
            error?.let { appendLine("Error: $it") }
        }
    }

    fun submitQuery(rawQuery: String = _uiState.value.input) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        if (!_isSubmitting.compareAndSet(false, true)) return

        _currentQueryJob?.cancel()
        val queryStartedAt = System.currentTimeMillis()

        _currentQueryJob = viewModelScope.launch {
            var lastDiagnostics: QueryDiagnostics? = null
            try {
                val settings = aiSettingsRepository.settings().first()
                if (!settings.aiEnabled || !settings.assistantEnabled || !settings.queryInterpretationEnabled) {
                    _uiState.value = _uiState.value.copy(
                        isDisabled = true,
                        disabledReason = "Enable AI assistant to use this feature",
                        isLoading = false
                    )
                    return@launch
                }

                val userItem = AssistantConversationItem.User(
                    id = "user-${System.nanoTime()}",
                    text = query
                )

                val sessionId = ensureSessionIfNeeded()

                _uiState.value = _uiState.value.copy(
                    input = "",
                    isLoading = true,
                    errorMessage = null,
                    messages = _uiState.value.messages + userItem,
                    currentSessionId = sessionId ?: _uiState.value.currentSessionId
                )

                persistUserTurn(sessionId, query)

                val historyMessages = if (settings.storeConversationHistory && sessionId != null) {
                    val raw = aiChatRepository.observeMessages(sessionId).first()
                    // S11-019: Sanitize history for cloud — strip payloadJson under redaction,
                    // limit to last 10 turns to avoid stale/excessive context
                    val limited = raw.takeLast(10)
                    if (settings.redactBeforeCloud) {
                        limited.map { msg -> msg.copy(payloadJson = null) }
                    } else {
                        limited
                    }
                } else {
                    emptyList()
                }

                // AID-10: Capture interpretation timing
                val interpretationStart = System.currentTimeMillis()
                val interpretation = interpretFinancialQueryUseCase(query, historyMessages)
                val interpretationDuration = System.currentTimeMillis() - interpretationStart

                val diagnosticsBuilder = QueryDiagnostics(
                    query = query,
                    totalDurationMs = System.currentTimeMillis() - queryStartedAt,
                    interpretationDurationMs = interpretationDuration,
                    route = _uiState.value.runtimeDiagnostics
                )

                when (interpretation) {
                    is FinancialQueryInterpretationResult.Structured -> {
                        // AID-10: Capture execution timing
                        val execStart = System.currentTimeMillis()
                        val result = executeFinancialQueryUseCase(interpretation.intent)
                        val executionDuration = System.currentTimeMillis() - execStart
                        val navigationFilter = mapFinancialQueryToNavigationUseCase(interpretation.intent)?.toUi()
                        val resultItem = AssistantConversationItem.Result(
                            id = "result-${System.nanoTime()}",
                            queryText = query,
                            result = result,
                            drilldownFilter = navigationFilter
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = System.currentTimeMillis() - queryStartedAt,
                            executionDurationMs = executionDuration,
                            resultType = result::class.simpleName
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem
                        )
                        persistAssistantTurn(sessionId, result)
                    }

                    is FinancialQueryInterpretationResult.Clarification -> {
                        val result = FinancialQueryResult.Clarification(
                            prompt = interpretation.prompt,
                            options = interpretation.options
                        )
                        val resultItem = AssistantConversationItem.Result(
                            id = "clarification-${System.nanoTime()}",
                            queryText = query,
                            result = result
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = System.currentTimeMillis() - queryStartedAt,
                            resultType = "Clarification"
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem
                        )
                        persistAssistantTurn(sessionId, result)
                    }

                    is FinancialQueryInterpretationResult.Unsupported -> {
                        val result = FinancialQueryResult.Unsupported(interpretation.reason)
                        val resultItem = AssistantConversationItem.Result(
                            id = "unsupported-${System.nanoTime()}",
                            queryText = query,
                            result = result
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = System.currentTimeMillis() - queryStartedAt,
                            resultType = "Unsupported"
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem
                        )
                        persistAssistantTurn(sessionId, result)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Assistant query pipeline failed")
                val friendlyMessage = mapAssistantExceptionToUserMessage(e)
                lastDiagnostics = QueryDiagnostics(
                    query = query,
                    totalDurationMs = System.currentTimeMillis() - queryStartedAt,
                    error = "${e::class.simpleName}: ${e.message}"
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = friendlyMessage,
                    messages = _uiState.value.messages + AssistantConversationItem.Error(
                        id = "error-${System.nanoTime()}",
                        text = friendlyMessage
                    ),
                    input = if (_uiState.value.input.isBlank()) query else _uiState.value.input
                )
            } finally {
                // AID-10: Update runtime diagnostics with per-request details
                if (lastDiagnostics != null) {
                    _uiState.value = _uiState.value.copy(
                        runtimeDiagnostics = lastDiagnostics.toDisplayString()
                    )
                }
                if (_uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                _isSubmitting.value = false
                _currentQueryJob = null
            }
        }
    }

    fun retryLast() {
        val lastUser = _uiState.value.messages.lastOrNull { it is AssistantConversationItem.User } as? AssistantConversationItem.User
            ?: return
        submitQuery(lastUser.text)
    }

    fun cancelCurrentQuery() {
        _currentQueryJob?.cancel()
        _currentQueryJob = null
        _isSubmitting.value = false
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = "Query cancelled"
        )
    }

    fun onSuggestionSelected(text: String) {
        updateInput(text)
        submitQuery(text)
    }

    fun onClarificationSelected(text: String) {
        updateInput(text)
        submitQuery(text)
    }

    fun openDrilldown(filter: TransactionFilter?) {
        if (filter == null) return
        viewModelScope.launch {
            _navigationEvents.send(AssistantNavigationEvent.OpenTransactions(filter))
        }
    }

    fun clearSession() {
        cancelCurrentQuery()
        viewModelScope.launch {
            _uiState.value.currentSessionId?.let { sessionId ->
                aiChatRepository.clearSession(sessionId)
            }
            _uiState.value = _uiState.value.copy(
                messages = emptyList(),
                input = "",
                errorMessage = null,
                currentSessionId = null,
                isLoading = false
            )
        }
    }

    /** S11-020: Request confirmation before clearing all history */
    fun requestClearAllHistory() {
        _uiState.value = _uiState.value.copy(showClearHistoryConfirm = true)
    }

    fun dismissClearAllHistory() {
        _uiState.value = _uiState.value.copy(showClearHistoryConfirm = false)
    }

    fun clearAllHistory() {
        _uiState.value = _uiState.value.copy(showClearHistoryConfirm = false)
        cancelCurrentQuery()
        viewModelScope.launch {
            aiChatRepository.clearAllHistory()
            clearSession()
        }
    }

    private suspend fun ensureSessionIfNeeded(): Long? {
        val current = _uiState.value.currentSessionId
        if (current != null) return current

        val created = aiChatRepository.createSession(title = UiText.from(R.string.assistant_title).asString(application))
        if (created != null) {
            _uiState.value = _uiState.value.copy(currentSessionId = created)
        }
        return created
    }

    private suspend fun persistUserTurn(sessionId: Long?, query: String) {
        if (sessionId == null) return
        aiChatRepository.appendMessage(
            sessionId = sessionId,
            role = AssistantMessageRole.USER,
            kind = AssistantMessageKind.QUERY,
            text = query
        )
    }

    private suspend fun persistAssistantTurn(sessionId: Long?, result: FinancialQueryResult) {
        if (sessionId == null) return
        aiChatRepository.appendMessage(
            sessionId = sessionId,
            role = AssistantMessageRole.ASSISTANT,
            kind = when (result) {
                is FinancialQueryResult.Clarification -> AssistantMessageKind.CLARIFICATION
                is FinancialQueryResult.Unsupported -> AssistantMessageKind.ERROR
                else -> AssistantMessageKind.RESULT
            },
            text = result.toDisplayText(),
            payloadJson = result.toPayloadJson()
        )
    }

    private fun FinancialQueryResult.toDisplayText(): String = when (this) {
        is FinancialQueryResult.Summary -> listOfNotNull(title.asString(application), primaryText, supportingText).joinToString("\n")
        is FinancialQueryResult.Breakdown -> title.asString(application)
        is FinancialQueryResult.TransactionList -> "${title.asString(application)} ($previewCount)"
        is FinancialQueryResult.Clarification -> prompt
        is FinancialQueryResult.Unsupported -> reason
    }

    private fun FinancialQueryResult.toPayloadJson(): String? = when (this) {
        is FinancialQueryResult.Summary -> JSONObject()
            .put("type", "summary")
            .put("title", title.asString(application))
            .put("primaryText", primaryText)
            .put("supportingText", supportingText)
            .toString()

        is FinancialQueryResult.Breakdown -> JSONObject()
            .put("type", "breakdown")
            .put("title", title.asString(application))
            .put(
                "rows",
                JSONArray().apply {
                    rows.forEach { row ->
                        put(
                            JSONObject()
                                .put("label", row.label)
                                .put("amount", row.amount)
                                .put("count", row.count)
                                .put("valueText", row.valueText)
                        )
                    }
                }
            )
            .toString()

        is FinancialQueryResult.TransactionList -> JSONObject()
            .put("type", "transaction_list")
            .put("title", title.asString(application))
            .put("previewCount", previewCount)
            .toString()

        is FinancialQueryResult.Clarification -> JSONObject()
            .put("type", "clarification")
            .put("prompt", prompt)
            .put("options", JSONArray(options))
            .toString()

        is FinancialQueryResult.Unsupported -> JSONObject()
            .put("type", "unsupported")
            .put("reason", reason)
            .toString()
    }

    private suspend fun buildRuntimePresentation(
        settings: com.yourname.expensetracker.domain.ai.model.AiSettings
    ): AssistantRuntimePresentation {
        val runtime = getAiRuntimeStatusUseCase(
            capabilities = listOf(AiCapability.QUERY_INTERPRETATION)
        ).capabilities.firstOrNull()

        if (runtime == null) {
            return AssistantRuntimePresentation(message = null, diagnostics = null)
        }

        val message = runtimeAssistantMessage(settings, runtime)
        return AssistantRuntimePresentation(
            message = message,
            diagnostics = runtime.routeDisplayText()
        )
    }

    private fun mapAssistantExceptionToUserMessage(error: Throwable): String {
        val details = error.message?.lowercase().orEmpty()
        return when {
            details.contains("timeout") -> "The assistant took too long to respond. Please try again."
            details.contains("network") || details.contains("offline") || details.contains("connection") ->
                "Couldn’t reach AI right now. Check your connection and retry."
            details.contains("ssl") || details.contains("certificate") || details.contains("secure") ->
                "Secure connection failed. Please try again in a moment."
            else -> "Something went wrong while handling your request. Please retry."
        }
    }
}

private fun runtimeAssistantMessage(
    settings: com.yourname.expensetracker.domain.ai.model.AiSettings,
    runtime: AiCapabilityRuntimeStatus
): String? {
    if (!settings.allowOnDeviceAi && runtime.route == AiRoute.CLOUD) {
        return null
    }

    return when (runtime.route) {
        AiRoute.CLOUD,
        AiRoute.ON_DEVICE -> null
        AiRoute.DETERMINISTIC_FALLBACK,
        AiRoute.DISABLED,
        null -> runtime.message
    }
}
