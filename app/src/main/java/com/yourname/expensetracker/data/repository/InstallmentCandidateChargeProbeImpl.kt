package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCandidateChargeProbe
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFactsReader
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentLinkRelations
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.readmodel.CandidateExpense
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentCandidateRule
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanReadModel
import com.yourname.expensetracker.domain.schedule.ExpenseScheduleLinkPolicy
import com.yourname.expensetracker.domain.schedule.ScheduleLinkDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the INV-13 candidate scan (S7a-1 part A). Two callers use the same code:
 * the read repository's suggestions ([collectCandidates] over every entry of a plan) and the plan
 * coordinator's bounded boolean [hasCandidate].
 *
 * One scan step for one entry: the range query of the plan mode window, the pure
 * [InstallmentCandidateRule] pre-filter (window, currency, one-minor-unit amount match), then the
 * expense eligibility (spending type, currency, not a plan purchase record, not linked to an
 * entry or an occurrence) through `ExpenseScheduleLinkPolicy`. The relations come from
 * [InstallmentLinkRelations], the same builder the coordinator link path uses.
 *
 * Read-only: it reads the plan store through the read methods of [InstallmentPlanDao] and expenses
 * through [InstallmentExpenseFactsReader]. It performs no writes and logs nothing (no amounts,
 * names or ids). A CancellationException is never caught here.
 */
@Singleton
class InstallmentCandidateChargeProbeImpl @Inject constructor(
    private val factsReader: InstallmentExpenseFactsReader,
    private val planDao: InstallmentPlanDao
) : InstallmentCandidateChargeProbe {

    /**
     * Accumulated result of a scan over several entries of one plan: the eligible candidates found
     * so far (in query order) and the expense ids already rejected, so no expense is checked twice.
     */
    class ScanState {
        internal val accepted = LinkedHashMap<Long, CandidateExpense>()
        internal val rejected = HashSet<Long>()

        /** The eligible candidates found so far, in the order they were found. */
        fun candidates(): List<CandidateExpense> = accepted.values.toList()
    }

    override suspend fun hasCandidate(plan: InstallmentPlan, entry: InstallmentEntry, zone: ZoneId): Boolean {
        // Unreadable stored values fail closed: report a candidate so the caller does not auto-record.
        val mode = InstallmentFulfilmentMode.fromCodeOrNull(plan.fulfilmentMode) ?: return true
        val currency = InstallmentCurrency.fromCodeOrNull(plan.currency) ?: return true
        val kind = InstallmentEntryKind.fromCodeOrNull(entry.kind) ?: return true
        val status = InstallmentEntryStatus.fromCodeOrNull(entry.status) ?: return true
        val scheduledMinor = InstallmentMinorUnits.toMinorOrNull(entry.scheduledAmount) ?: return true
        if (!InstallmentPlanReadModel.entryMayHaveCandidate(status, kind, entry.linkedExpenseId)) return false
        val state = ScanState()
        return collectCandidates(
            mode = mode,
            currency = currency,
            entryId = entry.id,
            dueEpochDay = entry.dueDate,
            scheduledMinor = scheduledMinor,
            zone = zone,
            state = state,
            stopAtFirst = true
        )
    }

    /**
     * Scans the window of one entry and records its eligible candidates in [state]. Returns true
     * when this step found at least one eligible candidate. With [stopAtFirst] the scan ends at the
     * first one.
     */
    internal suspend fun collectCandidates(
        mode: InstallmentFulfilmentMode,
        currency: InstallmentCurrency,
        entryId: Long,
        dueEpochDay: Long,
        scheduledMinor: Long,
        zone: ZoneId,
        state: ScanState,
        stopAtFirst: Boolean
    ): Boolean {
        // Range query follows the plan mode window: AUTO_RECORD [due-3d, due], CONFIRM [due-5d, due+10d].
        val startMillis = startOfDayMillis(InstallmentCandidateRule.windowStartEpochDay(mode, dueEpochDay), zone)
        val endMillis = startOfDayMillis(InstallmentCandidateRule.windowEndEpochDay(mode, dueEpochDay) + 1L, zone)
        val inWindow = factsReader.findExpenseMoneyBetween(startMillis, endMillis)
        var found = false
        for (money in inWindow) {
            if (state.accepted.containsKey(money.expenseId) || state.rejected.contains(money.expenseId)) continue
            val gross = InstallmentMinorUnits.toMinorOrNull(money.amount)
            if (gross == null) {
                state.rejected.add(money.expenseId)
                continue
            }
            val candidate = CandidateExpense(money.expenseId, money.currency, gross, epochDayOf(money.dateMillis, zone))
            // Cheap pure pre-filter before the per-expense eligibility reads.
            if (!InstallmentCandidateRule.matches(mode, currency, dueEpochDay, scheduledMinor, candidate)) continue
            if (isEligible(currency, entryId, money.expenseId)) {
                state.accepted[money.expenseId] = candidate
                found = true
                if (stopAtFirst) return true
            } else {
                state.rejected.add(money.expenseId)
            }
        }
        return found
    }

    private suspend fun isEligible(currency: InstallmentCurrency, entryId: Long, expenseId: Long): Boolean {
        val facts = factsReader.findExpenseFacts(expenseId) ?: return false
        val linkedEntry = planDao.getEntryByLinkedExpenseId(expenseId)
        val purchaseRecord = planDao.getPlanByPurchaseExpenseId(expenseId)
        // Same relations builder as the coordinator link path: one rule for "may this expense be linked".
        val relations = InstallmentLinkRelations.forExistingExpense(
            linkedInstallmentEntryId = linkedEntry?.id,
            planPurchaseRecordExists = purchaseRecord != null,
            facts = facts
        )
        return ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            currency.code, facts.currency, facts.isSpendingType, entryId, relations
        ) == ScheduleLinkDecision.Allowed
    }

    private fun epochDayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun startOfDayMillis(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
}
