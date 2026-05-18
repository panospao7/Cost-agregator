package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
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
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizationRepository: MerchantNormalizationRepository,
    private val recurringLifecycleCoordinator: Lazy<com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator>,
    private val sideEffectRecorder: com.yourname.expensetracker.domain.diagnostics.SideEffectDiagnosticRecorder
) {
    suspend fun dispatchOnCreated(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
        causationId: String? = null
    ) {
        Timber.d("TransactionSideEffectDispatcher: dispatching post-creation side effects for expense $expenseId (source=$source)")
        val ctx = com.yourname.expensetracker.domain.diagnostics.SideEffectContext(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
            correlationId = correlationId,
            causationId = causationId,
            entityType = "expense",
            entityId = expenseId,
            source = source.name
        )

        // 1. Budget check (fire-and-forget, best-effort)
        sideEffectRecorder.runSideEffect(ctx, "budget_check") {
            budgetMonitor.get().checkBudgets()
        }

        // 2. Load the expense for anomaly checking and pattern learning
        val expense = expenseDao.getById(expenseId) ?: run {
            Timber.w("TransactionSideEffectDispatcher: expense $expenseId not found after insert, skipping anomaly and pattern learning")
            return
        }

        // 3. Anomaly alert check (best-effort)
        sideEffectRecorder.runSideEffect(ctx, "anomaly_alert_check") {
            val category = expense.categoryId?.let { categoryDao.getById(it) }
            anomalyAlertOrchestrator.checkAndAlert(ExpenseWithCategory(expense = expense, category = category))
        }

        // 4. Learn merchant → category pattern if a category is assigned
        if (expense.categoryId != null) {
            sideEffectRecorder.runSideEffect(ctx, "merchant_category_pattern_learning") {
                merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId)
            }
        }

        // 5. Merchant canonical stats update (best-effort)
        sideEffectRecorder.runSideEffect(ctx, "merchant_canonical_stats_update") {
            val merchantKey = expense.merchantKey ?: expense.merchant
            val canonical = merchantNormalizationRepository.getCanonicalBySearchKey(merchantKey)
            if (canonical != null) {
                merchantNormalizationRepository.incrementMerchantStats(
                    id = canonical.id, amount = expense.amount, timestamp = expense.date
                )
            }
        }

        // 6. Recurring occurrence matching (best-effort)
        sideEffectRecorder.runSideEffect(ctx, "recurring_occurrence_matching") {
            recurringLifecycleCoordinator.get().linkExpenseToOccurrence(expenseId)
        }
    }

    /**
     * Dispatches all standard post-update side effects for the given expense.
     *
     * Best-effort: budget re-check, anomaly re-evaluation, and merchant pattern
     * learning are dispatched after an expense has been updated (amount, date,
     * category, etc. may have changed).
     *
     * @param expenseId The ID of the updated expense.
     * @param source    The source/origin of the expense update.
     */
    suspend fun dispatchOnUpdated(
        expenseId: Long,
        source: String,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
    ) {
        val ctx = com.yourname.expensetracker.domain.diagnostics.SideEffectContext(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
            correlationId = correlationId,
            entityType = "expense", entityId = expenseId, source = source
        )
        sideEffectRecorder.runSideEffect(ctx, "budget_check") { budgetMonitor.get().checkBudgets() }
        val expense = expenseDao.getById(expenseId) ?: return
        sideEffectRecorder.runSideEffect(ctx, "anomaly_alert_check") {
            val category = expense.categoryId?.let { categoryDao.getById(it) }
            anomalyAlertOrchestrator.checkAndAlert(ExpenseWithCategory(expense = expense, category = category))
        }
        if (expense.categoryId != null) {
            sideEffectRecorder.runSideEffect(ctx, "merchant_category_pattern_learning") {
                merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId)
            }
        }
    }

    suspend fun dispatchOnDeleted(
        expenseId: Long,
        source: String,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
    ) {
        val ctx = com.yourname.expensetracker.domain.diagnostics.SideEffectContext(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
            correlationId = correlationId,
            entityType = "expense", entityId = expenseId, source = source
        )
        sideEffectRecorder.runSideEffect(ctx, "budget_check") { budgetMonitor.get().checkBudgets() }
        sideEffectRecorder.runSideEffect(ctx, "recurring_occurrence_unlink") {
            recurringLifecycleCoordinator.get().unlinkExpenseFromOccurrence(expenseId)
        }
    }

    suspend fun dispatchOnBulkUpdated(source: String, affectedCount: Int) {
        val ctx = com.yourname.expensetracker.domain.diagnostics.SideEffectContext(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
            correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
            entityType = "bulk", entityId = null, source = source
        )
        sideEffectRecorder.runSideEffect(ctx, "bulk_budget_check") { budgetMonitor.get().checkBudgets() }
    }
}
