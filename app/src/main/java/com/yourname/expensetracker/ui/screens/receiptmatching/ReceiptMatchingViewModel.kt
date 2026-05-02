package com.yourname.expensetracker.ui.screens.receiptmatching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiptMatchingState(
    val unmatchedReceipts: List<ScannedReceipt> = emptyList(),
    val suggestedMatches: List<MatchSuggestion> = emptyList(),
    val manualCandidates: List<com.yourname.expensetracker.data.database.entity.Expense> = emptyList(),
    val selectedReceiptForManualMatch: ScannedReceipt? = null,
    val isLoading: Boolean = false,
    val autoMatchedCount: Int = 0,
    val pendingSuggestionCount: Int = 0
)

data class MatchSuggestion(
    val receipt: ScannedReceipt,
    val suggestedExpenseId: Long,
    val confidence: Double,
    val expenseMerchant: String?,
    val expenseAmount: Double?
)

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
            _state.update { it.copy(isLoading = true) }
            
            val unmatched = receiptRepository.getUnmatchedReceipts()
            val withSuggestions = receiptRepository.getReceiptsWithSuggestions()
            
            // Build match suggestions
            val suggestions = withSuggestions.mapNotNull { receipt ->
                val suggestedId = receipt.suggestedExpenseId ?: return@mapNotNull null
                val expense = receiptRepository.getExpenseById(suggestedId)
                
                MatchSuggestion(
                    receipt = receipt,
                    suggestedExpenseId = suggestedId,
                    confidence = receipt.matchConfidence?.toDouble() ?: 0.0,
                    expenseMerchant = expense?.merchant,
                    expenseAmount = expense?.amount
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
        }
    }

    fun runAutoMatching() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            val unmatched = receiptRepository.getUnmatchedReceipts()
            var autoMatched = 0
            
            for (receipt in unmatched) {
                when (val result = matcher.findBestMatch(receipt)) {
                    is MatchResult.AutoMatch -> {
                        receiptLinkService.linkReceiptToExpense(
                            receiptId = receipt.id,
                            expenseId = result.transaction.id,
                            linkType = "AUTO_MATCH",
                            source = "ReceiptMatchingViewModel",
                            confidence = result.score.toFloat()
                        )
                        autoMatched++
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
            
            _state.update { 
                it.copy(
                    isLoading = false,
                    autoMatchedCount = autoMatched
                )
            }
            loadReceipts()
        }
    }

    fun approveSuggestion(receiptId: Long) {
        viewModelScope.launch {
            receiptRepository.approveMatchSuggestion(receiptId)
            loadReceipts()
        }
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
        viewModelScope.launch {
            receiptRepository.clearMatchForReceipt(receipt.id)
            when (val result = matcher.findBestMatch(receipt)) {
                is MatchResult.AutoMatch -> {
                    receiptLinkService.linkReceiptToExpense(
                        receiptId = receipt.id,
                        expenseId = result.transaction.id,
                        linkType = "AUTO_MATCH",
                        source = "ReceiptMatchingViewModel",
                        confidence = result.score.toFloat()
                    )
                }
                is MatchResult.Suggested -> {
                    receiptRepository.saveMatchSuggestion(
                        receipt.id,
                        result.transaction.id,
                        result.score
                    )
                }
                MatchResult.NoMatch -> Unit
            }
            loadReceipts()
        }
    }

    fun refresh() {
        loadReceipts()
    }
}
