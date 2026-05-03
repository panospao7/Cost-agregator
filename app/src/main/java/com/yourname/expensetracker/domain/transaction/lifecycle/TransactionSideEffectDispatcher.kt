package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import dagger.Lazy
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches standard post-creation side effects for newly created expenses.
 *
 * This consolidates the common side effects that previously were duplicated
 * across every repository that creates expenses:
 *   - Budget monitoring
 *   - Anomaly alert checking
 *   - Merchant → category pattern learning
 *
 * Source-specific side effects (e.g. scanned receipt linking, raw notification
 * relevance, recommendation generation, recurring rule creation) remain in the
 * calling repository and are NOT handled here.
 */
@Singleton
class TransactionSideEffectDispatcher @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val budgetMonitor: Lazy<BudgetMonitor>,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository
) {
    /**
     * Dispatches all standard post-creation side effects for the given expense.
     *
     * This is designed to be called AFTER the expense has been persisted
     * (post-commit).  All operations are best-effort; failures are logged
     * and swallowed so they never propagate to the caller.
     *
     * @param expenseId The ID of the newly created expense.
     * @param source    The source/origin of the expense creation.
     */
    suspend fun dispatchOnCreated(expenseId: Long, source: ExpenseSource) {
        Timber.d("TransactionSideEffectDispatcher: dispatching post-creation side effects for expense $expenseId (source=$source)")

        // 1. Budget check (fire-and-forget, best-effort)
        runSafely("budget check for expense $expenseId") {
            budgetMonitor.get().checkBudgets()
        }

        // 2. Load the expense for anomaly checking and pattern learning
        val expense = expenseDao.getById(expenseId) ?: run {
            Timber.w("TransactionSideEffectDispatcher: expense $expenseId not found after insert, skipping anomaly and pattern learning")
            return
        }

        // 3. Anomaly alert check (best-effort)
        runSafely("anomaly alert check for expense $expenseId") {
            val category = expense.categoryId?.let { categoryDao.getById(it) }
            val expenseWithCategory = ExpenseWithCategory(
                expense = expense,
                category = category
            )
            anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)
        }

        // 4. Learn merchant → category pattern if a category is assigned
        if (expense.categoryId != null) {
            runSafely("merchant-category pattern learning for expense $expenseId (merchant=${expense.merchant})") {
                merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId)
            }
        }
    }

    private suspend fun runSafely(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "TransactionSideEffectDispatcher: $action failed")
        }
    }
}
