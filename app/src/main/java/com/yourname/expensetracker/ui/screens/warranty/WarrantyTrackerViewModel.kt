package com.yourname.expensetracker.ui.screens.warranty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.ui.util.UI_WRITE_BLOCKED_MESSAGE
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.ConfirmWarrantyResult
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject

data class WarrantyTrackerState(
    val warranties: List<Warranty> = emptyList(),
    val allWarranties: List<Warranty> = emptyList(),
    val isLoading: Boolean = false,
    val activeCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val totalProtectedValue: Double = 0.0,
    val selectedFilter: WarrantyStatus? = null,
    /** ai-fixes INV-10: rows whose [WarrantyReviewState] is AWAITING_REVIEW (derived, not persisted). */
    val needsReviewCount: Int = 0,
    /** ai-fixes G2-8: rows whose [WarrantyReviewState] is EXPIRED_UNCONFIRMED. */
    val expiredUnconfirmedCount: Int = 0,
    val autoDetectedWarranties: List<Warranty> = emptyList(),
    val showAutoDetectedOnly: Boolean = false,
    val showNeedsReviewOnly: Boolean = false,
    val referenceNowMillis: Long = 0L,
    /** S12-010: IDs of warranties currently being mutated */
    val mutatingWarrantyIds: Set<Long> = emptySet(),
    /** S12-010: non-null when a mutation failed (bounded string resource, never raw exception text) */
    val mutationError: UiText? = null,
    /** S12-004: true when add-manual dialog should close (emitted on success) */
    val addWarrantySuccess: Boolean = false
) {
    /**
     * ai-fixes INV-10: the single review-state derivation used by every count, filter, badge and
     * action in the warranty UI. [referenceNowMillis] is the clock of the last list emission.
     */
    fun reviewStateOf(warranty: Warranty): WarrantyReviewState =
        WarrantyReviewState.of(warranty, referenceNowMillis)

    val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<Warranty>>
        get() = when {
            isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
            warranties.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(com.yourname.expensetracker.domain.model.UiText.DynamicString("No warranties tracked"))
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(warranties)
        }
}

@HiltViewModel
class WarrantyTrackerViewModel @Inject constructor(
    private val warrantyRepository: WarrantyTrackerRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(WarrantyTrackerState())
    val state: StateFlow<WarrantyTrackerState> = _state.asStateFlow()
    private var warrantiesCollectorJob: Job? = null

    init {
        loadWarranties()
        loadStats()
    }

    private fun loadWarranties() {
        warrantiesCollectorJob?.cancel()
        warrantiesCollectorJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, referenceNowMillis = timeProvider.now()) }
            
            warrantyRepository.getAllWarranties()
                .collect { warranties ->
                    // INV-10: re-read the clock per emission so a row that has just passed its
                    // end date is classified EXPIRED_UNCONFIRMED even before reconciliation.
                    val now = timeProvider.now()
                    _state.update {
                        val autoDetected = warranties.filter { warranty -> warranty.autoDetected }
                        val states = warranties.map { warranty -> WarrantyReviewState.of(warranty, now) }
                        val updated = it.copy(
                            allWarranties = warranties,
                            autoDetectedWarranties = autoDetected,
                            needsReviewCount = states.count { s -> s == WarrantyReviewState.AWAITING_REVIEW },
                            expiredUnconfirmedCount = states.count { s -> s == WarrantyReviewState.EXPIRED_UNCONFIRMED },
                            referenceNowMillis = now,
                            isLoading = false
                        )
                        updated.withDerivedWarranties()
                    }
                }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            // The clock is owned by the list emission (counts, filters and cards are derived from
            // one referenceNowMillis); stats never move it on their own.
            val activeCount = warrantyRepository.getActiveWarrantyCount()
            val expiringSoon = warrantyRepository.getWarrantiesExpiringSoon(30).size
            // S12-006: Use currency-normalized aggregate instead of deprecated raw sum
            // CancellationException is rethrown (never swallowed); other failures log the class name only.
            val protectedValueAggregate = try {
                warrantyRepository.getTotalProtectedValueAggregate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("warranty protected value unavailable (%s)", e::class.simpleName)
                null
            }
            val protectedValue = protectedValueAggregate?.displayAmount ?: 0.0
            
            _state.update {
                it.copy(
                    activeCount = activeCount,
                    expiringSoonCount = expiringSoon,
                    totalProtectedValue = protectedValue
                )
            }
        }
    }

    fun filterByStatus(status: WarrantyStatus?) {
        _state.update {
            it.copy(
                selectedFilter = status,
                showAutoDetectedOnly = false,
                showNeedsReviewOnly = false
            ).withDerivedWarranties()
        }
    }
    
    // F1: Filter for auto-detected warranties
    fun filterByAutoDetected() {
        _state.update {
            val enableAutoDetectedOnly = !it.showAutoDetectedOnly
            it.copy(
                showAutoDetectedOnly = enableAutoDetectedOnly,
                showNeedsReviewOnly = false,
                selectedFilter = null
            ).withDerivedWarranties()
        }
    }
    
    // F1: Show warranties needing review
    fun showNeedsReview() {
        _state.update {
            it.copy(
                showNeedsReviewOnly = true,
                showAutoDetectedOnly = false,
                selectedFilter = null
            ).withDerivedWarranties()
        }
    }

    /**
     * WRN-4: Manual warranties no longer create a placeholder receipt.
     * Previously we called [WarrantyTrackerRepository.createManualPlaceholderReceipt]
     * which generated a fake EUR receipt just to satisfy the FK constraint.
     * Since [Warranty.receiptId] is now nullable, we skip the placeholder entirely
     * and store a null receiptId, avoiding fake EUR records in the receipt table.
     */
    fun addManualWarranty(
        productName: String,
        merchantName: String,
        purchaseDate: Long,
        warrantyDurationMonths: Int,
        supportPhone: String?
    ) {
        viewModelScope.launch {
            try {
                val purchaseStart = TimePeriodUtils.getStartOfDay(purchaseDate)
                val endDateMidnight = TimePeriodUtils.addMonths(purchaseStart, warrantyDurationMonths)
                // Use half-open end-of-day semantics so the warranty survives
                // through its entire expiration day (matches WarrantyTrackerRepository).
                val endDate = TimePeriodUtils.getEndOfDay(endDateMidnight)
                // WRN-4: receiptId is null — no placeholder receipt is created.
                val manualWarranty = Warranty(
                    receiptId = null,
                    expenseId = null,
                    productName = productName,
                    merchantName = merchantName,
                    purchaseDate = purchaseStart,
                    warrantyDurationMonths = warrantyDurationMonths,
                    warrantyEndDate = endDate,
                    supportPhone = supportPhone?.takeIf { it.isNotBlank() },
                    extractionSource = "manual"
                )
                warrantyRepository.addWarranty(manualWarranty)
                // S12-004: Signal success so dialog can close
                _state.update { it.copy(addWarrantySuccess = true, mutationError = null) }
                loadStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutationFailure(e)
            }
        }
    }

    /**
     * R29: repository writes fail closed, so a failed mutation must surface as a bounded,
     * localizable message (string resource only; never raw exception text) instead of
     * crashing the app from the viewModelScope coroutine.
     */
    private fun reportMutationFailure(
        e: Exception,
        failureMessage: UiText = UiText.StringResource(R.string.warranty_mutation_failed)
    ) {
        Timber.w("warranty mutation failed (%s)", e::class.simpleName)
        // R18b: a write rejected by the backup/restore/reset write barrier gets the shared,
        // retry-later maintenance message instead of the generic failure message.
        val message = if (e is DatabaseAccessBlockedException) {
            UI_WRITE_BLOCKED_MESSAGE
        } else {
            failureMessage
        }
        _state.update { it.copy(mutationError = message) }
    }

    fun clearAddWarrantySuccess() {
        _state.update { it.copy(addWarrantySuccess = false) }
    }

    fun clearMutationError() {
        _state.update { it.copy(mutationError = null) }
    }
    
    /**
     * ai-fixes INV-13: confirm (or "keep in history" for an expired-unconfirmed row) passes the id
     * only. The repository owns the status/needsReview transition; the ViewModel never builds it.
     * [ConfirmWarrantyResult.AlreadyConfirmed] is a silent success (the list flow refreshes the card).
     */
    fun confirmWarranty(warrantyId: Long) {
        // S12-010: Idempotency guard
        if (_state.value.mutatingWarrantyIds.contains(warrantyId)) return
        viewModelScope.launch {
            _state.update { it.copy(mutatingWarrantyIds = it.mutatingWarrantyIds + warrantyId) }
            try {
                when (warrantyRepository.confirmWarranty(warrantyId)) {
                    is ConfirmWarrantyResult.Confirmed,
                    ConfirmWarrantyResult.AlreadyConfirmed -> loadStats()
                    ConfirmWarrantyResult.NotFound ->
                        _state.update {
                            it.copy(mutationError = UiText.StringResource(R.string.warranty_error_not_found))
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutationFailure(e, UiText.StringResource(R.string.warranty_error_confirm_failed))
            } finally {
                _state.update { it.copy(mutatingWarrantyIds = it.mutatingWarrantyIds - warrantyId) }
            }
        }
    }
    
    // F1: Reject/delete an auto-detected warranty that was incorrect
    fun rejectAutoDetectedWarranty(warranty: Warranty) {
        viewModelScope.launch {
            try {
                // S12-009: Use atomic repository method — warranty + return window deleted together
                warrantyRepository.rejectAutoDetectedWarranty(warranty)
                loadStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutationFailure(e)
            }
        }
    }

    fun markAsClaimed(warrantyId: Long) {
        viewModelScope.launch {
            try {
                warrantyRepository.markWarrantyAsClaimed(warrantyId)
                loadStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutationFailure(e)
            }
        }
    }

    fun deleteWarranty(warranty: Warranty) {
        viewModelScope.launch {
            try {
                warrantyRepository.deleteWarranty(warranty)
                loadStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutationFailure(e)
            }
        }
    }

    fun refresh() {
        loadWarranties()
        loadStats()
    }

    private fun WarrantyTrackerState.withDerivedWarranties(): WarrantyTrackerState {
        // INV-10: every review-related filter goes through WarrantyReviewState.
        val derived = when {
            showNeedsReviewOnly -> allWarranties.filter {
                reviewStateOf(it) == WarrantyReviewState.AWAITING_REVIEW
            }
            showAutoDetectedOnly -> allWarranties.filter { it.autoDetected }
            // A row under review is never listed as Active; expired-unconfirmed rows are listed
            // as Expired even before the expiry sweep persists the transition (G2-8).
            selectedFilter == WarrantyStatus.ACTIVE -> allWarranties.filter {
                it.status == WarrantyStatus.ACTIVE && reviewStateOf(it) == WarrantyReviewState.NOT_IN_REVIEW
            }
            selectedFilter == WarrantyStatus.EXPIRED -> allWarranties.filter {
                it.status == WarrantyStatus.EXPIRED ||
                    reviewStateOf(it) == WarrantyReviewState.EXPIRED_UNCONFIRMED
            }
            selectedFilter != null -> allWarranties.filter { it.status == selectedFilter }
            else -> allWarranties
        }
        // UX §4.1: AWAITING_REVIEW first, then by end date (stable).
        val ordered = derived.sortedWith(
            compareBy<Warranty>(
                { if (reviewStateOf(it) == WarrantyReviewState.AWAITING_REVIEW) 0 else 1 },
                { it.warrantyEndDate }
            )
        )
        return copy(warranties = ordered)
    }
}
