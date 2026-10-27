package com.yourname.expensetracker.ui.screens.installments.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentEntryLine
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.ui.screens.installments.installmentAmountUi
import com.yourname.expensetracker.ui.screens.installments.installmentMoney
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bounded failure codes of the plan detail (INV-31). Only these codes reach the UI and the log; the
 * text shown comes from fixed string resources, never from an exception message.
 */
enum class InstallmentDetailErrorCode { LOAD_FAILED, REFRESH_FAILED, NOT_FOUND }

/** Row label of one schedule entry (AE-18 / ux-flows 3.5 table). The screen maps it to a fixed string. */
enum class InstallmentEntryLabelUi {
    UPCOMING,
    DUE_TODAY,
    OVERDUE,
    PAID,
    SKIPPED,
    CANCELLED,
    REFUNDED,
    PAID_BEFORE_TRACKING,
    PAYOFF
}

/** Bounded, known row sub-reasons. A stored code outside this set is dropped, never shown as text. */
enum class InstallmentEntryReasonUi {
    SKIP_LENDER_HOLIDAY,
    SKIP_WAIVED,
    SKIP_OTHER,
    PLAN_STOPPED,
    PLAN_REFUNDED,
    PAID_OFF_EARLY,
    SHORTENED,
    EXPENSE_REMOVED
}

/** Why the financing cost row shows "Unavailable" (INV-17), as a bounded code. */
enum class InstallmentFinancingNoteUi {
    NEEDS_SAME_CURRENCY_PURCHASE,
    NOT_AVAILABLE_FOR_STATUS,
    PAYMENT_UNREADABLE
}

data class InstallmentEntryRowUi(
    val entryId: Long,
    val seq: Int,
    val dueEpochDay: Long,
    val label: InstallmentEntryLabelUi,
    val reason: InstallmentEntryReasonUi?,
    val amount: MoneyDisplayUi,
    /** Original scheduled amount, only when it differs from the current one (the entry was adjusted). */
    val originalAmount: MoneyDisplayUi?,
    /** PAID entries only: non-zero paid minus scheduled, or unavailable; null for other statuses or no difference. */
    val variance: InstallmentAmountUi?,
    /** CONFIRM-mode ACTIVE plan, due today or overdue, not yet confirmed or linked (AE-10b). */
    val awaitingConfirmation: Boolean
)

data class InstallmentPlanDetailContent(
    val planId: Long,
    val displayName: String,
    val lenderLabel: String?,
    val currencyCode: String,
    val kind: InstallmentPlanKind,
    val status: InstallmentPlanStatus,
    val fulfilmentMode: InstallmentFulfilmentMode,
    /** n = paid + paid before tracking (owner-approvals 47(a)). */
    val paidCount: Int,
    /** N = n + scheduled + skipped (owner-approvals 47(a)). */
    val totalCount: Int,
    val scheduledCount: Int,
    val remaining: MoneyDisplayUi,
    val paidSoFar: InstallmentAmountUi,
    val financingCost: InstallmentAmountUi,
    val financingNote: InstallmentFinancingNoteUi?,
    /** Last due date among the entries still part of the plan; null is shown as "Unavailable". */
    val endEpochDay: Long?,
    val awaitingCount: Int,
    val awaitingAmount: MoneyDisplayUi?,
    val entries: List<InstallmentEntryRowUi>
)

data class InstallmentPlanDetailUiState(
    val loadable: LoadableUiState<InstallmentPlanDetailContent> = LoadableUiState.Loading,
    /** Bounded code of the last failure, or null. */
    val errorCode: InstallmentDetailErrorCode? = null,
    /** True when a refresh failed but the last loaded content is still shown. */
    val refreshFailed: Boolean = false
)

/** Pure mapping from the read model to the detail UI state. */
internal object InstallmentPlanDetailMapper {

    fun toContent(detail: InstallmentPlanDetail): InstallmentPlanDetailContent {
        val summary = detail.summary
        val currency = summary.currency
        val paid = summary.counts.paid + summary.counts.paidBeforeTracking
        val total = paid + summary.counts.scheduled + summary.counts.skipped
        val awaitingMode = summary.status == InstallmentPlanStatus.ACTIVE &&
            summary.fulfilmentMode == InstallmentFulfilmentMode.CONFIRM
        val awaitingEntries = detail.entries.filter { awaitingMode && isAwaiting(it) }
        return InstallmentPlanDetailContent(
            planId = summary.planId,
            displayName = summary.displayName,
            lenderLabel = detail.lenderLabel?.takeIf { it.isNotBlank() },
            currencyCode = currency.code,
            kind = detail.kind,
            status = summary.status,
            fulfilmentMode = summary.fulfilmentMode,
            paidCount = paid,
            totalCount = total,
            scheduledCount = summary.counts.scheduled,
            remaining = installmentMoney(summary.remainingScheduledMinor, currency),
            paidSoFar = installmentAmountUi(summary.paidSoFar, currency),
            financingCost = installmentAmountUi(detail.financingCost, currency),
            financingNote = financingNote(detail.financingCost),
            endEpochDay = summary.endEpochDay,
            awaitingCount = awaitingEntries.size,
            awaitingAmount = if (awaitingEntries.isEmpty()) {
                null
            } else {
                installmentMoney(awaitingEntries.sumOf { it.scheduledMinor }, currency)
            },
            entries = detail.entries.map { row(it, currency, awaitingMode && isAwaiting(it)) }
        )
    }

    private fun isAwaiting(entry: InstallmentEntryLine): Boolean =
        entry.status == InstallmentEntryStatus.SCHEDULED &&
            (entry.dueState == InstallmentDueState.DUE || entry.dueState == InstallmentDueState.OVERDUE)

    private fun row(
        entry: InstallmentEntryLine,
        currency: InstallmentCurrency,
        awaiting: Boolean
    ): InstallmentEntryRowUi = InstallmentEntryRowUi(
        entryId = entry.entryId,
        seq = entry.seq,
        dueEpochDay = entry.dueEpochDay,
        label = label(entry),
        reason = reason(entry),
        amount = installmentMoney(entry.scheduledMinor, currency),
        originalAmount = entry.originalScheduledMinor
            ?.takeIf { it != entry.scheduledMinor }
            ?.let { installmentMoney(it, currency) },
        // A zero variance (paid exactly as scheduled) is not shown; unavailable stays visible (INV-17).
        variance = entry.variance
            ?.takeUnless { it is InstallmentAmount.Available && it.minor == 0L }
            ?.let { installmentAmountUi(it, currency) },
        awaitingConfirmation = awaiting
    )

    private fun label(entry: InstallmentEntryLine): InstallmentEntryLabelUi = when (entry.status) {
        InstallmentEntryStatus.SCHEDULED -> when (entry.dueState) {
            InstallmentDueState.DUE -> InstallmentEntryLabelUi.DUE_TODAY
            InstallmentDueState.OVERDUE -> InstallmentEntryLabelUi.OVERDUE
            InstallmentDueState.UPCOMING, null -> InstallmentEntryLabelUi.UPCOMING
        }
        InstallmentEntryStatus.PAID ->
            if (entry.kind == InstallmentEntryKind.PAYOFF) InstallmentEntryLabelUi.PAYOFF else InstallmentEntryLabelUi.PAID
        InstallmentEntryStatus.SKIPPED -> InstallmentEntryLabelUi.SKIPPED
        InstallmentEntryStatus.CANCELLED -> InstallmentEntryLabelUi.CANCELLED
        InstallmentEntryStatus.REVERSED -> InstallmentEntryLabelUi.REFUNDED
        InstallmentEntryStatus.PAID_BEFORE_TRACKING -> InstallmentEntryLabelUi.PAID_BEFORE_TRACKING
    }

    /** Only reasons that belong to the entry status are surfaced; unknown stored codes are dropped. */
    private fun reason(entry: InstallmentEntryLine): InstallmentEntryReasonUi? {
        val code = InstallmentStatusReason.fromCodeOrNull(entry.statusReason) ?: return null
        return when (entry.status) {
            InstallmentEntryStatus.SKIPPED -> when (code) {
                InstallmentStatusReason.USER_SKIP_LENDER_HOLIDAY -> InstallmentEntryReasonUi.SKIP_LENDER_HOLIDAY
                InstallmentStatusReason.USER_SKIP_WAIVED -> InstallmentEntryReasonUi.SKIP_WAIVED
                InstallmentStatusReason.USER_SKIP_OTHER -> InstallmentEntryReasonUi.SKIP_OTHER
                else -> null
            }
            InstallmentEntryStatus.CANCELLED -> when (code) {
                InstallmentStatusReason.PLAN_STOPPED -> InstallmentEntryReasonUi.PLAN_STOPPED
                InstallmentStatusReason.PLAN_REFUNDED -> InstallmentEntryReasonUi.PLAN_REFUNDED
                InstallmentStatusReason.PAID_OFF_EARLY -> InstallmentEntryReasonUi.PAID_OFF_EARLY
                InstallmentStatusReason.SHORTENED -> InstallmentEntryReasonUi.SHORTENED
                InstallmentStatusReason.EXPENSE_REMOVED -> InstallmentEntryReasonUi.EXPENSE_REMOVED
                else -> null
            }
            InstallmentEntryStatus.SCHEDULED ->
                if (code == InstallmentStatusReason.EXPENSE_REMOVED) InstallmentEntryReasonUi.EXPENSE_REMOVED else null
            else -> null
        }
    }

    private fun financingNote(cost: InstallmentAmount): InstallmentFinancingNoteUi? = when (cost) {
        is InstallmentAmount.Available -> null
        is InstallmentAmount.Unavailable -> when (cost.reason) {
            InstallmentUnavailableReason.NO_PURCHASE,
            InstallmentUnavailableReason.CURRENCY_DIFFERS -> InstallmentFinancingNoteUi.NEEDS_SAME_CURRENCY_PURCHASE
            InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE -> InstallmentFinancingNoteUi.NOT_AVAILABLE_FOR_STATUS
            InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT,
            InstallmentUnavailableReason.MISSING_LINKED_EXPENSE -> InstallmentFinancingNoteUi.PAYMENT_UNREADABLE
        }
    }
}

/**
 * Read-only detail of one installment plan (loan-installments S10a-2). It reads only through
 * [InstallmentPlanReadRepository] and never writes; row actions, edit and end flows are S10b.
 * An invalid, missing or unreadable plan id shows a fixed not-found state (bounded code). Failures
 * map to bounded codes and fixed strings; an exception message never reaches the UI or the log (INV-31).
 */
@HiltViewModel
class InstallmentPlanDetailViewModel @Inject constructor(
    private val readRepository: InstallmentPlanReadRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InstallmentPlanDetailUiState())
    val state: StateFlow<InstallmentPlanDetailUiState> = _state.asStateFlow()

    private var planId: Long? = null
    private var collectJob: Job? = null

    /** Starts observing [planId]; calling it again with the same id is a no-op. */
    fun start(planId: Long) {
        if (this.planId == planId) return
        this.planId = planId
        _state.value = InstallmentPlanDetailUiState()
        observe(planId)
    }

    /**
     * Stops observing when the screen leaves composition. The ViewModel is activity-scoped (no
     * NavHost), so without this the collection would run for the life of the activity. A later
     * [start] restarts cleanly, even with the same id.
     */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        planId = null
    }

    /** Restarts the observation after a failure (Retry). Not offered for NOT_FOUND. */
    fun retry() {
        val id = planId ?: return
        if (_state.value.loadable !is LoadableUiState.Data) {
            _state.value = InstallmentPlanDetailUiState()
        } else {
            _state.value = _state.value.copy(refreshFailed = false, errorCode = null)
        }
        observe(id)
    }

    private fun observe(id: Long) {
        collectJob?.cancel()
        collectJob = null
        if (id <= 0L) {
            // An id that can never name a plan is not-found without touching the repository.
            _state.value = notFoundState()
            return
        }
        collectJob = viewModelScope.launch {
            readRepository.observeDetail(id)
                .map { detail ->
                    if (detail == null) {
                        notFoundState()
                    } else {
                        InstallmentPlanDetailUiState(
                            loadable = LoadableUiState.Data(InstallmentPlanDetailMapper.toContent(detail))
                        )
                    }
                }
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    onReadFailed()
                }
                .collect { mapped -> _state.value = mapped }
        }
    }

    private fun notFoundState() = InstallmentPlanDetailUiState(
        loadable = LoadableUiState.Error(UiText.from(R.string.installments_error_not_found)),
        errorCode = InstallmentDetailErrorCode.NOT_FOUND
    )

    private fun onReadFailed() {
        val current = _state.value
        val hasContent = current.loadable is LoadableUiState.Data
        val code = if (hasContent) InstallmentDetailErrorCode.REFRESH_FAILED else InstallmentDetailErrorCode.LOAD_FAILED
        // Bounded code only: no exception text, plan name, amount or id (INV-31).
        Timber.w("Installment detail read failed: code=%s", code.name)
        _state.value = if (hasContent) {
            current.copy(refreshFailed = true, errorCode = code)
        } else {
            InstallmentPlanDetailUiState(
                loadable = LoadableUiState.Error(UiText.from(R.string.installments_error_load_detail)),
                errorCode = code
            )
        }
    }
}
