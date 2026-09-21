package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RP-17 17-E: startup recovery of stale bank bookkeeping — two independent
 * recovery families (bank operation runs, bank statement import runs), each
 * finalized separately with the controlled STALE_RUNNING_ABORTED reason code,
 * never touching non-bank rows, never writing while the restore barrier denies.
 */
class BankSyncStartupRecoveryTest {

    private val operationRunDao: OperationRunDao = mockk(relaxed = true)
    private val bankStatementImportRunDao: BankStatementImportRunDao = mockk(relaxed = true)
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private lateinit var recovery: BankSyncStartupRecovery

    companion object {
        private const val FIXED_NOW = 1_710_000_000_000L
        private const val STALE_CUTOFF = FIXED_NOW - (15 * 60 * 1000L)
    }

    @Before
    fun setUp() {
        recovery = BankSyncStartupRecovery(
            operationRunDao = operationRunDao,
            bankStatementImportRunDao = bankStatementImportRunDao,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    private fun staleOperationRun(id: Long, type: String) = OperationRun(
        id = id,
        correlationId = "corr-$id",
        operationType = type,
        status = "RUNNING",
        startedAt = STALE_CUTOFF - 1
    )

    @Test
    fun `stale bank operation runs are finalized as CANCELLED with controlled code`() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } returns listOf(
            staleOperationRun(1, "BANK_SYNC"),
            staleOperationRun(2, "BANK_CONNECTION")
        )
        coEvery { operationRunDao.finalizeIfRunning(any(), any(), any(), any()) } returns 1
        coEvery { bankStatementImportRunDao.getStaleRunningRuns(any()) } returns emptyList()

        val result = recovery.recoverStaleRuns(STALE_CUTOFF)

        assertEquals(2, result.operationRunsRecovered)
        coVerify(exactly = 2) {
            operationRunDao.finalizeIfRunning(
                id = any(),
                status = "CANCELLED",
                finishedAt = FIXED_NOW,
                errorSummary = DiagnosticReasonCode.STALE_RUNNING_ABORTED.name
            )
        }
        coVerify(exactly = 0) { bankStatementImportRunDao.markStaleFailed(any(), any(), any()) }
    }

    @Test
    fun `non-bank stale operation runs are untouched`() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } returns listOf(
            staleOperationRun(1, "RESTORE"),
            staleOperationRun(2, "BACKUP")
        )
        coEvery { bankStatementImportRunDao.getStaleRunningRuns(any()) } returns emptyList()

        val result = recovery.recoverStaleRuns(STALE_CUTOFF)

        assertEquals(0, result.operationRunsRecovered)
        coVerify(exactly = 0) { operationRunDao.finalizeIfRunning(any(), any(), any(), any()) }
    }

    @Test
    fun `stale statement import runs are marked STALE_FAILED separately`() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } returns emptyList()
        coEvery { bankStatementImportRunDao.getStaleRunningRuns(STALE_CUTOFF) } returns listOf(
            BankStatementImportRun(
                id = 11,
                statementReceiptId = null,
                sourceFingerprint = null,
                correlationId = "corr-11",
                status = BankStatementImportRun.STATUS_RUNNING,
                startedAt = STALE_CUTOFF - 1
            )
        )

        val result = recovery.recoverStaleRuns(STALE_CUTOFF)

        assertEquals(0, result.operationRunsRecovered)
        assertEquals(1, result.statementRunsRecovered)
        coVerify(exactly = 1) {
            bankStatementImportRunDao.markStaleFailed(
                runId = 11,
                now = FIXED_NOW,
                reason = DiagnosticReasonCode.STALE_RUNNING_ABORTED.name
            )
        }
    }

    @Test
    fun `barrier denied means no recovery writes`() = runTest {
        coEvery {
            writeBarrier.checkWritesAllowed("BankSyncStartupRecovery.recoverStaleRuns")
        } throws com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
            accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
            operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation(
                "BankSyncStartupRecovery.recoverStaleRuns"
            ),
            mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val result = recovery.recoverStaleRuns(STALE_CUTOFF)

        assertEquals(0, result.operationRunsRecovered)
        assertEquals(0, result.statementRunsRecovered)
        coVerify(exactly = 0) { operationRunDao.getStaleRunning(any()) }
        coVerify(exactly = 0) { bankStatementImportRunDao.getStaleRunningRuns(any()) }
        coVerify(exactly = 0) { operationRunDao.finalizeIfRunning(any(), any(), any(), any()) }
        coVerify(exactly = 0) { bankStatementImportRunDao.markStaleFailed(any(), any(), any()) }
    }
}
