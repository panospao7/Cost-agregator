package com.yourname.expensetracker.ui.screens.installments.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.screens.installments.installmentAmountUi
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bounded failure codes of the history list (INV-31). Only these codes reach the UI and the log;
 * the text shown comes from fixed string resources, never from an exception message.
 */
enum class InstallmentHistoryErrorCode { LOAD_FAILED, REFRESH_FAILED }

/** History filter chips (ux-flows 3.8). */
enum class InstallmentHistoryFilter(val status: InstallmentPlanStatus?) {
    ALL(null),
    COMPLETED(InstallmentPlanStatus.COMPLETED),
    CANCELLED(InstallmentPlanStatus.CANCELLED),
    REFUNDED(InstallmentPlanStatus.REFUNDED),
    PAID_OFF(InstallmentPlanStatus.PAID_OFF)
}

/**
 * One history card (spec 11.1, AE-7, AE-11). Figures are in the plan currency. [totalCounted] and
 * [financingCost] are an explicit "unavailable" when the read model cannot derive them (INV-17);
 * a REFUNDED plan's total is a legitimate zero (its paid entries are reversed). A null
 * [startEpochDay] or [endEpochDay] is shown as "Unavailable", never as a date guess.
 */
data class InstallmentHistoryCardUi(
    val planId: Long,
    val displayName: String,
    val currencyCode: String,
    val finalStatus: InstallmentPlanStatus,
    val startEpochDay: Long?,
    val endEpochDay: Long?,
    val paidCount: Int,
    val skippedCount: Int,
    val cancelledCount: Int,
    val reversedCount: Int,
    val paidBeforeTrackingCount: Int,
    val totalCounted: InstallmentAmountUi,
    val financingCost: InstallmentAmountUi
)

data class InstallmentHistoryContent(
    /** Cards that match the selected filter, most recently ended first (the read model's order). */
    val cards: List<InstallmentHistoryCardUi>
)

data class InstallmentHistoryUiState(
    val loadable: LoadableUiState<InstallmentHistoryContent> = LoadableUiState.Loading,
    val filter: InstallmentHistoryFilter = InstallmentHistoryFilter.ALL,
    /** Bounded code of the last failure, or null. */
    val errorCode: InstallmentHistoryErrorCode? = null,
    /** True when a refresh failed but the last loaded content is still shown. */
    val refreshFailed: Boolean = false
)

/** Pure mapping from the read model to the history UI state. */
internal object InstallmentHistoryMapper {

    fun toState(items: List<InstallmentHistoryItem>, filter: InstallmentHistoryFilter): InstallmentHistoryUiState {
        // Empty means no finished plan at all; a filter that matches nothing is Data with no cards
        // ("filtered empty", ux-flows 3.8).
        if (items.isEmpty()) {
            return InstallmentHistoryUiState(
                loadable = LoadableUiState.Empty(UiText.from(R.string.installments_history_empty_body)),
                filter = filter
            )
        }
        val cards = items
            .filter { filter.status == null || it.summary.finalStatus == filter.status }
            .map(::card)
        return InstallmentHistoryUiState(
            loadable = LoadableUiState.Data(InstallmentHistoryContent(cards)),
            filter = filter
        )
    }

    private fun card(item: InstallmentHistoryItem): InstallmentHistoryCardUi {
        val summary = item.summary
        return InstallmentHistoryCardUi(
            planId = item.planId,
            displayName = item.displayName,
            currencyCode = summary.currency.code,
            finalStatus = summary.finalStatus,
            startEpochDay = summary.startEpochDay,
            endEpochDay = summary.endEpochDay,
            paidCount = summary.counts.paid,
            skippedCount = summary.counts.skipped,
            cancelledCount = summary.counts.cancelled,
            reversedCount = summary.counts.reversed,
            paidBeforeTrackingCount = summary.counts.paidBeforeTracking,
            totalCounted = installmentAmountUi(summary.totalCounted, summary.currency),
            financingCost = installmentAmountUi(summary.financingCost, summary.currency)
        )
    }
}

/**
 * History of finished plans: COMPLETED, CANCELLED, REFUNDED and PAID_OFF (loan-installments
 * S10a-2). Read-only: it reads only through [InstallmentPlanReadRepository] and never writes.
 * Failures map to bounded codes and fixed strings; an exception message never reaches the UI or
 * the log (INV-31).
 */
@HiltViewModel
class InstallmentHistoryViewModel @Inject constructor(
    private val readRepository: InstallmentPlanReadRepository
) : ViewModel() {

    private val filter = MutableStateFlow(InstallmentHistoryFilter.ALL)
    private val _state = MutableStateFlow(InstallmentHistoryUiState())
    val state: StateFlow<InstallmentHistoryUiState> = _state.asStateFlow()

    private var collectJob: Job? = null

    /** Last items read, so the filter chips keep working while no collection is running. */
    private var lastItems: List<InstallmentHistoryItem>? = null

    init {
        launchCollection()
    }

    /** Starts observing again after [stop]; a no-op while a collection is already running. */
    fun start() {
        if (collectJob?.isActive == true) return
        launchCollection()
    }

    /**
     * Stops observing when the screen leaves composition. The ViewModel is activity-scoped (no
     * NavHost), so without this the collection would run for the life of the activity.
     */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    fun selectFilter(selected: InstallmentHistoryFilter) {
        filter.value = selected
        // With no collection running (a failed refresh or a stopped screen) the combine does not
        // re-emit, so the cached items are re-filtered here and the chips keep working.
        if (collectJob?.isActive != true) {
            lastItems?.let { items ->
                val current = _state.value
                _state.value = InstallmentHistoryMapper.toState(items, selected).let { mapped ->
                    if (mapped.loadable is LoadableUiState.Data) {
                        mapped.copy(errorCode = current.errorCode, refreshFailed = current.refreshFailed)
                    } else {
                        mapped
                    }
                }
            }
        }
    }

    /** Restarts the observation after a failure (Retry). */
    fun retry() {
        if (_state.value.loadable !is LoadableUiState.Data) {
            _state.value = InstallmentHistoryUiState(filter = filter.value)
        } else {
            _state.value = _state.value.copy(refreshFailed = false, errorCode = null)
        }
        launchCollection()
    }

    private fun launchCollection() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            combine(readRepository.observeHistory(), filter) { items, selected ->
                lastItems = items
                InstallmentHistoryMapper.toState(items, selected)
            }
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    onReadFailed()
                }
                .collect { mapped -> _state.value = mapped }
        }
    }

    private fun onReadFailed() {
        val current = _state.value
        val hasContent = current.loadable is LoadableUiState.Data
        val code = if (hasContent) InstallmentHistoryErrorCode.REFRESH_FAILED else InstallmentHistoryErrorCode.LOAD_FAILED
        // Bounded code only: no exception text, plan name, amount or id (INV-31).
        Timber.w("Installment history read failed: code=%s", code.name)
        _state.value = if (hasContent) {
            current.copy(refreshFailed = true, errorCode = code)
        } else {
            InstallmentHistoryUiState(
                loadable = LoadableUiState.Error(UiText.from(R.string.installments_error_load_history)),
                filter = filter.value,
                errorCode = code
            )
        }
    }
}
