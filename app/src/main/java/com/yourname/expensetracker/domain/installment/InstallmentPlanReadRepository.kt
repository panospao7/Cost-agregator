package com.yourname.expensetracker.domain.installment

import com.yourname.expensetracker.domain.installment.readmodel.InstallmentBudgetInputs
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentLinkSuggestion
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only access to installment plans for the overview, detail, history and suggestions
 * (domain spec sections 9 and 11.1; INV-13, INV-16, INV-17, INV-26). It never writes: every plan
 * write goes through the plan lifecycle coordinator (INV-24) and every expense write through the
 * transaction lifecycle coordinator.
 *
 * "Today" and the zone come from the injected time provider and the device zone, never from a
 * wall-clock call. The observe functions re-emit when a plan or entry row changes; an edit to a
 * linked expense alone does not touch those tables, so collectors that need to see such an edit
 * (variance, paid-so-far) call the matching load function again on resume.
 */
interface InstallmentPlanReadRepository {

    /** Overview of the ACTIVE plans, per-currency totals (AE-5, AE-10b). */
    fun observeOverview(): Flow<InstallmentOverview>

    /** Detail of one plan, or null when the plan does not exist (or cannot be read). */
    fun observeDetail(planId: Long): Flow<InstallmentPlanDetail?>

    /** History of COMPLETED, CANCELLED, REFUNDED and PAID_OFF plans (AE-11). */
    fun observeHistory(): Flow<List<InstallmentHistoryItem>>

    /**
     * PE-C: a cheap invalidation trigger that re-emits whenever a plan or entry row changes, without
     * loading or computing any overview. Collectors that only need to know "something changed" (the
     * Home weather source, the Budget installment line) use it instead of [observeOverview], which
     * loads every plan on each emission. The emitted value carries no data. The default derives it
     * from [observeOverview] (same change set, more work); the implementation overrides it with the
     * bare table-change flows.
     */
    fun observeChanges(): Flow<Unit> = observeOverview().map { }

    suspend fun loadOverview(): InstallmentOverview

    suspend fun loadDetail(planId: Long): InstallmentPlanDetail?

    suspend fun loadHistory(): List<InstallmentHistoryItem>

    /** INV-13 suggested links across all ACTIVE plans (AE-17). */
    suspend fun loadSuggestions(): List<InstallmentLinkSuggestion>

    /**
     * S12b (B-2, INV-1): the readable plans (any status, with their PAID entries and linked
     * expense money including the expense date) plus the count of unreadable plans, for the
     * Budget info line. The budget screen feeds it to `InstallmentPlanReadModel.budgetInclusion`.
     * Read-only; no new store query.
     */
    suspend fun loadBudgetInputs(): InstallmentBudgetInputs
}
