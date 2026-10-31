package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only implementation of [InstallmentEntryLinkReader]. It performs no writes and returns only
 * the id of the linking entry. It lives outside the recurring package because the recurring
 * lifecycle may not reference the plan store directly.
 */
@Singleton
class InstallmentEntryLinkReaderImpl @Inject constructor(
    private val planDao: InstallmentPlanDao
) : InstallmentEntryLinkReader {

    // No status filter: a PAID or REVERSED entry still owns the expense (INV-4, INV-5).
    override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
        val entry = planDao.getEntryByLinkedExpenseId(expenseId)
        return entry?.id
    }

    // No status filter: a plan row of any status keeps the expense as its purchase record.
    override suspend fun isPlanPurchase(expenseId: Long): Boolean {
        val plan = planDao.getPlanByPurchaseExpenseId(expenseId)
        return plan != null
    }

    // Batch reads (S6c-2b): one query each, no status filter, read-only.
    override suspend fun linkedExpenseIds(): Set<Long> = planDao.getLinkedExpenseIds().toSet()

    override suspend fun planPurchaseExpenseIds(): Set<Long> = planDao.getPlanPurchaseExpenseIds().toSet()
}
