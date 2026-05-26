package com.yourname.expensetracker.ui.screens.receiptmatching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** S7-025: Typed content state replacing the misleading loadableState. */
sealed interface ReceiptMatchingContentState {
    data object Loading : ReceiptMatchingContentState
    data object Empty : ReceiptMatchingContentState
    data class Data(
        val unmatched: List<ScannedReceipt>,
        val suggestions: List<MatchSuggestion>
    ) : ReceiptMatchingContentState
}

data class ReceiptMatchingState(
    val unmatchedReceipts: List<ScannedReceipt> = emptyList(),
    val suggestedMatches: List<MatchSuggestion> = emptyList(),
    val manualCandidates: List<com.yourname.expensetracker.data.database.entity.Expense> = emptyList(),
    val selectedReceiptForManualMatch: ScannedReceipt? = null,
    val isLoading: Boolean = false,
    val autoMatchedCount: Int = 0,
    val pendingSuggestionCount: Int = 0,
    /** S12-028: non-null when an operation failed */
    val error: String? = null,
    /** S12-029: receipt IDs currently being mutated — prevents double-tap */
    val mutatingReceiptIds: Set<Long> = emptySet(),
    /** S12-029: true while auto-match is running globally */
    val isAutoMatching: Boolean = false
) {
    // S7-025: Typed content state — includes both unmatched receipts and suggestions
    val contentState: ReceiptMatchingContentState
        get() = when {
            isLoading -> ReceiptMatchingContentState.Loading
            unmatchedReceipts.isEmpty() && suggestedMatches.isEmpty() ->
                ReceiptMatchingContentState.Empty
            else -> ReceiptMatchingContentState.Data(unmatchedReceipts, suggestedMatches)
        }

    @Deprecated("Use contentState", ReplaceWith("contentState"))
    val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<ScannedReceipt>>
        get() = when {
            isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
            unmatchedReceipts.isEmpty() && suggestedMatches.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(com.yourname.expensetracker.domain.model.UiText.DynamicString("All receipts matched"))
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(unmatchedReceipts)
        }
}

data class MatchSuggestion(
    val receipt: ScannedReceipt,
    val suggestedExpenseId: Long,
    val confidence: Double,
    val expenseMerchant: String?,
    val expenseAmount: Double?,
    /** S12-030: Explicit currency for display — no raw amount without currency */
    val expenseCurrency: String? = null
)

/**
 * ViewModel for the receipt matching screen.
 *
 * ## N3: ReceiptMatchingViewModel legacy path
 * All link/unlink mutations go through [ReceiptLinkService], which is the
 * single owner of receipt-expense associations (it manages the join table,
 * legacy [ScannedReceipt.expenseId] field, and audit events). There is no
 * remaining legacy path that directly manipulates [ScannedReceipt.expenseId]
 * without going through [ReceiptLinkService].
 *
 * The [runAutoMatching] method uses [ReceiptLinkService.linkReceiptToExpense]
 * for auto-matches and [ReceiptRepository.saveMatchSuggestion] for suggested
 * matches (which are later approved via [approveSuggestion], also through
 * [ReceiptLinkService]).
 */
@HiltViewModel
@Suppress("DEPRECATION")
class ReceiptMatchingViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val matcher: ReceiptTransactionMatcher,
    private val receiptLinkService: ReceiptLinkService
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptMatchingState())
    val state: StateFlow<ReceiptMatchingState> = _state.asStateFlow()

    // S7-021: Cancel previous load before starting a new one to prevent stale-write races
    private var loadJob: kotlinx.coroutines.Job? = null
    // S7-F583-014: Generation counter — stale loads check before writing state
    private var loadSeq = 0L

    init {
        loadReceipts()
    }

    private fun loadReceipts() {
        val seq = ++loadSeq
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val unmatched = receiptRepository.getUnmatchedReceipts()
                val withSuggestions = receiptRepository.getReceiptsWithSuggestions()
                val suggestions = withSuggestions.mapNotNull { receipt ->
                    val suggestedId = receipt.suggestedExpenseId ?: return@mapNotNull null
                    val expense = receiptRepository.getExpenseById(suggestedId)
                    MatchSuggestion(
                        receipt = receipt,
                        suggestedExpenseId = suggestedId,
                        confidence = receipt.matchConfidence?.toDouble() ?: 0.0,
                        expenseMerchant = expense?.merchant,
                        expenseAmount = expense?.effectiveAmount,
                        expenseCurrency = expense?.currency
                    )
                }
                // S7-F583-014: Only write if this is still the latest load
                if (seq != loadSeq) return@launch
                _state.update {
                    it.copy(
                        unmatchedReceipts = unmatched,
                        suggestedMatches = suggestions,
                        isLoading = false,
                        pendingSuggestionCount = suggestions.size
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // never swallow cancellation
            } catch (e: Exception) {
                Timber.e(e, "ReceiptMatchingViewModel: loadReceipts failed")
                if (seq == loadSeq) _state.update { it.copy(isLoading = false, error = "Failed to load receipts.") }
            }
        }
    }

    fun runAutoMatching() {
        // S12-029: Idempotency guard
        if (_state.value.isAutoMatching) return
        viewModelScope.launch {
            _state.update { it.copy(isAutoMatching = true, error = null) }
            try {
                val unmatched = receiptRepository.getUnmatchedReceipts()
                var autoMatched = 0

                for (receipt in unmatched) {
                    when (val result = matcher.findBestMatch(receipt)) {
                        is MatchResult.AutoMatch -> {
                            // S7-022: Check Result.failure — linkReceiptToExpense returns Result, not throws
                            val linkResult = receiptLinkService.linkReceiptToExpense(
                                receiptId = receipt.id,
                                expenseId = result.transaction.id,
                                linkType = "AUTO_MATCH",
                                source = "ReceiptMatchingViewModel",
                                confidence = result.score.toFloat()
                            )
                            linkResult.fold(
                                onSuccess = { autoMatched++ },
                                onFailure = { e -> Timber.w(e, "Auto-match link failed for receipt ${receipt.id}") }
                            )
                        }
                        is MatchResult.Suggested -> {
                            receiptRepository.saveMatchSuggestion(
                                receipt.id,
                                result.transaction.id,
                                result.score
                            )
                        }
                        else -> {}
                    }
                }

                _state.update { it.copy(isAutoMatching = false, autoMatchedCount = autoMatched) }
                loadReceipts()
            } catch (e: Exception) {
                Timber.e(e, "ReceiptMatchingViewModel: runAutoMatching failed")
                _state.update { it.copy(isAutoMatching = false, error = "Auto-matching failed: ${e.message}") }
            }
        }
    }

    fun approveSuggestion(receiptId: Long) {
        // S12-029: Idempotency guard
        if (_state.value.mutatingReceiptIds.contains(receiptId)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds + receiptId, error = null) }
            try {
                val receipt = receiptRepository.getReceiptById(receiptId)
                val suggestedExpenseId = receipt?.suggestedExpenseId
                if (receipt == null || suggestedExpenseId == null) {
                    _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId, error = "Receipt not found or has no suggestion.") }
                    return@launch
                }
                val result = receiptLinkService.linkReceiptToExpense(
                    receiptId = receiptId,
                    expenseId = suggestedExpenseId,
                    linkType = "REVIEW_APPROVAL",
                    source = ExpenseSource.REVIEW_APPROVAL.name,
                    matchStatus = MatchStatus.MANUALLY_MATCHED
                )
                result.fold(
                    onSuccess = { Timber.d("Approved match for receipt $receiptId -> expense $suggestedExpenseId") },
                    onFailure = { e ->
                        Timber.e(e, "Failed to approve match for receipt $receiptId")
                        _state.update { it.copy(error = "Failed to approve match. Please try again.") }
                    }
                )
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to approve match. Please try again.") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId) }
            }
            // S7-F583-007: Only reload on success — reload clears error, hiding failure from user
            if (_state.value.error == null) loadReceipts()
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun rejectSuggestion(receiptId: Long) {
        if (_state.value.mutatingReceiptIds.contains(receiptId)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds + receiptId, error = null) }
            try {
                receiptRepository.rejectAllSuggestions(receiptId)
                loadReceipts()
            } catch (e: Exception) {
                Timber.e(e, "Failed to reject suggestion for receipt $receiptId")
                _state.update { it.copy(error = "Failed to reject suggestion. Please try again.") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId) }
            }
        }
    }

    fun openManualMatch(receipt: ScannedReceipt) {
        viewModelScope.launch {
            val candidates = receiptRepository.getCandidateExpensesForReceipt(receipt)
            _state.update {
                it.copy(
                    selectedReceiptForManualMatch = receipt,
                    manualCandidates = candidates
                )
            }
        }
    }

    fun closeManualMatch() {
        _state.update {
            it.copy(
                selectedReceiptForManualMatch = null,
                manualCandidates = emptyList()
            )
        }
    }

    fun manualMatch(receiptId: Long, expenseId: Long) {
        if (_state.value.mutatingReceiptIds.contains(receiptId)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds + receiptId, error = null) }
            try {
                val result = receiptLinkService.linkReceiptToExpense(
                    receiptId = receiptId,
                    expenseId = expenseId,
                    linkType = "MANUAL_MATCH",
                    source = "ReceiptMatchingViewModel",
                    confidence = 1.0f
                )
                result.fold(
                    onSuccess = {
                        // S7-006: Only close dialog on success
                        closeManualMatch()
                        loadReceipts()
                    },
                    onFailure = { e ->
                        Timber.e(e, "Manual match failed for receipt $receiptId")
                        _state.update { it.copy(error = "Failed to link receipt. Please try again.") }
                        // Dialog stays open so user can retry or cancel
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Manual match exception for receipt $receiptId")
                _state.update { it.copy(error = "Failed to link receipt. Please try again.") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId) }
            }
        }
    }

    fun skipReceipt(receiptId: Long) {
        if (_state.value.mutatingReceiptIds.contains(receiptId)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds + receiptId, error = null) }
            try {
                receiptRepository.rejectAllSuggestions(receiptId)
                loadReceipts()
            } catch (e: Exception) {
                Timber.e(e, "Failed to skip receipt $receiptId")
                _state.update { it.copy(error = "Failed to skip receipt. Please try again.") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId) }
            }
        }
    }

    fun rerunForReceipt(receipt: ScannedReceipt) {
        if (_state.value.mutatingReceiptIds.contains(receipt.id)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds + receipt.id) }
            try {
                // S12-032: Use ReceiptLinkService for unlink — goes through lifecycle/audit path
                val currentExpenseId = receiptRepository.getReceiptById(receipt.id)?.expenseId
                if (currentExpenseId != null) {
                    receiptLinkService.unlinkReceiptFromExpense(
                        receiptId = receipt.id,
                        expenseId = currentExpenseId
                    )
                } else {
                    // No existing link — just clear suggestion fields via repository
                    receiptRepository.clearMatchForReceipt(receipt.id)
                }
                when (val result = matcher.findBestMatch(receipt)) {
                    is MatchResult.AutoMatch -> {
                        // S7-66F-008: Fold over Result — runCatching wrapping a Result is incorrect
                        val linkResult = receiptLinkService.linkReceiptToExpense(
                            receiptId = receipt.id,
                            expenseId = result.transaction.id,
                            linkType = "AUTO_MATCH",
                            source = "ReceiptMatchingViewModel",
                            confidence = result.score.toFloat()
                        )
                        linkResult.onFailure { e ->
                            _state.update { it.copy(error = "Rerun link failed. Please try again.") }
                            Timber.e(e, "Rerun link failed for receipt ${receipt.id}")
                        }
                    }
                    is MatchResult.Suggested -> {
                        receiptRepository.saveMatchSuggestion(receipt.id, result.transaction.id, result.score)
                    }
                    else -> {}
                }
                loadReceipts()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Rerun failed: ${e.message}") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receipt.id) }
            }
        }
    }

    fun refresh() {
        loadReceipts()
    }
}
