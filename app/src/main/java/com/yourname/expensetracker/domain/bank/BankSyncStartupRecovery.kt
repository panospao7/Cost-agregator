package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.domain.diagnostics.StaleBankOperationRunRecovery
import com.yourname.expensetracker.domain.workers.LeaseAcquisitionBlockedException
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RP-17 17-E: startup recovery for stale BANK sync bookkeeping, independent of
 * any live sync. Two recovery families, each handled separately:
 *
 *  1. Operation runs — `operation_runs` rows of bank operations left RUNNING by
 *     process death are finalized as CANCELLED with the controlled
 *     STALE_RUNNING_ABORTED reason code so the ledger is accurate.
 *  2. Statement import runs — `bank_statement_import_runs` rows left RUNNING by
 *     process death are marked STALE_FAILED with the same controlled code.
 *
 * Runs only when the write barrier allows writes (restore/backup must never be
 * interrupted by recovery writes). Cancellation propagates; this class never
 * swallows [kotlinx.coroutines.CancellationException].
 *
 * This is bookkeeping only: it never touches expenses, connections, or reviews.
 */
@Singleton
class BankSyncStartupRecovery @Inject constructor(
    private val operationRunRecovery: StaleBankOperationRunRecovery,
    private val bankStatementImportRunDao: BankStatementImportRunDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val leaseRegistry: WorkerLeaseRegistry
) {

    data class RecoveryResult(
        val operationRunsRecovered: Int,
        val statementRunsRecovered: Int
    )

    /**
     * Finalize stale bank operation runs and stale statement-import runs.
     *
     * @param staleThresholdMs cutoff — only runs started strictly before this
     *   timestamp are considered stale (caller computes from [TimeProvider]).
     */
    suspend fun recoverStaleRuns(staleThresholdMs: Long): RecoveryResult {
        // Recovery writes are regular DB writes: they must respect the restore
        // barrier. When writes are blocked there is nothing safe to do — report
        // zero and let the next healthy startup retry.
        try {
            writeBarrier.checkWritesAllowed("BankSyncStartupRecovery.recoverStaleRuns")
        } catch (e: DatabaseAccessBlockedException) {
            return RecoveryResult(operationRunsRecovered = 0, statementRunsRecovered = 0)
        }

        // Startup is not a CoroutineWorker, but its suspended Room work must
        // participate in the same maintenance admission/drain protocol.
        val lease = try {
            leaseRegistry.acquire("bank_startup_recovery")
        } catch (_: LeaseAcquisitionBlockedException) {
            return RecoveryResult(operationRunsRecovered = 0, statementRunsRecovered = 0)
        }
        try {
            lease.checkpoint("BankSyncStartupRecovery.recoverStaleRuns")
            val operationRunsRecovered = operationRunRecovery.recoverStaleBankRuns(staleThresholdMs, lease)

            lease.checkpoint("BankSyncStartupRecovery.readStaleStatements")
            val staleStatementRuns = bankStatementImportRunDao.getStaleRunningRuns(staleThresholdMs)
            var statementRunsRecovered = 0
            for (run in staleStatementRuns) {
                lease.checkpoint("BankSyncStartupRecovery.markStaleStatement")
                statementRunsRecovered += bankStatementImportRunDao.markStaleFailed(
                    runId = run.id,
                    cutoffMs = staleThresholdMs,
                    now = timeProvider.now(),
                    reason = DiagnosticReasonCode.STALE_RUNNING_ABORTED.name
                )
            }
            return RecoveryResult(operationRunsRecovered, statementRunsRecovered)
        } finally {
            lease.close()
        }
    }
}
