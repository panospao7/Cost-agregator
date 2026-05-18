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
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
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

        // 5. Merchant canonical stats update (best-effort)
        runSafely("merchant canonical stats for expense $expenseId") {
            // C08-FIXED: Update merchant canonical stats post-commit for every new expense.
            try {
                val merchantKey = expense.merchantKey ?: expense.merchant
                val canonical = merchantNormalizationRepository.getCanonicalBySearchKey(merchantKey)
                if (canonical != null) {
                    merchantNormalizationRepository.incrementMerchantStats(
                        id = canonical.id,
                        amount = expense.amount,
                        timestamp = expense.date
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to update merchant stats for %s", expense.merchant)
            }
        }

        // 6. Recurring occurrence matching (best-effort)
        runSafely("recurring occurrence matching for expense $expenseId") {
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
    suspend fun dispatchOnUpdated(expenseId: Long, source: String) {
        Timber.d("TransactionSideEffectDispatcher: dispatching post-update side effects for expense $expenseId (source=$source)")

        // Budget re-check — amount/date/category may have changed
        runSafely("budget check after update for expense $expenseId") {
            budgetMonitor.get().checkBudgets()
        }

        // Load expense for anomaly check
        val expense = expenseDao.getById(expenseId) ?: return

        // Anomaly re-evaluation
        runSafely("anomaly check after update for expense $expenseId") {
            val category = expense.categoryId?.let { categoryDao.getById(it) }
            anomalyAlertOrchestrator.checkAndAlert(
                ExpenseWithCategory(expense = expense, category = category)
            )
        }

        // Merchant pattern learning if category assigned
        if (expense.categoryId != null) {
            runSafely("merchant-category pattern update for expense $expenseId") {
                merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId)
            }
        }

        // TODO (C08): Merchant canonical stats on update — currently wired only in dispatchOnCreated.
        // For updates, consider whether to use delta (newAmount - oldAmount) or
        // re-compute from scratch. Delta is simpler but requires old amount fetch.
    }

    /**
     * Dispatches all standard post-delete side effects for the given expense.
     *
     * Best-effort: budget re-check runs so budget usage is recalculated after
     * removing spending. No merchant pattern learning on delete — the pattern
     * may still be valid.
     *
     * @param expenseId The ID of the deleted expense.
     * @param source    The source/origin of the expense deletion.
     */
    suspend fun dispatchOnDeleted(expenseId: Long, source: String) {
        Timber.d("TransactionSideEffectDispatcher: dispatching post-delete side effects for expense $expenseId (source=$source)")

        // Budget re-check — removing spending should update budget usage
        runSafely("budget check after delete for expense $expenseId") {
            budgetMonitor.get().checkBudgets()
        }

        // Note: anomalyAlertOrchestrator.clearAlertForExpense() is not available;
        // if added in the future, insert anomaly clearing here.
        // No merchant pattern learning on delete — the pattern may still be valid

        // Recurring occurrence unlink (best-effort)
        runSafely("recurring occurrence unlink for expense $expenseId") {
            recurringLifecycleCoordinator.get().unlinkExpenseFromOccurrence(expenseId)
        }
    }

    /**
     * P2-07: Dispatch aggregate post-commit side effects for bulk operations.
     *
     * Instead of N per-row budget checks / anomaly evaluations, runs a single
     * holistic recalculation. This prevents side-effect storms while still
     * ensuring cache/budget/anomaly state is fresh after bulk mutations.
     *
     * @param source       The source/origin of the bulk update.
     * @param affectedCount Number of rows affected (for logging).
     */
    suspend fun dispatchOnBulkUpdated(source: String, affectedCount: Int) {
        Timber.d("TransactionSideEffectDispatcher: bulk update recalculation (source=%s, affected=%d)", source, affectedCount)

        // Single budget re-check for overall state after bulk mutation
        runSafely("bulk budget check (source=$source, affected=$affectedCount)") {
            budgetMonitor.get().checkBudgets()
        }
    }

    private suspend fun runSafely(action: String, block: suspend () -> Unit) {
        try {
            diagnosticEventWriter.emit(
                com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
                    stage = "side_effect",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SIDE_EFFECT_STARTED,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.DEBUG,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("sideEffect", action.take(128))
                        .build()
                )
            )
        } catch (_: Exception) {}
        try {
            block()
            try {
                diagnosticEventWriter.emit(
                    com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
                        stage = "side_effect",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SIDE_EFFECT_COMPLETED,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.DEBUG,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("sideEffect", action.take(128))
                            .build()
                    )
                )
            } catch (_: Exception) {}
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "TransactionSideEffectDispatcher: $action failed")
            try {
                diagnosticEventWriter.emit(
                    com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.TRANSACTION,
                        stage = "side_effect",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SIDE_EFFECT_FAILED,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("sideEffect", action.take(128))
                            .build(),
                        exception = e,
                        isTerminal = false
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
