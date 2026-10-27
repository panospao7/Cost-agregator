package com.yourname.expensetracker.ui.screens.installments.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentNextDue
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanSummary
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bounded failure codes of the overview (INV-31). Only these codes reach the UI and the log; the
 * text shown comes from fixed string resources, never from an exception message.
 */
enum class InstallmentOverviewErrorCode { LOAD_FAILED, REFRESH_FAILED }

/** Which empty variant to show (ux-flows 3.4): nothing ever, or only finished plans. */
enum class InstallmentOverviewEmptyKind { NO_PLANS, NO_ACTIVE_PLANS }

/** A money value that is either known or explicitly unavailable; unavailable is never rendered as zero (INV-17). */
sealed interface InstallmentAmountUi {
    data class Money(val display: MoneyDisplayUi) : InstallmentAmountUi
    data class Unavailable(val reason: InstallmentUnavailableReason) : InstallmentAmountUi
}

/** One "Remaining scheduled payments" line; exists only for a currency with an ACTIVE plan (AE-5, INV-16). */
data class InstallmentRemainingLineUi(
    val currencyCode: String,
    val remaining: MoneyDisplayUi,
    val planCount: Int
)

/** One "awaiting confirmation" line per currency; these payments are not counted yet (AE-10b). */
data class InstallmentAwaitingLineUi(
    val currencyCode: String,
    val count: Int,
    val amount: MoneyDisplayUi
)

data class InstallmentNextDueUi(
    val entryId: Long,
    val seq: Int,
    val dueEpochDay: Long,
    val dueState: InstallmentDueState,
    val amount: MoneyDisplayUi
)

data class InstallmentNextDueCardUi(
    val planId: Long,
    val displayName: String,
    val nextDue: InstallmentNextDueUi
)

data class InstallmentPlanCardUi(
    val planId: Long,
    val displayName: String,
    val currencyCode: String,
    val fulfilmentMode: InstallmentFulfilmentMode,
    val paidCount: Int,
    val totalCount: Int,
    val scheduledCount: Int,
    val remaining: MoneyDisplayUi,
    val paidSoFar: InstallmentAmountUi,
    val nextDue: InstallmentNextDueUi?,
    val endEpochDay: Long?,
    /** CONFIRM-mode plan whose next payment is due or overdue and not yet confirmed (AE-10b). */
    val awaitingConfirmation: Boolean
)

data class InstallmentOverviewContent(
    val remainingLines: List<InstallmentRemainingLineUi>,
    val awaitingLines: List<InstallmentAwaitingLineUi>,
    val nextDueCard: InstallmentNextDueCardUi?,
    val plans: List<InstallmentPlanCardUi>,
    /** Plans that could not be read; shown as a notice, never counted as zero (INV-17). */
    val unreadablePlanCount: Int
)

data class InstallmentOverviewUiState(
    val loadable: LoadableUiState<InstallmentOverviewContent> = LoadableUiState.Loading,
    val emptyKind: InstallmentOverviewEmptyKind? = null,
    /** Bounded code of the last failure, or null. */
    val errorCode: InstallmentOverviewErrorCode? = null,
    /** True when a refresh failed but the last loaded content is still shown. */
    val refreshFailed: Boolean = false
)

/** Pure mapping from the read model to the overview UI state. */
internal object InstallmentOverviewMapper {

    fun toState(overview: InstallmentOverview, finishedPlanCount: Int): InstallmentOverviewUiState {
        if (overview.plans.isEmpty() && overview.unreadablePlanCount == 0) {
            val kind = if (finishedPlanCount > 0) {
                InstallmentOverviewEmptyKind.NO_ACTIVE_PLANS
            } else {
                InstallmentOverviewEmptyKind.NO_PLANS
            }
            val body = when (kind) {
                InstallmentOverviewEmptyKind.NO_PLANS -> R.string.installments_empty_body
                InstallmentOverviewEmptyKind.NO_ACTIVE_PLANS -> R.string.installments_empty_finished_body
            }
            return InstallmentOverviewUiState(
                loadable = LoadableUiState.Empty(UiText.from(body)),
                emptyKind = kind
            )
        }
        val cards = overview.plans.map(::planCard)
        val planCurrencies = overview.plans.map { it.currency }.toSet()
        val content = InstallmentOverviewContent(
            // AE-5: a line only for a currency that has at least one plan (remaining keys ∩ plan currencies).
            remainingLines = overview.remainingScheduledByCurrency.entries
                .filter { (currency, _) -> planCurrencies.contains(currency) }
                .sortedBy { it.key.ordinal }
                .map { (currency, minor) ->
                    InstallmentRemainingLineUi(
                        currencyCode = currency.code,
                        remaining = money(minor, currency),
                        planCount = overview.plans.count { it.currency == currency }
                    )
                },
            awaitingLines = overview.awaitingConfirmationByCurrency.entries
                .sortedBy { it.key.ordinal }
                .map { (currency, totals) ->
                    InstallmentAwaitingLineUi(
                        currencyCode = currency.code,
                        count = totals.count,
                        amount = money(totals.minor, currency)
                    )
                },
            nextDueCard = nextDueCard(overview.plans),
            plans = cards,
            unreadablePlanCount = overview.unreadablePlanCount
        )
        return InstallmentOverviewUiState(loadable = LoadableUiState.Data(content))
    }

    /**
     * The nearest SCHEDULED entry that is not yet overdue; currencies are compared by date only.
     *
     * Known limitation: a plan whose earliest scheduled entry is OVERDUE contributes no candidate,
     * because the read model exposes only the earliest entry per plan; its later upcoming entry
     * needs the detail read (S10a-2/S10b attention rows).
     */
    private fun nextDueCard(plans: List<InstallmentPlanSummary>): InstallmentNextDueCardUi? =
        plans
            .mapNotNull { plan ->
                plan.nextDue
                    ?.takeIf { it.dueState != InstallmentDueState.OVERDUE }
                    ?.let { plan to it }
            }
            .minWithOrNull(compareBy({ it.second.dueEpochDay }, { it.first.planId }))
            ?.let { (plan, due) ->
                InstallmentNextDueCardUi(plan.planId, plan.displayName, nextDue(due, plan.currency))
            }

    private fun planCard(plan: InstallmentPlanSummary): InstallmentPlanCardUi {
        val paid = plan.counts.paid + plan.counts.paidBeforeTracking
        val total = paid + plan.counts.scheduled + plan.counts.skipped
        val due = plan.nextDue
        return InstallmentPlanCardUi(
            planId = plan.planId,
            displayName = plan.displayName,
            currencyCode = plan.currency.code,
            fulfilmentMode = plan.fulfilmentMode,
            paidCount = paid,
            totalCount = total,
            scheduledCount = plan.counts.scheduled,
            remaining = money(plan.remainingScheduledMinor, plan.currency),
            paidSoFar = amount(plan.paidSoFar, plan.currency),
            nextDue = due?.let { nextDue(it, plan.currency) },
            endEpochDay = plan.endEpochDay,
            awaitingConfirmation = plan.fulfilmentMode == InstallmentFulfilmentMode.CONFIRM &&
                due != null &&
                (due.dueState == InstallmentDueState.DUE || due.dueState == InstallmentDueState.OVERDUE)
        )
    }

    private fun nextDue(due: InstallmentNextDue, currency: InstallmentCurrency) = InstallmentNextDueUi(
        entryId = due.entryId,
        seq = due.seq,
        dueEpochDay = due.dueEpochDay,
        dueState = due.dueState,
        amount = money(due.scheduledMinor, currency)
    )

    private fun amount(value: InstallmentAmount, currency: InstallmentCurrency): InstallmentAmountUi =
        when (value) {
            is InstallmentAmount.Available -> InstallmentAmountUi.Money(money(value.minor, currency))
            is InstallmentAmount.Unavailable -> InstallmentAmountUi.Unavailable(value.reason)
        }

    /** Minor units to a displayed amount with its explicit currency (2 decimals for EUR and USD). */
    private fun money(minor: Long, currency: InstallmentCurrency): MoneyDisplayUi =
        MoneyDisplayUi.from(InstallmentMinorUnits.fromMinor(minor), currency.code)
}

/**
 * Overview of the ACTIVE installment plans (loan-installments S10a-1). Read-only: it reads only
 * through [InstallmentPlanReadRepository] and never writes. Failures map to bounded codes and fixed
 * strings; an exception message never reaches the UI or the log (INV-31).
 */
@HiltViewModel
class InstallmentOverviewViewModel @Inject constructor(
    private val readRepository: InstallmentPlanReadRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InstallmentOverviewUiState())
    val state: StateFlow<InstallmentOverviewUiState> = _state.asStateFlow()

    private var collectJob: Job? = null

    init {
        start()
    }

    /** Restarts the observation after a failure (Retry). */
    fun retry() {
        if (_state.value.loadable !is LoadableUiState.Data) {
            _state.value = InstallmentOverviewUiState()
        } else {
            _state.value = _state.value.copy(refreshFailed = false, errorCode = null)
        }
        start()
    }

    private fun start() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            combine(readRepository.observeOverview(), finishedPlanCount()) { overview, finished ->
                InstallmentOverviewMapper.toState(overview, finished)
            }
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    onReadFailed()
                }
                .collect { mapped -> _state.value = mapped }
        }
    }

    /** History size, used only to choose the empty variant; a failure there never blocks the overview. */
    private fun finishedPlanCount(): Flow<Int> =
        readRepository.observeHistory()
            .map { it.size }
            // First paint must not wait for the history load: start from 0 and refine when it arrives.
            // Follow-up (S10a-2): replace this second full load with a count-only read.
            .onStart { emit(0) }
            .catch { cause ->
                if (cause is CancellationException) throw cause
                emit(0)
            }

    private fun onReadFailed() {
        val current = _state.value
        val hasContent = current.loadable is LoadableUiState.Data
        val code = if (hasContent) InstallmentOverviewErrorCode.REFRESH_FAILED else InstallmentOverviewErrorCode.LOAD_FAILED
        // Bounded code only: no exception text, plan name, amount or id (INV-31).
        Timber.w("Installment overview read failed: code=%s", code.name)
        _state.value = if (hasContent) {
            current.copy(refreshFailed = true, errorCode = code)
        } else {
            InstallmentOverviewUiState(
                loadable = LoadableUiState.Error(UiText.from(R.string.installments_error_load)),
                errorCode = code
            )
        }
    }
}
