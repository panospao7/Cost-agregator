package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loan-installments: the ONE owner of the post-commit dispatch of installment coordinator results
 * (S10b-1 follow-up F3). The S7a-2 due-recording worker, the S6c-4 pre-backup reconciler and
 * `InstallmentEntryActionsViewModel` each call [dispatch] with the `postCommit` batches of a `Done`
 * result, after the coordinator has returned (the batches belong to transactions that already
 * committed). No other caller may keep its own copy of this loop.
 *
 * Semantics:
 * - a batch without actions is skipped;
 * - batches run in order, each through [PostCommitActionRunner];
 * - a failing batch (any non-cancellation exception) is isolated: it is logged by class simple name
 *   only (no message, path, amount or id) and does not stop later batches or change the committed
 *   result, because a batch carries side effects only, never stored state;
 * - `CancellationException` is always rethrown and stops the remaining batches.
 */
@Singleton
class InstallmentPostCommitDispatcher @Inject constructor(
    private val postCommitActionRunner: PostCommitActionRunner
) {

    suspend fun dispatch(batches: List<PostCommitActionBatch>) {
        for (batch in batches) {
            if (batch.actions.isEmpty()) continue
            try {
                postCommitActionRunner.run(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Installment post-commit dispatch failed: class=%s", e::class.java.simpleName)
            }
        }
    }
}
