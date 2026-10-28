package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentCandidateRule
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

// ---------------------------------------------------------------------------------------------
// Row marks (loan-installments S12a). Pure UI join of expense ids to plan facts. Nothing is stored:
// the chip "Installment n of N" is built from the plan read model, never from stored text (OQ-22).
// ---------------------------------------------------------------------------------------------

sealed interface InstallmentRowMark {
    val planId: Long

    /** The purchase record of a plan: chips "Paid in N installments" + "Not counted as spending". */
    data class PurchaseRecord(override val planId: Long, val installmentCount: Int) : InstallmentRowMark

    /**
     * An expense linked by an installment entry. [reversed] = refunded ("Not counted (refunded)").
     * [showSequence] is true for REGULAR entries only: a PAYOFF entry (seq 0) shows the source badge
     * without an "Installment n of N" chip.
     */
    data class Payment(
        override val planId: Long,
        val seq: Int,
        val total: Int,
        val reversed: Boolean,
        val showSequence: Boolean = true
    ) : InstallmentRowMark
}

/** A PAID regular entry, the reference of the AE-17 "possible duplicate" check. Numbers only. */
data class InstallmentPaidEntryRef(
    val planId: Long,
    val seq: Int,
    val currencyCode: String,
    val dueEpochDay: Long,
    val scheduledMinor: Long
)

data class InstallmentRowMarks(
    val byExpenseId: Map<Long, InstallmentRowMark> = emptyMap(),
    /** AE-17 part 1: expense id to the sequence number of the entry it is suggested for. */
    val suggestionSeqByExpenseId: Map<Long, Int> = emptyMap(),
    val paidEntries: List<InstallmentPaidEntryRef> = emptyList()
) {
    companion object {
        val EMPTY = InstallmentRowMarks()
    }
}

/** Plain inputs of [InstallmentRowMarksLoader.build]; mapped from the read model, testable without it. */
data class MarkPlanInput(
    val planId: Long,
    val currencyCode: String,
    val purchaseExpenseId: Long?,
    val entries: List<MarkEntryInput>
)

data class MarkEntryInput(
    val seq: Int,
    val kind: InstallmentEntryKind,
    val status: InstallmentEntryStatus,
    val linkedExpenseId: Long?,
    val dueEpochDay: Long,
    val scheduledMinor: Long
)

/**
 * Builds [InstallmentRowMarks] through the existing read repository only (no new store query, no
 * write). While the feature is disabled nothing is read. A failed read returns null; the caller then
 * shows no marks (fail closed: a mark is never guessed).
 *
 * PE-D: one snapshot read ([InstallmentPlanReadRepository.loadBudgetInputs], every readable plan of any
 * status with all its entries) plus [InstallmentPlanReadRepository.loadSuggestions], instead of an
 * overview, a history and one detail read per plan. The plans are the same set (ACTIVE plus the history
 * statuses), and the entry fields are the ones the detail read carried, so the marks are unchanged.
 */
class InstallmentRowMarksLoader @Inject constructor(
    private val readRepository: InstallmentPlanReadRepository
) {
    suspend fun load(enabled: Boolean = InstallmentsFeature.ENABLED): InstallmentRowMarks? {
        if (!enabled) return InstallmentRowMarks.EMPTY
        return try {
            val inputs = readRepository.loadBudgetInputs().plans.map { plan ->
                MarkPlanInput(
                    planId = plan.planId,
                    currencyCode = plan.currency.code,
                    purchaseExpenseId = plan.purchaseExpenseId,
                    // Same order as the detail read (due date, seq, entry id): first-match rules stay stable.
                    entries = plan.entries.sortedWith(
                        compareBy({ it.money.dueEpochDay }, { it.seq }, { it.entryId })
                    ).map {
                        MarkEntryInput(
                            seq = it.seq,
                            kind = it.money.kind,
                            status = it.money.status,
                            linkedExpenseId = it.linkedExpenseId,
                            dueEpochDay = it.money.dueEpochDay,
                            scheduledMinor = it.money.scheduledMinor
                        )
                    }
                )
            }
            val suggestions = readRepository.loadSuggestions()
                .groupBy { it.expenseId }
                .mapValues { (_, list) -> list.first().seq }
            build(inputs, suggestions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Transactions installment marks failed: class=%s", e::class.java.simpleName)
            null
        }
    }

    companion object {
        /** N of "Installment n of N" and "Paid in N installments": the REGULAR entries of the plan. */
        fun build(plans: List<MarkPlanInput>, suggestionSeqByExpenseId: Map<Long, Int> = emptyMap()): InstallmentRowMarks {
            val marks = HashMap<Long, InstallmentRowMark>()
            val paid = ArrayList<InstallmentPaidEntryRef>()
            for (plan in plans) {
                val total = plan.entries.count { it.kind == InstallmentEntryKind.REGULAR }
                plan.purchaseExpenseId?.let { marks[it] = InstallmentRowMark.PurchaseRecord(plan.planId, total) }
                for (entry in plan.entries) {
                    val linked = entry.linkedExpenseId ?: continue
                    val reversed = entry.status == InstallmentEntryStatus.REVERSED
                    if (entry.status != InstallmentEntryStatus.PAID && !reversed) continue
                    // A purchase record keeps its own mark if an id were ever both (never expected).
                    if (marks.containsKey(linked)) continue
                    marks[linked] = InstallmentRowMark.Payment(
                        plan.planId, entry.seq, total, reversed,
                        showSequence = entry.kind == InstallmentEntryKind.REGULAR
                    )
                    if (entry.status == InstallmentEntryStatus.PAID && entry.kind == InstallmentEntryKind.REGULAR) {
                        paid += InstallmentPaidEntryRef(plan.planId, entry.seq, plan.currencyCode, entry.dueEpochDay, entry.scheduledMinor)
                    }
                }
            }
            return InstallmentRowMarks(marks, suggestionSeqByExpenseId, paid)
        }
    }
}

/**
 * AE-17 part 2: an unlinked captured expense that matches a PAID entry shortly after its due date is
 * shown as "possible duplicate of installment n". Nothing changes automatically. Same currency, amount
 * within [InstallmentCandidateRule.AMOUNT_TOLERANCE_MINOR] of the scheduled amount, civil date in
 * (due, due + [InstallmentCandidateRule.CONFIRM_DAYS_AFTER_DUE]]. The window is a proposal for the
 * domain-modeler to confirm (the spec example is one day after the due date).
 */
object InstallmentDuplicateRule {
    fun duplicateOfSeq(expense: Expense, marks: InstallmentRowMarks, zone: ZoneId): Int? {
        if (marks.paidEntries.isEmpty() || marks.byExpenseId.containsKey(expense.id)) return null
        if (!InstallmentExpenseFacts.isSpendingType(expense.transactionType)) return null
        val minor = InstallmentMinorUnits.toMinorOrNull(expense.amount) ?: return null
        val day = InstallmentScheduleCalculator.civilEpochDay(expense.date, zone)
        return marks.paidEntries
            .filter {
                it.currencyCode == expense.currency &&
                    kotlin.math.abs(minor - it.scheduledMinor) <= InstallmentCandidateRule.AMOUNT_TOLERANCE_MINOR &&
                    day > it.dueEpochDay &&
                    day <= it.dueEpochDay + InstallmentCandidateRule.CONFIRM_DAYS_AFTER_DUE
            }
            .minByOrNull { day - it.dueEpochDay }
            ?.seq
    }
}

enum class InstallmentMenuDisabledReason { NOT_MINE, SHARED_OR_SPLIT }

/** Row overflow variants (ux-flows 3.2). */
sealed interface InstallmentMenuAction {
    data object Hidden : InstallmentMenuAction
    data object PayInInstallments : InstallmentMenuAction
    data class ViewPlan(val planId: Long) : InstallmentMenuAction
    data class Disabled(val reason: InstallmentMenuDisabledReason) : InstallmentMenuAction
}

/** Everything one row needs; null while the feature is disabled. */
data class InstallmentRowUi(
    val mark: InstallmentRowMark?,
    val suggestionSeq: Int?,
    val duplicateSeq: Int?,
    val menu: InstallmentMenuAction
) {
    companion object {
        fun of(
            expense: Expense,
            marks: InstallmentRowMarks,
            zone: ZoneId = ZoneId.systemDefault(),
            enabled: Boolean = InstallmentsFeature.ENABLED
        ): InstallmentRowUi? {
            if (!enabled) return null
            val mark = marks.byExpenseId[expense.id]
            return InstallmentRowUi(
                mark = mark,
                suggestionSeq = marks.suggestionSeqByExpenseId[expense.id],
                duplicateSeq = InstallmentDuplicateRule.duplicateOfSeq(expense, marks, zone),
                menu = menuFor(expense, mark)
            )
        }

        /**
         * Non-spending types: hidden. Already a plan purchase or an installment: View plan. Not mine /
         * shared / split: disabled with a reason. Other states (for example a recurring link) are
         * decided by the coordinator, which refuses with a fixed message.
         */
        fun menuFor(expense: Expense, mark: InstallmentRowMark?): InstallmentMenuAction = when {
            !InstallmentExpenseFacts.isSpendingType(expense.transactionType) -> InstallmentMenuAction.Hidden
            mark != null -> InstallmentMenuAction.ViewPlan(mark.planId)
            expense.isNotMine -> InstallmentMenuAction.Disabled(InstallmentMenuDisabledReason.NOT_MINE)
            expense.isSharedExpense || InstallmentExpenseFacts.hasSplit(expense.splitTemplateId, expense.splitVisualization) ->
                InstallmentMenuAction.Disabled(InstallmentMenuDisabledReason.SHARED_OR_SPLIT)
            // Flagged but unreadable (no mark): never offer a second declaration.
            expense.spendingExclusionReason != null -> InstallmentMenuAction.Hidden
            else -> InstallmentMenuAction.PayInInstallments
        }
    }
}

/**
 * The "load the next page" predicate of the Transactions list (F3). Filter off: the near-the-end
 * scroll trigger, exactly as before (isLoading is not consulted). Filter on: the filtered list can be
 * empty although more pages exist, so keep paging until the end, but never while the initial load or a
 * refresh runs (loadMore returns early then and would be lost).
 */
object TransactionsLoadMorePolicy {
    fun shouldLoadMore(
        isAllTab: Boolean,
        installmentFilterOn: Boolean,
        nearEnd: Boolean,
        isLoading: Boolean,
        isLoadingMore: Boolean,
        hasReachedEnd: Boolean
    ): Boolean = isAllTab &&
        (if (installmentFilterOn) !isLoading else nearEnd) &&
        !isLoadingMore &&
        !hasReachedEnd

    /**
     * PE-D: the key of the load-more effect. Filter off: the constant -1, so the effect is keyed by
     * [shouldLoadMore] alone, exactly as before. Filter on: the loaded base row count, [hasReachedEnd]
     * and [isLoading] (never the isLoadingMore toggle). A completed page changes the count and a
     * refresh changes [isLoading], so the effect restarts; a failed page changes none of them, so the
     * effect does not relaunch and a permanently failing page is not retried in a loop.
     */
    fun effectKey(
        installmentFilterOn: Boolean,
        baseRowCount: Int,
        hasReachedEnd: Boolean,
        isLoading: Boolean
    ): List<Any> = if (installmentFilterOn) listOf(baseRowCount, hasReachedEnd, isLoading) else NO_KEY

    private val NO_KEY: List<Any> = listOf(-1)
}

/**
 * Separate from [TransactionsViewModel] on purpose (overlap file): holds the installment row marks and
 * the installments filter. Dormant while [InstallmentsFeature.ENABLED] is false: [refresh] then reads
 * nothing and the filter cannot turn on. Reads only; every write is in the declare sheet.
 */
@HiltViewModel
class TransactionsInstallmentViewModel @Inject constructor(
    private val loader: InstallmentRowMarksLoader
) : ViewModel() {

    private val _marks = MutableStateFlow(InstallmentRowMarks.EMPTY)
    val marks: StateFlow<InstallmentRowMarks> = _marks.asStateFlow()

    private val _filterOn = MutableStateFlow(false)
    val filterOn: StateFlow<Boolean> = _filterOn.asStateFlow()

    private var loadJob: Job? = null

    fun refresh(enabled: Boolean = InstallmentsFeature.ENABLED) {
        if (!enabled) {
            _marks.value = InstallmentRowMarks.EMPTY
            _filterOn.value = false
            return
        }
        // A newer refresh cancels both the debounce wait and a running read, so a slow older read can
        // never land after a newer one (CANCEL-01: the CancellationException is never swallowed).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            // CancellationException propagates out of load(); a failed read shows no marks.
            _marks.value = loader.load(enabled) ?: InstallmentRowMarks.EMPTY
        }
    }

    fun toggleFilter(enabled: Boolean = InstallmentsFeature.ENABLED) {
        if (!enabled) return
        _filterOn.value = !_filterOn.value
    }

    companion object {
        /** Coalesces bursts of base-list changes (paging, edits) into one marks read. */
        const val REFRESH_DEBOUNCE_MS = 250L

        /** The installments filter: rows with a plan mark only; empty date groups are dropped. */
        fun filterGrouped(
            grouped: Map<String, List<ExpenseWithCategory>>,
            marks: InstallmentRowMarks
        ): Map<String, List<ExpenseWithCategory>> =
            grouped.mapValues { (_, rows) -> rows.filter { it.expense.id in marks.byExpenseId } }
                .filterValues { it.isNotEmpty() }
    }
}
