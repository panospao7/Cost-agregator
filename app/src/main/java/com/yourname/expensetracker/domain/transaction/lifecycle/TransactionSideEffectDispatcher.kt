package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility facade that delegates side-effect planning and execution
 * to [TransactionSideEffectPlanner] and [PostCommitActionRunner].
 *
 * This class is retained for backward compatibility. New callers should
 * use the planner + runner directly via [TransactionLifecycleCoordinator].
 *
 * Source-specific side effects (e.g. scanned receipt linking, raw notification
 * relevance, recommendation generation, recurring rule creation) remain in the
 * calling repository and are NOT handled here.
 */
@Singleton
class TransactionSideEffectDispatcher @Inject constructor(
    private val planner: TransactionSideEffectPlanner,
    private val runner: PostCommitActionRunner
) {
    suspend fun dispatchOnCreated(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
        causationId: String? = null
    ) {
        Timber.d("TransactionSideEffectDispatcher: dispatching post-creation side effects for expense $expenseId (source=$source)")
        val batch = planner.planCreated(expenseId, source, correlationId)
        runner.run(batch)
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
        val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.FULL)
        runner.run(batch)
    }

    suspend fun dispatchOnDeleted(
        expenseId: Long,
        source: String,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
    ) {
        val batch = planner.planDeleted(expenseId, source, correlationId)
        runner.run(batch)
    }

    suspend fun dispatchOnBulkUpdated(source: String, affectedCount: Int) {
        val batch = planner.planBulkUpdated(source, affectedCount, null)
        runner.run(batch)
    }
}
