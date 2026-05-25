package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility wrapper that delegates to [ReceiptSideEffectPlanner] and
 * [PostCommitActionRunner].
 *
 * This class is retained for binary compatibility during the PR4 migration.
 * New call sites should use [ReceiptSideEffectPlanner] directly and invoke
 * the runner themselves.
 *
 * ## Migration path
 * 1. Inject [ReceiptSideEffectPlanner] where needed.
 * 2. Call `planner.planAfterReceiptSaved(...)` to obtain a [PostCommitActionBatch].
 * 3. Call `postCommitActionRunner.run(batch)` after the database transaction commits.
 * 4. Remove usage of this dispatcher once all call sites are migrated.
 */
@Singleton
class ReceiptSideEffectDispatcher @Inject constructor(
    private val planner: ReceiptSideEffectPlanner,
    private val runner: PostCommitActionRunner
) {

    /**
     * Dispatch all applicable post-save side effects for the given [receipt].
     *
     * This method now delegates to [ReceiptSideEffectPlanner] for planning and
     * [PostCommitActionRunner] for execution.  Behaviour is identical to the
     * previous imperative implementation.
     *
     * @param receipt The newly-saved receipt whose side effects should run.
     */
    suspend fun dispatchAfterSave(
        receipt: ScannedReceipt,
        correlationId: String = CorrelationIds.newId(),
        causationId: String? = null
    ) {
        try {
            val batch = planner.planAfterReceiptSaved(
                input = ReceiptSideEffectInput(receipt = receipt, ephemeralRawOcrText = null,
                    rawStorageMode = RawStorageMode.STORE_RAW, correlationId = correlationId),
                causationId = causationId
            )
            runner.run(batch)
        } catch (e: Exception) {
            Timber.e(e, "dispatchAfterSave failed for receipt %d", receipt.id)
        }
    }
}
