package com.yourname.expensetracker.domain.installment

import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentLinkSuggestion
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import kotlinx.coroutines.flow.Flow

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

    suspend fun loadOverview(): InstallmentOverview

    suspend fun loadDetail(planId: Long): InstallmentPlanDetail?

    suspend fun loadHistory(): List<InstallmentHistoryItem>

    /** INV-13 suggested links across all ACTIVE plans (AE-17). */
    suspend fun loadSuggestions(): List<InstallmentLinkSuggestion>
}
