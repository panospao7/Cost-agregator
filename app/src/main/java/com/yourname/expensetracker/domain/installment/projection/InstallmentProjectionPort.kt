package com.yourname.expensetracker.domain.installment.projection

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus

/**
 * One projected installment: a SCHEDULED entry of an ACTIVE plan due today or later (INV-15).
 * The amount is the scheduled amount in the plan currency, in minor units; it is never converted
 * and never summed across currencies. [displayName] is local-only text; the cloud-payload
 * exclusion seam (S8a) drops projected installments as a whole, never field by field.
 *
 * @property recordGeneration with [planUuid] and [seq] this forms the reminder dedupe key
 *   `planUuid:seq:recordGeneration`.
 */
data class InstallmentProjectedEntry(
    val planId: Long,
    val planUuid: String,
    val displayName: String,
    val entryId: Long,
    val seq: Int,
    val currency: InstallmentCurrency,
    val dueEpochDay: Long,
    val amountMinor: Long,
    val recordGeneration: Int
)

/** One entry as the projection rule needs it. */
data class ProjectionEntryInput(
    val entryId: Long,
    val seq: Int,
    val kind: InstallmentEntryKind,
    val status: InstallmentEntryStatus,
    val dueEpochDay: Long,
    val scheduledMinor: Long,
    val recordGeneration: Int
)

/** One plan as the projection rule needs it. */
data class ProjectionPlanInput(
    val planId: Long,
    val planUuid: String,
    val displayName: String,
    val currency: InstallmentCurrency,
    val status: InstallmentPlanStatus,
    val entries: List<ProjectionEntryInput>
)

/**
 * The INV-15 projection bounds as a pure rule: only SCHEDULED entries of ACTIVE plans with
 * `dueDate >= today`. Terminal and COMPLETED plans project nothing; overdue SCHEDULED entries
 * (due before today) are not projected; PAID_BEFORE_TRACKING, PAID, SKIPPED, CANCELLED and REVERSED
 * entries are never projected. Ordered by due date, then plan id, then sequence.
 */
object InstallmentProjectionRule {

    fun project(
        plans: List<ProjectionPlanInput>,
        todayEpochDay: Long,
        horizonEndEpochDay: Long? = null
    ): List<InstallmentProjectedEntry> =
        plans
            .asSequence()
            .filter { it.status == InstallmentPlanStatus.ACTIVE }
            .flatMap { plan ->
                plan.entries.asSequence()
                    .filter { it.status == InstallmentEntryStatus.SCHEDULED }
                    .filter { it.dueEpochDay >= todayEpochDay }
                    .filter { horizonEndEpochDay == null || it.dueEpochDay <= horizonEndEpochDay }
                    .map { entry ->
                        InstallmentProjectedEntry(
                            planId = plan.planId,
                            planUuid = plan.planUuid,
                            displayName = plan.displayName,
                            entryId = entry.entryId,
                            seq = entry.seq,
                            currency = plan.currency,
                            dueEpochDay = entry.dueEpochDay,
                            amountMinor = entry.scheduledMinor,
                            recordGeneration = entry.recordGeneration
                        )
                    }
            }
            .sortedWith(compareBy({ it.dueEpochDay }, { it.planId }, { it.seq }))
            .toList()
}

/**
 * Read-only port through which the cash-flow, forecast, stress-forecast, recurring-sibling and
 * reminder readers see upcoming installments (INV-15, S8b, S7b). It exposes numbers and local
 * labels only; it never writes and never touches the expense store.
 */
interface InstallmentProjectionPort {

    /**
     * Upcoming installments from today (the injected time provider, civil date in the device
     * zone) up to and including [horizonEndEpochDay] when given. An entry whose stored amount
     * cannot be converted to minor units is omitted, never shown as zero.
     */
    suspend fun upcomingEntries(horizonEndEpochDay: Long? = null): List<InstallmentProjectedEntry>
}
