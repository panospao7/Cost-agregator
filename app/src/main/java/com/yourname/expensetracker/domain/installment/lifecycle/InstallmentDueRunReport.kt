package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.installment.model.InstallmentDeferralReason
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode

/**
 * Outcome counts of one `recordDueEntries` run (domain spec 5.2, INV-12, INV-13, INV-31, INV-34).
 *
 * Every count is an integer keyed by a bounded enum. The report never carries a plan name, lender
 * label, amount, date or identifier, so it may be logged and returned to a worker as is.
 *
 * Post-commit batches of the recorded entries are not part of the report; they travel in
 * [InstallmentOpResult.Done.postCommit], like the reconcile batches.
 *
 * @property reconcile the report of the `reconcile()` that ran first (D-18).
 * @property recorded entries claimed AUTO_RECORDED in this run.
 * @property deferred entries left SCHEDULED, per [InstallmentDeferralReason] (INV-13).
 * @property refused entries skipped with a bounded refusal code; a blank plan name counts each
 *   applicable entry under [InstallmentRefusalCode.PLAN_NAME_REQUIRED] (INV-34).
 * @property failed entries whose own transaction failed or lost a compare-and-set and rolled back,
 *   per [InstallmentOpFailure]; the run went on with the next entry.
 * @property stoppedByBarrier true when a closed write barrier (restore, reset, maintenance, backup
 *   export) ended the run early (owner approval 39(a)); the counts above cover what ran before the
 *   stop, the remaining entries are left for the next run, and the caller should retry later.
 */
data class InstallmentDueRunReport(
    val reconcile: InstallmentReconcileReport = InstallmentReconcileReport(),
    val recorded: Int = 0,
    val deferred: Map<InstallmentDeferralReason, Int> = emptyMap(),
    val refused: Map<InstallmentRefusalCode, Int> = emptyMap(),
    val failed: Map<InstallmentOpFailure, Int> = emptyMap(),
    val stoppedByBarrier: Boolean = false
) {
    fun withRecorded(): InstallmentDueRunReport = copy(recorded = recorded + 1)

    fun withDeferred(reason: InstallmentDeferralReason): InstallmentDueRunReport =
        copy(deferred = deferred.bumped(reason))

    fun withRefused(code: InstallmentRefusalCode): InstallmentDueRunReport =
        copy(refused = refused.bumped(code))

    fun withFailed(failure: InstallmentOpFailure): InstallmentDueRunReport =
        copy(failed = failed.bumped(failure))

    private fun <K> Map<K, Int>.bumped(key: K): Map<K, Int> = this + (key to ((this[key] ?: 0) + 1))
}
