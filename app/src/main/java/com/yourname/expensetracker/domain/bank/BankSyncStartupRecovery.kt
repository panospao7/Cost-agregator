package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunDao
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
    private val operationRunDao: OperationRunDao,
    private val bankStatementImportRunDao: BankStatementImportRunDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
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

        val reasonCode = DiagnosticReasonCode.STALE_RUNNING_ABORTED.name

        // Family 1: bank operation runs.
        var operationRunsRecovered = 0
        val staleRuns = operationRunDao.getStaleRunning(staleThresholdMs)
        for (run in staleRuns) {
            if (!run.operationType.contains("BANK", ignoreCase = true)) continue
            operationRunsRecovered += operationRunDao.finalizeIfRunning(
                id = run.id,
                status = "CANCELLED",
                finishedAt = timeProvider.now(),
                errorSummary = reasonCode
            )
        }

        // Family 2: bank statement import runs (post-parse import ledger).
        var statementRunsRecovered = 0
        val staleStatementRuns = bankStatementImportRunDao.getStaleRunningRuns(staleThresholdMs)
        for (run in staleStatementRuns) {
            bankStatementImportRunDao.markStaleFailed(
                runId = run.id,
                now = timeProvider.now(),
                reason = reasonCode
            )
            statementRunsRecovered++
        }

        return RecoveryResult(
            operationRunsRecovered = operationRunsRecovered,
            statementRunsRecovered = statementRunsRecovered
        )
    }
}
