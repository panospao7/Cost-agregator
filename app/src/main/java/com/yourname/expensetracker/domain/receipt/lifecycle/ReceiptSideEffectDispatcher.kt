package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.PostCommitSideEffectEvidenceService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility wrapper that delegates to [ReceiptSideEffectPlanner] and
 * [PostCommitActionRunner].
 *
 * PR 8: Now records durable evidence of side-effect outcomes via
 * [PostCommitSideEffectEvidenceService] so failed post-commit actions
 * are queryable through the diagnostics infrastructure.
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
    private val runner: PostCommitActionRunner,
    private val evidenceService: PostCommitSideEffectEvidenceService
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
            // PR 8: Record durable evidence of side-effect outcomes
            evidenceService.runBestEffortWithEvidence(
                batch = batch,
                logMessage = "Receipt side-effect batch completed with evidence",
                targetId = receipt.id
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "dispatchAfterSave failed for receipt %d", receipt.id)
        }
    }
}
