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
class ReceiptMatchingViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val matcher: ReceiptTransactionMatcher,
    private val receiptLinkService: ReceiptLinkService
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptMatchingState())
    val state: StateFlow<ReceiptMatchingState> = _state.asStateFlow()

    init {
        loadReceipts()
    }

    private fun loadReceipts() {
        viewModelScope.launch {
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
                _state.update {
                    it.copy(
                        unmatchedReceipts = unmatched,
                        suggestedMatches = suggestions,
                        isLoading = false,
                        pendingSuggestionCount = suggestions.size
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "ReceiptMatchingViewModel: loadReceipts failed")
                _state.update { it.copy(isLoading = false, error = "Failed to load receipts: ${e.message}") }
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
                            // S12-027: Only increment if link succeeds
                            val linkResult = runCatching {
                                receiptLinkService.linkReceiptToExpense(
                                    receiptId = receipt.id,
                                    expenseId = result.transaction.id,
                                    linkType = "AUTO_MATCH",
                                    source = "ReceiptMatchingViewModel",
                                    confidence = result.score.toFloat()
                                )
                            }
                            if (linkResult.isSuccess) autoMatched++
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
                    _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId, error = "Receipt not found or has no suggestion") }
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
                        _state.update { it.copy(error = "Failed to approve: ${e.message}") }
                    }
                )
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to approve: ${e.message}") }
            } finally {
                _state.update { it.copy(mutatingReceiptIds = it.mutatingReceiptIds - receiptId) }
            }
            loadReceipts()
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun rejectSuggestion(receiptId: Long) {
        viewModelScope.launch {
            receiptRepository.rejectAllSuggestions(receiptId)
            loadReceipts()
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
        viewModelScope.launch {
            receiptLinkService.linkReceiptToExpense(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "MANUAL_MATCH",
                source = "ReceiptMatchingViewModel",
                confidence = 1.0f
            )
            closeManualMatch()
            loadReceipts()
        }
    }

    fun skipReceipt(receiptId: Long) {
        viewModelScope.launch {
            receiptRepository.rejectAllSuggestions(receiptId)
            loadReceipts()
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
                        val linkResult = runCatching {
                            receiptLinkService.linkReceiptToExpense(
                                receiptId = receipt.id,
                                expenseId = result.transaction.id,
                                linkType = "AUTO_MATCH",
                                source = "ReceiptMatchingViewModel",
                                confidence = result.score.toFloat()
                            )
                        }
                        if (linkResult.isFailure) {
                            _state.update { it.copy(error = "Rerun link failed: ${linkResult.exceptionOrNull()?.message}") }
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
