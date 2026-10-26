package com.yourname.expensetracker.domain.installment.readmodel

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import java.util.EnumMap

// ---------------------------------------------------------------------------------------------
// Loaded data (input of the pure read model). Everything is already in Long minor units.
// ---------------------------------------------------------------------------------------------

/** One entry as the screens need it: the math input plus identity and audit fields. */
data class EntrySnapshot(
    val entryId: Long,
    val seq: Int,
    val originalScheduledMinor: Long?,
    val linkedExpenseId: Long?,
    /** Bounded reason code as stored, or null. Never free text. */
    val statusReason: String?,
    val money: EntryMoney
)

/** A plan with its entries and its purchase money, ready for the pure read model. */
data class PlanSnapshot(
    val planId: Long,
    val planUuid: String,
    val displayName: String,
    val lenderLabel: String?,
    val kind: InstallmentPlanKind,
    val fulfilmentMode: InstallmentFulfilmentMode,
    val status: InstallmentPlanStatus,
    val currency: InstallmentCurrency,
    val purchaseExpenseId: Long?,
    val purchase: PurchaseMoney?,
    /** Civil date the plan reached its final status, or null when unknown (INV-26). */
    val terminalEpochDay: Long?,
    val entries: List<EntrySnapshot>
) {
    /** The input of [InstallmentPlanMath]. */
    fun moneyInput(): PlanMoneyInput = PlanMoneyInput(
        status = status,
        currency = currency,
        purchase = purchase,
        entries = entries.map { it.money }
    )
}

/** An unlinked expense considered for a suggested link; money is the gross charge in minor units. */
data class CandidateExpense(
    val expenseId: Long,
    val currency: String,
    val grossMinor: Long,
    /** Civil date of the expense in the caller's zone. */
    val dateEpochDay: Long
)

// ---------------------------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------------------------

/** The earliest SCHEDULED entry of a plan (an overdue one wins over a later one). */
data class InstallmentNextDue(
    val entryId: Long,
    val seq: Int,
    val dueEpochDay: Long,
    val scheduledMinor: Long,
    val dueState: InstallmentDueState
)

data class InstallmentPlanSummary(
    val planId: Long,
    val planUuid: String,
    val displayName: String,
    val currency: InstallmentCurrency,
    val status: InstallmentPlanStatus,
    val fulfilmentMode: InstallmentFulfilmentMode,
    val counts: InstallmentEntryCounts,
    val remainingScheduledMinor: Long,
    val paidSoFar: InstallmentAmount,
    val nextDue: InstallmentNextDue?,
    /** Last due date among the entries still part of the plan; null when there is none. */
    val endEpochDay: Long?
)

/** SCHEDULED entries of CONFIRM-mode ACTIVE plans that are due today or overdue; not counted until confirmed or linked (AE-10b). */
data class InstallmentAwaitingTotals(
    val count: Int,
    val minor: Long
)

data class InstallmentOverview(
    val plans: List<InstallmentPlanSummary>,
    /** Remaining scheduled payments per currency; a currency with no ACTIVE plan has no entry (INV-17). */
    val remainingScheduledByCurrency: Map<InstallmentCurrency, Long>,
    /** Per currency; a currency with nothing awaiting has no entry. */
    val awaitingConfirmationByCurrency: Map<InstallmentCurrency, InstallmentAwaitingTotals>,
    /** Plans that could not be read (unknown stored code or an unconvertible amount); never shown as zero. */
    val unreadablePlanCount: Int
)

data class InstallmentEntryLine(
    val entryId: Long,
    val seq: Int,
    val kind: InstallmentEntryKind,
    val status: InstallmentEntryStatus,
    val statusReason: String?,
    val dueEpochDay: Long,
    val scheduledMinor: Long,
    val originalScheduledMinor: Long?,
    val linkedExpenseId: Long?,
    val dueState: InstallmentDueState?,
    /** PAID entries only: linked effective amount minus scheduled amount, or unavailable. Null for other statuses. */
    val variance: InstallmentAmount?
)

/** A suggested link of an unlinked captured expense to a SCHEDULED entry (INV-13). Carries ids and numbers only. */
data class InstallmentLinkSuggestion(
    val planId: Long,
    val entryId: Long,
    val seq: Int,
    val expenseId: Long,
    val expenseGrossMinor: Long,
    val expenseDateEpochDay: Long,
    /** Expense gross minus scheduled amount, within the tolerance. */
    val deltaMinor: Long
)

data class InstallmentPlanDetail(
    val summary: InstallmentPlanSummary,
    val kind: InstallmentPlanKind,
    val lenderLabel: String?,
    val purchaseExpenseId: Long?,
    val entries: List<InstallmentEntryLine>,
    val financingCost: InstallmentAmount,
    val suggestions: List<InstallmentLinkSuggestion>
)

data class InstallmentHistoryItem(
    val planId: Long,
    val planUuid: String,
    val displayName: String,
    val summary: InstallmentHistorySummary
)

// ---------------------------------------------------------------------------------------------
// Candidate rule (INV-13 and domain spec section 8.2)
// ---------------------------------------------------------------------------------------------

/**
 * The candidate-charge rule as a pure predicate with a mode-specific date window. Eligibility of the
 * expense itself (not a plan purchase record, not linked to an entry or occurrence, a spending type)
 * is decided by the caller through `ExpenseScheduleLinkPolicy`; this object decides currency,
 * amount and date only.
 *
 * Windows, as civil dates around the entry due date:
 * - AUTO_RECORD plans (INV-13): [due - [AUTO_RECORD_DAYS_BEFORE_DUE], due + [AUTO_RECORD_DAYS_AFTER_DUE]],
 *   that is [due - 3 days, due]. This is also the candidate and deferral window the coordinator
 *   auto-record step reuses (S7a) through [autoRecordWindowStartEpochDay] and
 *   [autoRecordWindowEndEpochDay]; it must not define a second copy of the rule.
 * - CONFIRM plans (spec section 8.2): [due - [CONFIRM_DAYS_BEFORE_DUE], due + [CONFIRM_DAYS_AFTER_DUE]],
 *   that is [due - 5 days, due + 10 days].
 *
 * Amount tolerance for both modes: same currency and |gross - scheduled| at most
 * [AMOUNT_TOLERANCE_MINOR] (0.01, one minor unit).
 */
object InstallmentCandidateRule {
    const val AUTO_RECORD_DAYS_BEFORE_DUE: Long = 3L
    const val AUTO_RECORD_DAYS_AFTER_DUE: Long = 0L
    const val CONFIRM_DAYS_BEFORE_DUE: Long = 5L
    const val CONFIRM_DAYS_AFTER_DUE: Long = 10L
    const val AMOUNT_TOLERANCE_MINOR: Long = 1L

    /** First civil day of the INV-13 AUTO_RECORD window of an entry due on [dueEpochDay]. */
    fun autoRecordWindowStartEpochDay(dueEpochDay: Long): Long = dueEpochDay - AUTO_RECORD_DAYS_BEFORE_DUE

    /** Last civil day (inclusive) of the INV-13 AUTO_RECORD window of an entry due on [dueEpochDay]. */
    fun autoRecordWindowEndEpochDay(dueEpochDay: Long): Long = dueEpochDay + AUTO_RECORD_DAYS_AFTER_DUE

    /** First civil day of the window of an entry due on [dueEpochDay] in a plan of [mode]. */
    fun windowStartEpochDay(mode: InstallmentFulfilmentMode, dueEpochDay: Long): Long = when (mode) {
        InstallmentFulfilmentMode.AUTO_RECORD -> autoRecordWindowStartEpochDay(dueEpochDay)
        InstallmentFulfilmentMode.CONFIRM -> dueEpochDay - CONFIRM_DAYS_BEFORE_DUE
    }

    /** Last civil day (inclusive) of the window of an entry due on [dueEpochDay] in a plan of [mode]. */
    fun windowEndEpochDay(mode: InstallmentFulfilmentMode, dueEpochDay: Long): Long = when (mode) {
        InstallmentFulfilmentMode.AUTO_RECORD -> autoRecordWindowEndEpochDay(dueEpochDay)
        InstallmentFulfilmentMode.CONFIRM -> dueEpochDay + CONFIRM_DAYS_AFTER_DUE
    }

    fun matches(
        mode: InstallmentFulfilmentMode,
        planCurrency: InstallmentCurrency,
        entryDueEpochDay: Long,
        entryScheduledMinor: Long,
        candidate: CandidateExpense
    ): Boolean =
        candidate.currency == planCurrency.code &&
            candidate.dateEpochDay in
            windowStartEpochDay(mode, entryDueEpochDay)..windowEndEpochDay(mode, entryDueEpochDay) &&
            kotlin.math.abs(candidate.grossMinor - entryScheduledMinor) <= AMOUNT_TOLERANCE_MINOR
}

// ---------------------------------------------------------------------------------------------
// Pure read model
// ---------------------------------------------------------------------------------------------

/**
 * Pure assembly of the overview, detail, history and suggestions (domain spec sections 9 and 11.1;
 * INV-13, INV-16, INV-17, INV-26). No I/O, no clock: "today" is passed in as a civil epoch day.
 * Money math is delegated to [InstallmentPlanMath]; totals stay per currency and are never summed
 * across currencies.
 */
object InstallmentPlanReadModel {

    /** Overview of the ACTIVE plans (COMPLETED plans that reopen come back as ACTIVE, spec 3.1). */
    fun overview(
        plans: List<PlanSnapshot>,
        todayEpochDay: Long,
        unreadablePlanCount: Int = 0
    ): InstallmentOverview {
        val active = plans.filter { it.status == InstallmentPlanStatus.ACTIVE }
        val summaries = active
            .map { summary(it, todayEpochDay) }
            .sortedWith(compareBy({ it.nextDue?.dueEpochDay ?: Long.MAX_VALUE }, { it.planId }))
        val awaiting = EnumMap<InstallmentCurrency, InstallmentAwaitingTotals>(InstallmentCurrency::class.java)
        for (plan in active) {
            if (plan.fulfilmentMode != InstallmentFulfilmentMode.CONFIRM) continue
            val due = plan.entries.filter {
                val state = InstallmentPlanMath.dueState(it.money.status, it.money.dueEpochDay, todayEpochDay)
                state == InstallmentDueState.DUE || state == InstallmentDueState.OVERDUE
            }
            if (due.isEmpty()) continue
            val before = awaiting[plan.currency]
            awaiting[plan.currency] = InstallmentAwaitingTotals(
                count = (before?.count ?: 0) + due.size,
                minor = (before?.minor ?: 0L) + due.sumOf { it.money.scheduledMinor }
            )
        }
        return InstallmentOverview(
            plans = summaries,
            remainingScheduledByCurrency = InstallmentPlanMath.remainingScheduledByCurrency(active.map { it.moneyInput() }),
            awaitingConfirmationByCurrency = awaiting,
            unreadablePlanCount = unreadablePlanCount
        )
    }

    fun summary(plan: PlanSnapshot, todayEpochDay: Long): InstallmentPlanSummary {
        val money = plan.entries.map { it.money }
        val nextDue = plan.entries
            .filter { it.money.status == InstallmentEntryStatus.SCHEDULED }
            .minWithOrNull(compareBy({ it.money.dueEpochDay }, { it.seq }, { it.entryId }))
            ?.let { e ->
                InstallmentNextDue(
                    entryId = e.entryId,
                    seq = e.seq,
                    dueEpochDay = e.money.dueEpochDay,
                    scheduledMinor = e.money.scheduledMinor,
                    dueState = InstallmentPlanMath.dueState(e.money.status, e.money.dueEpochDay, todayEpochDay)
                        ?: InstallmentDueState.UPCOMING
                )
            }
        val end = money
            .filter {
                it.status == InstallmentEntryStatus.SCHEDULED ||
                    it.status == InstallmentEntryStatus.PAID ||
                    it.status == InstallmentEntryStatus.PAID_BEFORE_TRACKING ||
                    it.status == InstallmentEntryStatus.SKIPPED
            }
            .maxOfOrNull { it.dueEpochDay }
        return InstallmentPlanSummary(
            planId = plan.planId,
            planUuid = plan.planUuid,
            displayName = plan.displayName,
            currency = plan.currency,
            status = plan.status,
            fulfilmentMode = plan.fulfilmentMode,
            counts = InstallmentPlanMath.entryCounts(money),
            remainingScheduledMinor = InstallmentPlanMath.remainingScheduledMinor(money),
            paidSoFar = InstallmentPlanMath.paidSoFar(plan.moneyInput()),
            nextDue = nextDue,
            endEpochDay = end
        )
    }

    /** Detail of one plan; [suggestions] come from [suggestionsFor] (the caller loads the candidates). */
    fun detail(
        plan: PlanSnapshot,
        todayEpochDay: Long,
        suggestions: List<InstallmentLinkSuggestion> = emptyList()
    ): InstallmentPlanDetail = InstallmentPlanDetail(
        summary = summary(plan, todayEpochDay),
        kind = plan.kind,
        lenderLabel = plan.lenderLabel,
        purchaseExpenseId = plan.purchaseExpenseId,
        entries = plan.entries
            .sortedWith(compareBy({ it.money.dueEpochDay }, { it.seq }, { it.entryId }))
            .map { entryLine(plan.currency, it, todayEpochDay) },
        financingCost = InstallmentPlanMath.financingCost(plan.moneyInput()),
        suggestions = suggestions
    )

    fun entryLine(currency: InstallmentCurrency, entry: EntrySnapshot, todayEpochDay: Long): InstallmentEntryLine =
        InstallmentEntryLine(
            entryId = entry.entryId,
            seq = entry.seq,
            kind = entry.money.kind,
            status = entry.money.status,
            statusReason = entry.statusReason,
            dueEpochDay = entry.money.dueEpochDay,
            scheduledMinor = entry.money.scheduledMinor,
            originalScheduledMinor = entry.originalScheduledMinor,
            linkedExpenseId = entry.linkedExpenseId,
            dueState = InstallmentPlanMath.dueState(entry.money.status, entry.money.dueEpochDay, todayEpochDay),
            variance = entryVariance(currency, entry.money)
        )

    /**
     * Variance of a PAID entry (spec: an edited installment expense keeps the entry PAID and the
     * read model shows the variance against the schedule; a not-mine expense counts 0, so the
     * variance is minus the scheduled amount). Unavailable, never 0, when the link is missing or
     * the linked expense is not in the plan currency. Null for entries that are not PAID.
     */
    fun entryVariance(currency: InstallmentCurrency, entry: EntryMoney): InstallmentAmount? {
        if (entry.status != InstallmentEntryStatus.PAID) return null
        val linked = entry.linkedExpense
            ?: return InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
        if (linked.currency != currency.code) {
            return InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
        }
        return InstallmentAmount.Available(InstallmentPlanMath.varianceMinor(entry.scheduledMinor, linked.effectiveMinor))
    }

    /** History rows (COMPLETED, CANCELLED, REFUNDED, PAID_OFF), most recent end date first, unknown end dates last. */
    fun history(plans: List<PlanSnapshot>): List<InstallmentHistoryItem> =
        plans
            .mapNotNull { plan ->
                InstallmentPlanMath.historySummary(plan.moneyInput(), plan.terminalEpochDay)?.let { summary ->
                    InstallmentHistoryItem(plan.planId, plan.planUuid, plan.displayName, summary)
                }
            }
            .sortedWith(
                compareByDescending<InstallmentHistoryItem> { it.summary.endEpochDay ?: Long.MIN_VALUE }
                    .thenByDescending { it.planId }
            )

    /**
     * INV-13 / section 8.2 suggestions: for every SCHEDULED, unlinked REGULAR entry of an ACTIVE
     * plan, each eligible [candidates] expense that matches [InstallmentCandidateRule] for the
     * plan's fulfilment mode (AUTO_RECORD [due - 3 d, due], CONFIRM [due - 5 d, due + 10 d]). Entries in any other
     * status (PAID_BEFORE_TRACKING in particular) and plans that are not ACTIVE get none. Ordered
     * by entry due date, then closest amount, then newest expense date, then expense id.
     *
     * [candidates] must already be filtered for eligibility by the caller (exclusivity, spending
     * type); the caller passes the candidates that may be linked to this plan's entries.
     */
    fun suggestionsFor(plan: PlanSnapshot, candidates: List<CandidateExpense>): List<InstallmentLinkSuggestion> {
        if (plan.status != InstallmentPlanStatus.ACTIVE) return emptyList()
        val result = ArrayList<InstallmentLinkSuggestion>()
        for (entry in plan.entries) {
            val money = entry.money
            if (money.status != InstallmentEntryStatus.SCHEDULED) continue
            if (money.kind != InstallmentEntryKind.REGULAR) continue
            if (entry.linkedExpenseId != null) continue
            for (candidate in candidates) {
                if (!InstallmentCandidateRule.matches(
                        plan.fulfilmentMode, plan.currency, money.dueEpochDay, money.scheduledMinor, candidate
                    )
                ) continue
                result += InstallmentLinkSuggestion(
                    planId = plan.planId,
                    entryId = entry.entryId,
                    seq = entry.seq,
                    expenseId = candidate.expenseId,
                    expenseGrossMinor = candidate.grossMinor,
                    expenseDateEpochDay = candidate.dateEpochDay,
                    deltaMinor = candidate.grossMinor - money.scheduledMinor
                )
            }
        }
        return result.sortedWith(
            compareBy<InstallmentLinkSuggestion> { dueOf(plan, it.entryId) }
                .thenBy { kotlin.math.abs(it.deltaMinor) }
                .thenByDescending { it.expenseDateEpochDay }
                .thenBy { it.expenseId }
        )
    }

    /**
     * Whether an entry can have a candidate at all today: the window of the plan's [mode] has
     * started. An entry whose window starts after [todayEpochDay] cannot have a captured charge yet.
     */
    fun mayHaveCandidate(mode: InstallmentFulfilmentMode, entry: EntrySnapshot, todayEpochDay: Long): Boolean =
        entryMayHaveCandidate(entry.money.status, entry.money.kind, entry.linkedExpenseId) &&
            InstallmentCandidateRule.windowStartEpochDay(mode, entry.money.dueEpochDay) <= todayEpochDay

    /**
     * The entry-side part of [mayHaveCandidate], without the clock: only a SCHEDULED, unlinked
     * REGULAR entry can have a candidate charge. The single definition, shared with the candidate
     * charge probe of the coordinator (S7a).
     */
    fun entryMayHaveCandidate(status: InstallmentEntryStatus, kind: InstallmentEntryKind, linkedExpenseId: Long?): Boolean =
        status == InstallmentEntryStatus.SCHEDULED &&
            kind == InstallmentEntryKind.REGULAR &&
            linkedExpenseId == null

    private fun dueOf(plan: PlanSnapshot, entryId: Long): Long =
        plan.entries.firstOrNull { it.entryId == entryId }?.money?.dueEpochDay ?: Long.MAX_VALUE
}
