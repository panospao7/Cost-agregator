package com.yourname.expensetracker.data.store

import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write facade over [ExpenseDao] with [DatabaseWriteBarrier] enforced on every method.
 *
 * Coordinators receive this store. Direct DAO mutation outside coordinators/repositories
 * is a static guard violation (see config/db_access_allowlist.yml).
 */
@Singleton
class ExpenseWriteStore @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val expenseDao: ExpenseDao
) {
    private fun check(method: String) =
        writeBarrier.checkWritesAllowed(
            DatabaseAccessOperation("ExpenseWriteStore.$method", pipeline = "P2", entity = "Expense")
        )

    suspend fun insert(expense: Expense): Long {
        check("insert"); return expenseDao.insert(expense)
    }

    suspend fun insertAll(expenses: List<Expense>) {
        check("insertAll"); expenseDao.insertAll(expenses)
    }

    suspend fun update(expense: Expense) {
        check("update"); expenseDao.update(expense)
    }

    suspend fun delete(expense: Expense) {
        check("delete"); expenseDao.delete(expense)
    }

    suspend fun updateCategory(expenseId: Long, categoryId: Long) {
        check("updateCategory"); expenseDao.updateCategory(expenseId, categoryId)
    }

    suspend fun updateCategoryNullable(expenseId: Long, categoryId: Long?) {
        check("updateCategoryNullable"); expenseDao.updateCategoryNullable(expenseId, categoryId)
    }

    suspend fun updateMerchantKey(expenseId: Long, merchantKey: String) {
        check("updateMerchantKey"); expenseDao.updateMerchantKey(expenseId, merchantKey)
    }

    suspend fun incrementBackfillAttempts(expenseId: Long) {
        check("incrementBackfillAttempts"); expenseDao.incrementBackfillAttempts(expenseId)
    }

    suspend fun conditionallySetLocation(
        expenseId: Long, latitude: Double, longitude: Double,
        source: String, placeId: String?, resolvedAddress: String? = null
    ): Int {
        check("conditionallySetLocation")
        return expenseDao.conditionallySetLocation(expenseId, latitude, longitude, source, placeId, resolvedAddress)
    }

    suspend fun updateMerchant(expenseId: Long, merchant: String) {
        check("updateMerchant"); expenseDao.updateMerchant(expenseId, merchant)
    }

    /** Debug-only: delete all expenses. Caller must also check [BuildConfig.DEBUG]. */
    suspend fun deleteAll() {
        check("deleteAll"); expenseDao.deleteAll()
    }
}
