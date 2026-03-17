package com.yourname.expensetracker.ui.screens.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
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
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

data class AssistantUiState(
    val messages: List<AssistantConversationItem> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val isDisabled: Boolean = false,
    val disabledReason: String? = null,
    val runtimeStatusMessage: String? = null,
    val runtimeDiagnostics: String? = null,
    val errorMessage: String? = null,
    val canPersistHistory: Boolean = false,
    val currentSessionId: Long? = null
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
                val runtimePresentation = if (isDisabled) {
                    AssistantRuntimePresentation(message = null, diagnostics = null)
                } else {
                    buildRuntimePresentation(settings)
                }

                _uiState.value = _uiState.value.copy(
                    isDisabled = isDisabled,
                    disabledReason = disabledReason,
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

    fun submitQuery(rawQuery: String = _uiState.value.input) {
        val query = rawQuery.trim()
        if (query.isBlank() || _uiState.value.isLoading) return

        viewModelScope.launch {
            val settings = aiSettingsRepository.settings().first()
            if (!settings.aiEnabled || !settings.assistantEnabled || !settings.queryInterpretationEnabled) {
                _uiState.value = _uiState.value.copy(
                    isDisabled = true,
                    disabledReason = "Enable AI assistant to use this feature"
                )
                return@launch
            }

            val userItem = AssistantConversationItem.User(
                id = "user-${System.nanoTime()}",
                text = query
            )

            _uiState.value = _uiState.value.copy(
                input = "",
                isLoading = true,
                errorMessage = null,
                messages = _uiState.value.messages + userItem
            )

            val sessionId = ensureSessionIfNeeded()
            persistUserTurn(sessionId, query)

            val historyMessages = if (settings.storeConversationHistory && sessionId != null) {
                aiChatRepository.observeMessages(sessionId).first()
            } else {
                emptyList()
            }

            when (val interpretation = interpretFinancialQueryUseCase(query, historyMessages)) {
                is FinancialQueryInterpretationResult.Structured -> {
                    val result = executeFinancialQueryUseCase(interpretation.intent)
                    val navigationFilter = mapFinancialQueryToNavigationUseCase(interpretation.intent)
                    val resultItem = AssistantConversationItem.Result(
                        id = "result-${System.nanoTime()}",
                        queryText = query,
                        result = result,
                        drilldownFilter = navigationFilter
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        messages = _uiState.value.messages + resultItem,
                        currentSessionId = sessionId ?: _uiState.value.currentSessionId
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
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        messages = _uiState.value.messages + resultItem,
                        currentSessionId = sessionId ?: _uiState.value.currentSessionId
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
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        messages = _uiState.value.messages + resultItem,
                        currentSessionId = sessionId ?: _uiState.value.currentSessionId
                    )
                    persistAssistantTurn(sessionId, result)
                }
            }
        }
    }

    fun retryLast() {
        val lastUser = _uiState.value.messages.lastOrNull { it is AssistantConversationItem.User } as? AssistantConversationItem.User
            ?: return
        submitQuery(lastUser.text)
    }

    fun onSuggestionSelected(text: String) {
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

    fun clearAllHistory() {
        viewModelScope.launch {
            aiChatRepository.clearAllHistory()
            clearSession()
        }
    }

    private suspend fun ensureSessionIfNeeded(): Long? {
        val current = _uiState.value.currentSessionId
        if (current != null) return current

        val created = aiChatRepository.createSession(title = "Assistant")
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
        is FinancialQueryResult.Summary -> listOfNotNull(title, primaryText, supportingText).joinToString("\n")
        is FinancialQueryResult.Breakdown -> title
        is FinancialQueryResult.TransactionList -> "$title ($previewCount)"
        is FinancialQueryResult.Clarification -> prompt
        is FinancialQueryResult.Unsupported -> reason
    }

    private fun FinancialQueryResult.toPayloadJson(): String? = when (this) {
        is FinancialQueryResult.Summary -> JSONObject()
            .put("type", "summary")
            .put("title", title)
            .put("primaryText", primaryText)
            .put("supportingText", supportingText)
            .toString()

        is FinancialQueryResult.Breakdown -> JSONObject()
            .put("type", "breakdown")
            .put("title", title)
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
            .put("title", title)
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
