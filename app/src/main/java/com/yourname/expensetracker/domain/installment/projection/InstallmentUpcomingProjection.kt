package com.yourname.expensetracker.domain.installment.projection

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency

/**
 * One upcoming installment as the Home weather card lists it (S8b-3). The amount is the scheduled
 * amount in the PLAN currency, in minor units; it is never converted and never summed across
 * currencies (INV-16). [displayName] is local-only text. This type is deliberately not an
 * `UpcomingItem` variant: plan projections never enter the shared upcoming list (A15).
 */
data class InstallmentUpcomingRow(
    val planId: Long,
    val entryId: Long,
    val displayName: String,
    val seq: Int,
    val currency: InstallmentCurrency,
    val dueEpochDay: Long,
    val amountMinor: Long
)

/** The rows of one plan currency in due order. [totalMinor] is a same-currency Long sum. */
data class InstallmentUpcomingCurrencyGroup(
    val currency: InstallmentCurrency,
    val rows: List<InstallmentUpcomingRow>,
    val totalMinor: Long
)

/**
 * The 31-day installment list for Home (S8b-3), a sibling of `RecurringPlanProjectionService` that
 * leaves that service and its RECURRING_RULE contract untouched. It reads the [InstallmentProjectionPort]
 * (INV-15 bounds are applied by the port: SCHEDULED entries of ACTIVE plans due today or later) and
 * keeps the civil days `today .. today + 30`, the same 31-day window as the baseline upcoming list.
 *
 * The list is bounded by [MAX_ROWS]. Rows keep the port order (due day, plan id, sequence). Amounts
 * stay in their plan currency; [groupByCurrency] gives the per-currency view.
 */
object InstallmentUpcomingProjection {

    /** Window length in civil days, today included. */
    const val HORIZON_DAYS: Int = 31

    /** Hard bound on the list size (a plan has at most 480 entries; 31 days hold far fewer). */
    const val MAX_ROWS: Int = 100

    /** Last civil day of the window for [todayEpochDay]. */
    fun horizonEndEpochDay(todayEpochDay: Long): Long = todayEpochDay + (HORIZON_DAYS - 1)

    suspend fun load(port: InstallmentProjectionPort, todayEpochDay: Long): List<InstallmentUpcomingRow> {
        val lastDay = horizonEndEpochDay(todayEpochDay)
        return port.upcomingEntries(lastDay)
            .asSequence()
            .filter { it.dueEpochDay in todayEpochDay..lastDay }
            // A non-positive amount is not a payable installment; it is omitted, never shown as zero.
            .filter { it.amountMinor > 0L }
            .map { entry ->
                InstallmentUpcomingRow(
                    planId = entry.planId,
                    entryId = entry.entryId,
                    displayName = entry.displayName,
                    seq = entry.seq,
                    currency = entry.currency,
                    dueEpochDay = entry.dueEpochDay,
                    amountMinor = entry.amountMinor
                )
            }
            .sortedWith(compareBy({ it.dueEpochDay }, { it.planId }, { it.seq }))
            .take(MAX_ROWS)
            .toList()
    }

    /** Rows grouped by plan currency (first-seen currency order); totals are same-currency sums only. */
    fun groupByCurrency(rows: List<InstallmentUpcomingRow>): List<InstallmentUpcomingCurrencyGroup> =
        rows.groupBy { it.currency }.map { (currency, currencyRows) ->
            InstallmentUpcomingCurrencyGroup(
                currency = currency,
                rows = currencyRows,
                totalMinor = currencyRows.sumOf { it.amountMinor }
            )
        }
}
