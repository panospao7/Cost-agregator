package com.yourname.expensetracker.domain.installment.lifecycle

import dagger.Lazy
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Why the installment plans were not reconciled before a backup. Bounded; never carries text. */
enum class PreBackupNotReconciledReason {
    /** The reconcile returned anything other than `Done` (refused, failed, rate refused). */
    NOT_DONE,

    /** The report counted a failed plan or a failed orphan flag: the store may still hold an orphan. */
    RECONCILE_INCOMPLETE,

    /** The reconcile (or a closed write barrier) threw something other than a cancellation. */
    EXCEPTION
}

/** Outcome of [InstallmentPreBackupReconciler.reconcileBeforeBackup]. */
sealed interface PreBackupReconcileResult {

    /**
     * No reconcile failure was reported. A non-Move R2 is skipped and not counted, so
     * BackupVerifier remains the authority on the stored state.
     */
    data object Reconciled : PreBackupReconcileResult

    /** The reconcile did not complete cleanly; the caller must fail the backup closed. */
    data class NotReconciled(val reason: PreBackupNotReconciledReason) : PreBackupReconcileResult
}

/**
 * Loan-installments S6c-4 (INV-30, owner item 17, handoff F8): repairs installment orphans that a
 * previous expense delete or update left behind (its plan reaction did not run) so that
 * `BackupVerifier` does not reject an otherwise healthy backup. The verifier stays unchanged and
 * fail-closed.
 *
 * Must be called in NORMAL mode, outside any counted write and BEFORE the backup enters its
 * maintenance mode: the reconcile writes through the plan coordinator, and the write barrier
 * refuses writes once BACKUP_EXPORTING is active. A write racing between this call and the
 * maintenance drain can reintroduce a transient orphan; the verify then fails that backup and a
 * retry succeeds (owner item 17, option (a)).
 *
 * Runs `reconcile()` and dispatches every `Done.postCommit` batch through
 * [InstallmentPostCommitDispatcher] (the single owner of the dispatch loop; it wraps the executor the
 * transaction lifecycle coordinator uses), even when the report counts failures: those batches
 * belong to transactions that already committed. A failing batch does not stop later ones and does
 * not make the reconcile incomplete: it carries side effects only, not stored state.
 * `CancellationException` is always rethrown. Diagnostics carry class simple names only.
 *
 * Both dependencies are `Lazy`: the plan coordinator depends on the transaction lifecycle
 * coordinator, and the backup repository must not pull that graph in at construction. The
 * dispatcher is resolved only when there is a batch to dispatch.
 */
@Singleton
class InstallmentPreBackupReconciler @Inject constructor(
    private val coordinator: Lazy<InstallmentPlanLifecycleCoordinator>,
    private val postCommitDispatcher: Lazy<InstallmentPostCommitDispatcher>
) {

    suspend fun reconcileBeforeBackup(): PreBackupReconcileResult {
        return try {
            val result = coordinator.get().reconcile()
            if (result !is InstallmentOpResult.Done) {
                Timber.w("Installment pre-backup reconcile not done: %s", result.javaClass.simpleName)
                return PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.NOT_DONE)
            }
            // Dispatch first: the batches belong to plan transactions that already committed.
            val batches = result.postCommit
            if (batches.isNotEmpty()) postCommitDispatcher.get().dispatch(batches)
            val report = result.value
            if (report.plansFailed > 0 || report.orphanFlagsFailed > 0) {
                Timber.w("Installment pre-backup reconcile incomplete")
                PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.RECONCILE_INCOMPLETE)
            } else {
                PreBackupReconcileResult.Reconciled
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w("Installment pre-backup reconcile failed: %s", e.javaClass.simpleName)
            PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.EXCEPTION)
        }
    }
}
