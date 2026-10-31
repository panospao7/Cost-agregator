package com.yourname.expensetracker.ui.screens.assistant

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.model.ClarificationCompletion
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.routeDisplayText
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CompleteFinancialQueryClarificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.InterpretFinancialQueryUseCase
import com.yourname.expensetracker.domain.ai.usecase.MapFinancialQueryToNavigationUseCase
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import com.yourname.expensetracker.ui.components.ai.assistantClarificationChoiceLabelRes
import com.yourname.expensetracker.ui.components.ai.assistantUnsupportedReasonMessageRes
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.util.guardedWrite
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
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** AID-10: Nanoseconds per millisecond for elapsed-duration diagnostics. */
private const val NANOS_PER_MILLI = 1_000_000L

/** C4 (INV-C14): bounded log-only token for a clarification tap that is no longer live. Never carries option text. */
private const val CLARIFICATION_EXPIRED = "CLARIFICATION_EXPIRED"
private const val CLARIFICATION_COMPLETION_UNAVAILABLE = "CLARIFICATION_COMPLETION_UNAVAILABLE"

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
    val showClearHistoryConfirm: Boolean = false,
    /**
     * C4 (INV-C14): the single live structured clarification as (clarification item id, completion).
     * In-memory only, never persisted; options of any other clarification card render disabled.
     */
    val livePendingClarification: Pair<String, ClarificationCompletion>? = null
)

private data class AssistantRuntimePresentation(
    val message: String?,
    val diagnostics: String?
)

sealed interface AssistantConversationItem {
    data class User(
        val id: String,
        val text: String,
        /** C4 (INV-C16): set only for structured-clarification taps; `retryLast` re-executes it without interpretation. */
        val completedIntent: FinancialQueryIntent? = null
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
    private val completeFinancialQueryClarificationUseCase: CompleteFinancialQueryClarificationUseCase,
    private val mapFinancialQueryToNavigationUseCase: MapFinancialQueryToNavigationUseCase,
    private val monotonicTimeProvider: MonotonicTimeProvider
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
     * Captures phase-level timing, routing info, and error details.
     *
     * S11-009 / T1 privacy: user query text is NEVER captured. There is no
     * query field, so `toDisplayString()` can only emit controlled timing,
     * result-type, route, and reason-code/exception-class fields in every
     * build (debug and release alike).
     */
    private data class QueryDiagnostics(
        val totalDurationMs: Long,
        val interpretationDurationMs: Long? = null,
        val executionDurationMs: Long? = null,
        val resultType: String? = null,
        val route: String? = null,
        val error: String? = null
    ) {
        fun toDisplayString(): String = buildString {
            appendLine("Total: ${totalDurationMs}ms")
            interpretationDurationMs?.let { appendLine("Interpret: ${it}ms") }
            executionDurationMs?.let { appendLine("Execute: ${it}ms") }
            resultType?.let { appendLine("Result: $it") }
            route?.let { appendLine("Route: $it") }
            error?.let { appendLine("Error: $it") }
        }
    }

    /**
     * AID-10: Whole-millisecond elapsed duration from a monotonic start read.
     * Monotonic (not wall-clock) so diagnostics stay correct across NTP/user
     * clock jumps; the arbitrary origin makes only the difference meaningful.
     */
    private fun elapsedMillis(startNanos: Long): Long =
        (monotonicTimeProvider.nowNanos() - startNanos) / NANOS_PER_MILLI

    fun submitQuery(rawQuery: String = _uiState.value.input) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        if (!_isSubmitting.compareAndSet(false, true)) return

        // C4 (INV-C14): any new submitted query expires the live pending clarification.
        clearPendingClarification()

        _currentQueryJob?.cancel()
        val queryStartedAt = monotonicTimeProvider.nowNanos()

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
                    id = "user-${UUID.randomUUID().toString()}",
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

                // C1: snapshot the PRIOR turns before this query is persisted. Shaping (USER-only,
                // last K, redaction) is owned solely by FinancialQueryInterpretationInputBuilder.
                val historyMessages = if (settings.storeConversationHistory && sessionId != null) {
                    aiChatRepository.observeMessages(sessionId).first()
                } else {
                    emptyList()
                }

                persistUserTurn(sessionId, query)

                // AID-10: Capture interpretation timing
                val interpretationStart = monotonicTimeProvider.nowNanos()
                val interpretation = interpretFinancialQueryUseCase(query, historyMessages)
                val interpretationDuration = elapsedMillis(interpretationStart)

                val diagnosticsBuilder = QueryDiagnostics(
                    totalDurationMs = elapsedMillis(queryStartedAt),
                    interpretationDurationMs = interpretationDuration,
                    route = _uiState.value.runtimeDiagnostics
                )

                when (interpretation) {
                    is FinancialQueryInterpretationResult.Structured -> {
                        // AID-10: Capture execution timing
                        val execStart = monotonicTimeProvider.nowNanos()
                        val result = executeFinancialQueryUseCase(interpretation.intent)
                        val executionDuration = elapsedMillis(execStart)
                        val navigationFilter = mapFinancialQueryToNavigationUseCase(interpretation.intent)?.toUi()
                        val resultItem = AssistantConversationItem.Result(
                            id = "result-${UUID.randomUUID().toString()}",
                            queryText = query,
                            result = result,
                            drilldownFilter = navigationFilter
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = elapsedMillis(queryStartedAt),
                            executionDurationMs = executionDuration,
                            resultType = result::class.simpleName
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem,
                            livePendingClarification = pendingClarificationFor(resultItem)
                        )
                        persistAssistantTurn(sessionId, result)
                    }

                    is FinancialQueryInterpretationResult.Clarification -> {
                        val result = FinancialQueryResult.Clarification(
                            prompt = interpretation.prompt,
                            options = interpretation.options,
                            completion = interpretation.completion
                        )
                        val resultItem = AssistantConversationItem.Result(
                            id = "clarification-${UUID.randomUUID().toString()}",
                            queryText = query,
                            result = result
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = elapsedMillis(queryStartedAt),
                            resultType = "Clarification"
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem,
                            livePendingClarification = pendingClarificationFor(resultItem)
                        )
                        persistAssistantTurn(sessionId, result)
                    }

                    is FinancialQueryInterpretationResult.Unsupported -> {
                        val result = FinancialQueryResult.Unsupported(
                            reason = interpretation.reason,
                            diagnostic = interpretation.diagnostic
                        )
                        val resultItem = AssistantConversationItem.Result(
                            id = "unsupported-${UUID.randomUUID().toString()}",
                            queryText = query,
                            result = result
                        )
                        lastDiagnostics = diagnosticsBuilder.copy(
                            totalDurationMs = elapsedMillis(queryStartedAt),
                            resultType = "Unsupported"
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            messages = _uiState.value.messages + resultItem,
                            livePendingClarification = pendingClarificationFor(resultItem)
                        )
                        persistAssistantTurn(sessionId, result)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastDiagnostics = reportQueryFailure(e, queryStartedAt, restoreInput = query)
            } finally {
                finishQuery(lastDiagnostics)
            }
        }
    }

    /** S11-009: shared failure path; adds a bounded Error item (which also expires any pending clarification). */
    private fun reportQueryFailure(
        e: Exception,
        queryStartedAt: Long,
        restoreInput: String?
    ): QueryDiagnostics {
        // S11-009: Log only controlled reason code + exception class; never raw
        // exception messages, SQL text, paths, or stack traces.
        val failureCode = assistantFailureCode(e)
        Timber.e("Assistant query pipeline failed: reasonCode=%s errorClass=%s", failureCode, e::class.simpleName)
        val friendlyMessage = mapAssistantExceptionToUserMessage(e)
        val diagnostics = QueryDiagnostics(
            totalDurationMs = elapsedMillis(queryStartedAt),
            error = "$failureCode [${e::class.simpleName}]"
        )
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = friendlyMessage,
            messages = _uiState.value.messages + AssistantConversationItem.Error(
                id = "error-${UUID.randomUUID().toString()}",
                text = friendlyMessage
            ),
            input = if (restoreInput != null && _uiState.value.input.isBlank()) restoreInput else _uiState.value.input,
            livePendingClarification = null
        )
        return diagnostics
    }

    private fun finishQuery(lastDiagnostics: QueryDiagnostics?) {
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

    private fun pendingClarificationFor(item: AssistantConversationItem.Result): Pair<String, ClarificationCompletion>? =
        (item.result as? FinancialQueryResult.Clarification)?.completion?.let { item.id to it }

    private fun clearPendingClarification() {
        if (_uiState.value.livePendingClarification != null) {
            _uiState.value = _uiState.value.copy(livePendingClarification = null)
        }
    }

    /**
     * C4 (INV-C13/C14): tap on option [optionIndex] of the structured clarification card [itemId].
     *
     * Consume first, then execute. The pending intent is completed by the pure completer and executed
     * directly: no interpreter, provider or network call. A tap on a card that is not the live one (or
     * after consumption) is a no-op that logs the bounded [CLARIFICATION_EXPIRED] token. A tap while a
     * query is in flight is ignored and leaves the pending entry untouched.
     */
    fun onClarificationOptionSelected(itemId: String, optionIndex: Int) {
        if (_isSubmitting.value) return

        val pending = _uiState.value.livePendingClarification
        val completion = pending?.takeIf { it.first == itemId }?.second
        val choice = completion?.choices?.getOrNull(optionIndex)
        if (completion == null || choice == null) {
            Timber.w("Assistant clarification tap ignored: reasonCode=%s", CLARIFICATION_EXPIRED)
            return
        }

        // Compute label + completed intent BEFORE taking the submit gate / consuming the pending entry,
        // so a throwing resource lookup or completer cannot leave _isSubmitting stuck at true.
        val label: String
        val completedIntent: FinancialQueryIntent
        try {
            label = application.getString(assistantClarificationChoiceLabelRes(choice))
            completedIntent = completeFinancialQueryClarificationUseCase(completion, choice)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Bounded reason code only: no exception message, stack trace or payload.
            Timber.w("Assistant clarification tap ignored: reasonCode=%s", CLARIFICATION_COMPLETION_UNAVAILABLE)
            return
        }

        if (!_isSubmitting.compareAndSet(false, true)) return
        clearPendingClarification()
        launchCompletedIntent(label, completedIntent)
    }

    fun retryLast() {
        val lastUser = _uiState.value.messages.lastOrNull { it is AssistantConversationItem.User } as? AssistantConversationItem.User
            ?: return
        val completedIntent = lastUser.completedIntent
        if (completedIntent != null) {
            // C4 (INV-C16): re-execute the completed intent; never re-interpret the option label.
            if (!_isSubmitting.compareAndSet(false, true)) return
            clearPendingClarification()
            launchCompletedIntent(lastUser.text, completedIntent)
            return
        }
        submitQuery(lastUser.text)
    }

    /**
     * Executes an already-completed intent. The caller must have acquired [_isSubmitting] and consumed
     * the pending clarification. Never calls the interpreter. [CancellationException] propagates (INV-4);
     * the User item keeps [AssistantConversationItem.User.completedIntent] so retry stays possible.
     */
    private fun launchCompletedIntent(label: String, intent: FinancialQueryIntent) {
        _currentQueryJob?.cancel()
        val queryStartedAt = monotonicTimeProvider.nowNanos()

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
                    id = "user-${UUID.randomUUID().toString()}",
                    text = label,
                    completedIntent = intent
                )

                val sessionId = ensureSessionIfNeeded()

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    errorMessage = null,
                    messages = _uiState.value.messages + userItem,
                    currentSessionId = sessionId ?: _uiState.value.currentSessionId
                )

                persistUserTurn(sessionId, label)

                val execStart = monotonicTimeProvider.nowNanos()
                val result = executeFinancialQueryUseCase(intent)
                val executionDuration = elapsedMillis(execStart)
                val navigationFilter = mapFinancialQueryToNavigationUseCase(intent)?.toUi()
                val resultItem = AssistantConversationItem.Result(
                    id = "result-${UUID.randomUUID().toString()}",
                    queryText = label,
                    result = result,
                    drilldownFilter = navigationFilter
                )
                lastDiagnostics = QueryDiagnostics(
                    totalDurationMs = elapsedMillis(queryStartedAt),
                    executionDurationMs = executionDuration,
                    resultType = result::class.simpleName,
                    route = _uiState.value.runtimeDiagnostics
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    messages = _uiState.value.messages + resultItem,
                    livePendingClarification = pendingClarificationFor(resultItem)
                )
                persistAssistantTurn(sessionId, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastDiagnostics = reportQueryFailure(e, queryStartedAt, restoreInput = null)
            } finally {
                finishQuery(lastDiagnostics)
            }
        }
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
        clearPendingClarification()
        viewModelScope.launch {
            val sessionToClear = _uiState.value.currentSessionId
            if (sessionToClear != null) {
                // R18b: blocked by backup/restore -> keep the session visible and show the message.
                guardedWrite(::showWriteBlocked) {
                    aiChatRepository.clearSession(sessionToClear)
                } ?: return@launch
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
        clearPendingClarification()
        viewModelScope.launch {
            guardedWrite(::showWriteBlocked) {
                aiChatRepository.clearAllHistory()
            } ?: return@launch
            clearSession()
        }
    }

    /** R18b: surfaces the bounded maintenance message through the existing errorMessage channel. */
    private fun showWriteBlocked(message: UiText) {
        _uiState.value = _uiState.value.copy(errorMessage = message.asString(application))
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
        is FinancialQueryResult.Unsupported ->
            application.getString(assistantUnsupportedReasonMessageRes(reason))
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
            .put("clarificationPrompt", prompt)
            .put("options", JSONArray(options))
            .toString()

        is FinancialQueryResult.Unsupported -> JSONObject()
            .put("type", "unsupported")
            .put("reason", reason.name)
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

    /**
     * S11-009: Maps a pipeline exception to a controlled [DiagnosticReasonCode]
     * name only. Never returns raw exception messages, SQL text, file paths, or
     * user payloads. The exception class name may be surfaced separately as a
     * bounded structured field.
     */
    private fun assistantFailureCode(error: Throwable): String = when (error) {
        is SecurityException -> DiagnosticReasonCode.PERMISSION_DENIED.name
        is IOException -> DiagnosticReasonCode.NETWORK_UNAVAILABLE.name
        else -> DiagnosticReasonCode.UNKNOWN_ERROR.name
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
