package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFactsReader
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.EntryMoney
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentBudgetInputs
import com.yourname.expensetracker.domain.installment.readmodel.EntrySnapshot
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentLinkSuggestion
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanReadModel
import com.yourname.expensetracker.domain.installment.readmodel.LinkedExpenseMoney
import com.yourname.expensetracker.domain.installment.readmodel.PlanSnapshot
import com.yourname.expensetracker.domain.installment.readmodel.PurchaseMoney
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest

/**
 * Read-only implementation of [InstallmentPlanReadRepository]. It reads only the read methods of
 * [InstallmentPlanDao] and, for expense facts and money, the read-only [InstallmentExpenseFactsReader].
 * It performs no writes and logs nothing (no amounts, names or ids).
 *
 * Consistency: one load (overview, history, suggestions) is a sequence of separate reads, not one
 * read transaction. If a write commits between two of those reads, a single emission can be
 * momentarily inconsistent (for example a plan and its entries from different moments); the write
 * also triggers the change flows, so the next emission is computed from the new state and corrects
 * it. Nothing here persists what it reads, so a transient mix is never written back.
 *
 * A plan whose stored codes or amounts cannot be interpreted (unknown status, unsupported
 * currency, an amount outside the minor-unit range) is not shown with guessed values; it is
 * counted in `unreadablePlanCount` of the overview and omitted from detail and history. A
 * CancellationException is never caught here.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentPlanReadRepositoryImpl @Inject constructor(
    private val planDao: InstallmentPlanDao,
    private val factsReader: InstallmentExpenseFactsReader,
    private val timeProvider: TimeProvider,
    private val candidateScan: InstallmentCandidateChargeProbeImpl
) : InstallmentPlanReadRepository {

    override fun observeOverview(): Flow<InstallmentOverview> = changes().mapLatest { loadOverview() }

    override fun observeDetail(planId: Long): Flow<InstallmentPlanDetail?> = changes().mapLatest { loadDetail(planId) }

    override fun observeHistory(): Flow<List<InstallmentHistoryItem>> = changes().mapLatest { loadHistory() }

    /** PE-C: the table-change flows only; no load, no new query. */
    override fun observeChanges(): Flow<Unit> = changes()

    override suspend fun loadOverview(): InstallmentOverview {
        val zone = ZoneId.systemDefault()
        val today = todayEpochDay(zone)
        val loaded = loadAll(zone)
        return InstallmentPlanReadModel.overview(loaded.snapshots, today, loaded.unreadable)
    }

    override suspend fun loadDetail(planId: Long): InstallmentPlanDetail? {
        val zone = ZoneId.systemDefault()
        val today = todayEpochDay(zone)
        val plan = planDao.getPlanById(planId) ?: return null
        val entries = planDao.getEntriesForPlan(planId)
        val snapshot = snapshotOf(plan, entries, zone) ?: return null
        return InstallmentPlanReadModel.detail(snapshot, today, suggestionsOf(snapshot, zone, today))
    }

    override suspend fun loadHistory(): List<InstallmentHistoryItem> =
        InstallmentPlanReadModel.history(loadAll(ZoneId.systemDefault()).snapshots)

    override suspend fun loadSuggestions(): List<InstallmentLinkSuggestion> {
        val zone = ZoneId.systemDefault()
        val today = todayEpochDay(zone)
        return loadAll(zone).snapshots
            .filter { it.status == InstallmentPlanStatus.ACTIVE }
            .flatMap { suggestionsOf(it, zone, today) }
    }

    override suspend fun loadBudgetInputs(): InstallmentBudgetInputs {
        val loaded = loadAll(ZoneId.systemDefault())
        return InstallmentBudgetInputs(loaded.snapshots, loaded.unreadable)
    }

    // ------------------------------------------------------------------ loading

    private fun changes(): Flow<Unit> {
        val plansFlow = planDao.observeAllPlans()
        val entriesFlow = planDao.observeAllEntries()
        return combine(plansFlow, entriesFlow) { _, _ -> Unit }.conflate()
    }

    private class Loaded(val snapshots: List<PlanSnapshot>, val unreadable: Int)

    private suspend fun loadAll(zone: ZoneId): Loaded {
        val plans = planDao.getAllPlans()
        val snapshots = ArrayList<PlanSnapshot>(plans.size)
        var unreadable = 0
        for (plan in plans) {
            val entries = planDao.getEntriesForPlan(plan.id)
            val snapshot = snapshotOf(plan, entries, zone)
            if (snapshot == null) unreadable++ else snapshots += snapshot
        }
        return Loaded(snapshots, unreadable)
    }

    /** Null when a stored code or amount cannot be interpreted; the caller counts the plan as unreadable. */
    private suspend fun snapshotOf(plan: InstallmentPlan, entries: List<InstallmentEntry>, zone: ZoneId): PlanSnapshot? {
        val currency = InstallmentCurrency.fromCodeOrNull(plan.currency) ?: return null
        val kind = InstallmentPlanKind.fromCodeOrNull(plan.kind) ?: return null
        val mode = InstallmentFulfilmentMode.fromCodeOrNull(plan.fulfilmentMode) ?: return null
        val status = InstallmentPlanStatus.fromCodeOrNull(plan.status) ?: return null
        val snapshots = ArrayList<EntrySnapshot>(entries.size)
        for (entry in entries) {
            snapshots += entrySnapshotOf(entry) ?: return null
        }
        val purchase = plan.purchaseExpenseId?.let { id ->
            factsReader.findExpenseMoney(id)?.let { m ->
                InstallmentMinorUnits.toMinorOrNull(m.amount)?.let { PurchaseMoney(m.currency, it) }
            }
        }
        return PlanSnapshot(
            planId = plan.id,
            categoryId = plan.categoryId,
            planUuid = plan.planUuid,
            displayName = plan.displayName,
            lenderLabel = plan.lenderLabel,
            kind = kind,
            fulfilmentMode = mode,
            status = status,
            currency = currency,
            purchaseExpenseId = plan.purchaseExpenseId,
            purchase = purchase,
            terminalEpochDay = plan.terminalAt?.let { epochDayOf(it, zone) },
            entries = snapshots
        )
    }

    private suspend fun entrySnapshotOf(entry: InstallmentEntry): EntrySnapshot? {
        val kind = InstallmentEntryKind.fromCodeOrNull(entry.kind) ?: return null
        val status = InstallmentEntryStatus.fromCodeOrNull(entry.status) ?: return null
        val scheduled = InstallmentMinorUnits.toMinorOrNull(entry.scheduledAmount) ?: return null
        // A PAID entry needs its linked money; a missing or unconvertible expense leaves the link null,
        // and the math reports "unavailable" rather than 0 (INV-17).
        val linked = if (status == InstallmentEntryStatus.PAID) {
            entry.linkedExpenseId?.let { id ->
                factsReader.findExpenseMoney(id)?.let { m ->
                    val gross = InstallmentMinorUnits.toMinorOrNull(m.amount)
                    val effective = InstallmentMinorUnits.toMinorOrNull(m.effectiveAmount)
                    if (gross == null || effective == null) null else LinkedExpenseMoney(m.currency, gross, effective, m.dateMillis, m.categoryId)
                }
            }
        } else {
            null
        }
        return EntrySnapshot(
            entryId = entry.id,
            seq = entry.seq,
            originalScheduledMinor = entry.originalScheduledAmount?.let { InstallmentMinorUnits.toMinorOrNull(it) },
            linkedExpenseId = entry.linkedExpenseId,
            statusReason = entry.statusReason,
            money = EntryMoney(
                kind = kind,
                status = status,
                dueEpochDay = entry.dueDate,
                scheduledMinor = scheduled,
                linkedExpense = linked
            )
        )
    }

    // ------------------------------------------------------------------ INV-13 suggestions

    /**
     * Eligible candidates for the entries of [plan] that may have one today, then the pure rule.
     * The scan (window query, pure rule, eligibility through `ExpenseScheduleLinkPolicy`) is the one
     * owned by [InstallmentCandidateChargeProbeImpl], which the plan coordinator also uses.
     */
    private suspend fun suggestionsOf(plan: PlanSnapshot, zone: ZoneId, todayEpochDay: Long): List<InstallmentLinkSuggestion> {
        if (plan.status != InstallmentPlanStatus.ACTIVE) return emptyList()
        val state = InstallmentCandidateChargeProbeImpl.ScanState()
        for (entry in plan.entries) {
            if (!InstallmentPlanReadModel.mayHaveCandidate(plan.fulfilmentMode, entry, todayEpochDay)) continue
            // The scan itself (window query, pure rule, eligibility) is owned by the candidate probe.
            candidateScan.collectCandidates(
                mode = plan.fulfilmentMode,
                currency = plan.currency,
                entryId = entry.entryId,
                dueEpochDay = entry.money.dueEpochDay,
                scheduledMinor = entry.money.scheduledMinor,
                zone = zone,
                state = state,
                stopAtFirst = false
            )
        }
        return InstallmentPlanReadModel.suggestionsFor(plan, state.candidates())
    }

    // ------------------------------------------------------------------ time

    private fun todayEpochDay(zone: ZoneId): Long = epochDayOf(timeProvider.now(), zone)

    private fun epochDayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
}
