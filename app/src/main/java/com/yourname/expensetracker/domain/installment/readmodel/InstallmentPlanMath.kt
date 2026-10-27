package com.yourname.expensetracker.domain.installment.readmodel

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import java.util.EnumMap

/** Why an amount cannot be shown. A bounded code: unavailable is never rendered as zero (INV-17). */
enum class InstallmentUnavailableReason {
    /** The plan has no purchase attached (standalone, or the purchase was deleted). */
    NO_PURCHASE,

    /** The purchase currency differs from the plan currency (OD-14). */
    CURRENCY_DIFFERS,

    /** Financing cost is shown only for ACTIVE, COMPLETED and PAID_OFF plans. */
    STATUS_NOT_ELIGIBLE,

    /** A linked expense is in another currency than the plan (transient until reconcile unlinks it). */
    LINKED_CURRENCY_DRIFT,

    /** A PAID entry has no linked expense (an integrity gap the reconcile repairs). */
    MISSING_LINKED_EXPENSE
}

/** A money figure in the plan currency, in minor units, or an explicit "unavailable". */
sealed interface InstallmentAmount {
    data class Available(val minor: Long) : InstallmentAmount
    data class Unavailable(val reason: InstallmentUnavailableReason) : InstallmentAmount
}

/**
 * The linked expense of a PAID or REVERSED entry, already converted to minor units by the caller
 * (see `InstallmentMinorUnits.toMinorOrNull`).
 *
 * @property currency the expense's own ISO 4217 code.
 * @property grossMinor the expense's gross `amount` (used by financing cost; ownership and
 *   exclusion flags do not change what the lender charged).
 * @property effectiveMinor the expense's `effectiveAmount` (what counts as spending; used by
 *   paid-so-far and the lifetime counted total).
 * @property dateMillis the expense's own date in epoch millis (S12b budget line: the charge's own
 *   date governs the month it counts in, OD-10); null when not loaded, and then the charge is never
 *   placed in a budget period.
 * @property categoryId the expense's own category (S12b budget line: a budget's spent follows each
 *   expense's category, not the plan's); null when the expense is uncategorised or not loaded.
 */
data class LinkedExpenseMoney(
    val currency: String,
    val grossMinor: Long,
    val effectiveMinor: Long,
    val dateMillis: Long? = null,
    val categoryId: Long? = null
)

/** One entry as the math needs it. [scheduledMinor] is in the plan currency. */
data class EntryMoney(
    val kind: InstallmentEntryKind,
    val status: InstallmentEntryStatus,
    val dueEpochDay: Long,
    val scheduledMinor: Long,
    val linkedExpense: LinkedExpenseMoney? = null
)

/** The purchase expense of a purchase-attached plan, or absent (standalone / detached). */
data class PurchaseMoney(
    val currency: String,
    val amountMinor: Long
)

/** A plan with its entries, all money in minor units. */
data class PlanMoneyInput(
    val status: InstallmentPlanStatus,
    val currency: InstallmentCurrency,
    val purchase: PurchaseMoney?,
    val entries: List<EntryMoney>
)

/** Entry counts for detail and history lines ("n paid, k paid before tracking, m left"). */
data class InstallmentEntryCounts(
    val scheduled: Int,
    val paid: Int,
    val skipped: Int,
    val cancelled: Int,
    val reversed: Int,
    val paidBeforeTracking: Int
)

/**
 * History row of a COMPLETED, CANCELLED, REFUNDED or PAID_OFF plan (spec 11.1, INV-26). Derived
 * from plan, entry and expense rows alone; nothing comes from `transaction_events`.
 */
data class InstallmentHistorySummary(
    val currency: InstallmentCurrency,
    val finalStatus: InstallmentPlanStatus,
    /** First REGULAR entry due date (PAID_BEFORE_TRACKING included); null when there is none. */
    val startEpochDay: Long?,
    val endEpochDay: Long?,
    val totalCounted: InstallmentAmount,
    val counts: InstallmentEntryCounts,
    val financingCost: InstallmentAmount
)

/**
 * Pure read-model math for installment plans (domain spec sections 9 and 11.1; INV-6, INV-11,
 * INV-16, INV-17, INV-26). Everything is Long minor units in the plan currency; totals are kept
 * per currency and never summed across currencies. There is no rounding in this object: the
 * callers convert stored Doubles with `InstallmentMinorUnits.toMinorOrNull` (HALF_UP at two
 * decimals) before calling.
 *
 * No I/O, no clock: "today" and terminal dates are passed in as civil epoch days.
 */
object InstallmentPlanMath {

    // ------------------------------------------------------------------ remaining scheduled

    /** Sum of SCHEDULED entry amounts of one plan ("remaining scheduled payments"; never "principal"). */
    fun remainingScheduledMinor(entries: List<EntryMoney>): Long =
        entries.filter { it.status == InstallmentEntryStatus.SCHEDULED }.sumOf { it.scheduledMinor }

    /**
     * Remaining scheduled payments per currency over the ACTIVE plans (overview, AE-5). A
     * currency appears only when at least one ACTIVE plan uses it (a currency with no plans shows
     * no line, not 0). Currencies are never combined.
     */
    fun remainingScheduledByCurrency(plans: List<PlanMoneyInput>): Map<InstallmentCurrency, Long> {
        val totals = EnumMap<InstallmentCurrency, Long>(InstallmentCurrency::class.java)
        for (plan in plans) {
            if (plan.status != InstallmentPlanStatus.ACTIVE) continue
            totals[plan.currency] = (totals[plan.currency] ?: 0L) + remainingScheduledMinor(plan.entries)
        }
        return totals
    }

    // ------------------------------------------------------------------ paid / counted

    /**
     * Paid-so-far, which equals the lifetime counted total of the plan (INV-6): the sum of the
     * linked expenses' `effectiveAmount` over PAID entries (REGULAR and PAYOFF). REVERSED,
     * SKIPPED, CANCELLED, SCHEDULED and PAID_BEFORE_TRACKING entries add 0.
     *
     * Unavailable (never 0) when a PAID entry has no linked expense or when any linked expense is
     * not in the plan currency (INV-17).
     */
    fun paidSoFar(plan: PlanMoneyInput): InstallmentAmount = sumPaid(plan) { it.effectiveMinor }

    /** Same as [paidSoFar]; named for the INV-6 "lifetime counted" figure shown in history. */
    fun lifetimeCounted(plan: PlanMoneyInput): InstallmentAmount = paidSoFar(plan)

    /** Variance of a PAID entry against its schedule: linked `effectiveAmount` minus `scheduledAmount`. */
    fun varianceMinor(scheduledMinor: Long, linkedEffectiveMinor: Long): Long = linkedEffectiveMinor - scheduledMinor

    // ------------------------------------------------------------------ financing cost

    /**
     * Financing cost (domain spec section 9, AMD-2 / OQ-24), all in minor units:
     *
     * `Σ gross(linked expense) over PAID entries (REGULAR + PAYOFF)
     *  + Σ scheduledAmount over SCHEDULED entries
     *  + Σ scheduledAmount over PAID_BEFORE_TRACKING entries
     *  - purchase amount`
     *
     * SKIPPED, CANCELLED and REVERSED entries are excluded. The PAID term uses the linked
     * expense's gross `amount`, not `effectiveAmount`. The result may be negative and is returned
     * as is.
     *
     * Unavailable (never 0) unless: the plan status is ACTIVE, COMPLETED or PAID_OFF; a purchase
     * is attached; the purchase currency equals the plan currency; and paid-so-far is available
     * (INV-17). Checked in that order.
     */
    fun financingCost(plan: PlanMoneyInput): InstallmentAmount {
        if (plan.status != InstallmentPlanStatus.ACTIVE &&
            plan.status != InstallmentPlanStatus.COMPLETED &&
            plan.status != InstallmentPlanStatus.PAID_OFF
        ) {
            return InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
        }
        val purchase = plan.purchase
            ?: return InstallmentAmount.Unavailable(InstallmentUnavailableReason.NO_PURCHASE)
        if (purchase.currency != plan.currency.code) {
            return InstallmentAmount.Unavailable(InstallmentUnavailableReason.CURRENCY_DIFFERS)
        }
        val paidGross = when (val paid = sumPaid(plan) { it.grossMinor }) {
            is InstallmentAmount.Available -> paid.minor
            is InstallmentAmount.Unavailable -> return paid
        }
        val scheduled = plan.entries
            .filter { it.status == InstallmentEntryStatus.SCHEDULED }
            .sumOf { it.scheduledMinor }
        val paidBeforeTracking = plan.entries
            .filter { it.status == InstallmentEntryStatus.PAID_BEFORE_TRACKING }
            .sumOf { it.scheduledMinor }
        return InstallmentAmount.Available(paidGross + scheduled + paidBeforeTracking - purchase.amountMinor)
    }

    // ------------------------------------------------------------------ counts, display, payoff bounds

    /** Counts by stored entry status. PAYOFF entries count under their status (a PAID payoff is one paid). */
    fun entryCounts(entries: List<EntryMoney>): InstallmentEntryCounts = InstallmentEntryCounts(
        scheduled = entries.count { it.status == InstallmentEntryStatus.SCHEDULED },
        paid = entries.count { it.status == InstallmentEntryStatus.PAID },
        skipped = entries.count { it.status == InstallmentEntryStatus.SKIPPED },
        cancelled = entries.count { it.status == InstallmentEntryStatus.CANCELLED },
        reversed = entries.count { it.status == InstallmentEntryStatus.REVERSED },
        paidBeforeTracking = entries.count { it.status == InstallmentEntryStatus.PAID_BEFORE_TRACKING }
    )

    /**
     * Derived display state of a SCHEDULED entry (spec 3.2): UPCOMING when due after today, DUE
     * when due today, OVERDUE when due before today. Returns null for every other status:
     * PAID_BEFORE_TRACKING in particular has no display state.
     */
    fun dueState(status: InstallmentEntryStatus, dueEpochDay: Long, todayEpochDay: Long): InstallmentDueState? =
        if (status != InstallmentEntryStatus.SCHEDULED) {
            null
        } else {
            when {
                dueEpochDay > todayEpochDay -> InstallmentDueState.UPCOMING
                dueEpochDay == todayEpochDay -> InstallmentDueState.DUE
                else -> InstallmentDueState.OVERDUE
            }
        }

    /**
     * Default early-payoff amount (spec 7.3): the sum of SCHEDULED entries, overdue unrecorded
     * ones included; PAID_BEFORE_TRACKING entries are excluded.
     */
    fun defaultPayoffAmountMinor(entries: List<EntryMoney>): Long = remainingScheduledMinor(entries)

    /**
     * Lower bound of the payoff date (spec 7.3, derived rule d): the latest due date among PAID
     * and PAID_BEFORE_TRACKING entries, or null when there is none.
     */
    fun payoffDateLowerBoundEpochDay(entries: List<EntryMoney>): Long? =
        entries
            .filter {
                it.status == InstallmentEntryStatus.PAID || it.status == InstallmentEntryStatus.PAID_BEFORE_TRACKING
            }
            .maxOfOrNull { it.dueEpochDay }

    // ------------------------------------------------------------------ history

    /** true for the statuses listed in history (COMPLETED, CANCELLED, REFUNDED, PAID_OFF). */
    fun isHistoryStatus(status: InstallmentPlanStatus): Boolean = status != InstallmentPlanStatus.ACTIVE

    /**
     * History row for a plan in a history status, or null for an ACTIVE plan (INV-26).
     *
     * - start: the earliest REGULAR entry due date, PAID_BEFORE_TRACKING entries included.
     * - end: for COMPLETED, the latest due date among PAID and PAID_BEFORE_TRACKING entries,
     *   falling back to [terminalEpochDay] when there is none; for CANCELLED, REFUNDED and
     *   PAID_OFF, [terminalEpochDay] (the civil date the plan reached its final status).
     * - totalCounted: [lifetimeCounted] (0 for REFUNDED, because its paid entries are REVERSED;
     *   PAID_BEFORE_TRACKING adds 0).
     * - financingCost: [financingCost] (unavailable when INV-17 says so).
     */
    fun historySummary(plan: PlanMoneyInput, terminalEpochDay: Long?): InstallmentHistorySummary? {
        if (!isHistoryStatus(plan.status)) return null
        val start = plan.entries
            .filter { it.kind == InstallmentEntryKind.REGULAR }
            .minOfOrNull { it.dueEpochDay }
        val end = if (plan.status == InstallmentPlanStatus.COMPLETED) {
            payoffDateLowerBoundEpochDay(plan.entries) ?: terminalEpochDay
        } else {
            terminalEpochDay
        }
        return InstallmentHistorySummary(
            currency = plan.currency,
            finalStatus = plan.status,
            startEpochDay = start,
            endEpochDay = end,
            totalCounted = lifetimeCounted(plan),
            counts = entryCounts(plan.entries),
            financingCost = financingCost(plan)
        )
    }

    // ------------------------------------------------------------------ internals

    private fun sumPaid(plan: PlanMoneyInput, selector: (LinkedExpenseMoney) -> Long): InstallmentAmount {
        var total = 0L
        for (entry in plan.entries) {
            if (entry.status != InstallmentEntryStatus.PAID) continue
            val linked = entry.linkedExpense
                ?: return InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
            if (linked.currency != plan.currency.code) {
                return InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
            }
            total += selector(linked)
        }
        return InstallmentAmount.Available(total)
    }
}
