package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.RoomOperationRunRecorder
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private lateinit var leaseRegistry: WorkerLeaseRegistryImpl

    companion object {
        private const val FIXED_NOW = 1_710_000_000_000L
        private const val STALE_CUTOFF = FIXED_NOW - (15 * 60 * 1000L)
    }

    @Before
    fun setUp() {
        leaseRegistry = WorkerLeaseRegistryImpl(writeBarrier, object : MonotonicTimeProvider {
            override fun nowNanos(): Long = 0L
        })
        val ledgerWriter = RoomOperationRunRecorder(
            runDao = operationRunDao,
            eventDao = mockk(relaxed = true),
            sanitizer = mockk(relaxed = true),
            timeProvider = timeProvider,
            safeSink = mockk(relaxed = true),
            restoreMaintenanceMode = mockk(relaxed = true)
        )
        coEvery { bankStatementImportRunDao.markStaleFailed(any(), any(), any(), any()) } returns 1
        recovery = BankSyncStartupRecovery(
            operationRunRecovery = ledgerWriter,
            bankStatementImportRunDao = bankStatementImportRunDao,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            leaseRegistry = leaseRegistry
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
        coVerify(exactly = 0) { bankStatementImportRunDao.markStaleFailed(any(), any(), any(), any()) }
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
                cutoffMs = STALE_CUTOFF,
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
        coVerify(exactly = 0) { bankStatementImportRunDao.markStaleFailed(any(), any(), any(), any()) }
    }

    @Test
    fun zero_affected_rows_are_not_counted_as_recovered() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } returns listOf(staleOperationRun(1, "BANK_SYNC"))
        coEvery { operationRunDao.finalizeIfRunning(any(), any(), any(), any()) } returns 0
        coEvery { bankStatementImportRunDao.getStaleRunningRuns(STALE_CUTOFF) } returns listOf(
            BankStatementImportRun(11, null, null, "corr-11", "RUNNING", STALE_CUTOFF - 1)
        )
        coEvery { bankStatementImportRunDao.markStaleFailed(11, STALE_CUTOFF, FIXED_NOW, any()) } returns 0

        assertEquals(BankSyncStartupRecovery.RecoveryResult(0, 0), recovery.recoverStaleRuns(STALE_CUTOFF))
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }

    @Test
    fun sealed_maintenance_admission_performs_no_database_reads() = runTest {
        leaseRegistry.requestStopAll("TEST_MAINTENANCE")

        assertEquals(BankSyncStartupRecovery.RecoveryResult(0, 0), recovery.recoverStaleRuns(STALE_CUTOFF))
        coVerify(exactly = 0) { operationRunDao.getStaleRunning(any()) }
        coVerify(exactly = 0) { bankStatementImportRunDao.getStaleRunningRuns(any()) }
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }

    @Test
    fun maintenance_during_operation_read_stops_before_write_and_releases_lease() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } coAnswers {
            assertFalse("Maintenance must see the in-flight recovery lease", leaseRegistry.awaitNoActiveWorkers(0))
            leaseRegistry.requestStopAll("TEST_MAINTENANCE")
            listOf(staleOperationRun(1, "BANK_SYNC"))
        }
        try {
            recovery.recoverStaleRuns(STALE_CUTOFF)
            fail("Expected maintenance cancellation")
        } catch (_: CancellationException) {
            // Cancellation is propagated, not converted to successful recovery.
        }
        coVerify(exactly = 0) { operationRunDao.finalizeIfRunning(any(), any(), any(), any()) }
        coVerify(exactly = 0) { bankStatementImportRunDao.getStaleRunningRuns(any()) }
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }

    @Test
    fun maintenance_during_statement_read_stops_before_write_and_releases_lease() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } returns emptyList()
        coEvery { bankStatementImportRunDao.getStaleRunningRuns(STALE_CUTOFF) } coAnswers {
            assertFalse(leaseRegistry.awaitNoActiveWorkers(0))
            leaseRegistry.requestStopAll("TEST_MAINTENANCE")
            listOf(BankStatementImportRun(11, null, null, "corr-11", "RUNNING", STALE_CUTOFF - 1))
        }
        try {
            recovery.recoverStaleRuns(STALE_CUTOFF)
            fail("Expected maintenance cancellation")
        } catch (_: CancellationException) {
            // The suspended selection cannot authorize a later mutation.
        }
        coVerify(exactly = 0) { bankStatementImportRunDao.markStaleFailed(any(), any(), any(), any()) }
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }

    @Test
    fun database_failure_propagates_and_releases_recovery_lease() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } throws IllegalStateException("TEST_READ_FAILED")
        try {
            recovery.recoverStaleRuns(STALE_CUTOFF)
            fail("Expected database failure")
        } catch (failure: IllegalStateException) {
            assertEquals("TEST_READ_FAILED", failure.message)
        }
        coVerify(exactly = 0) { bankStatementImportRunDao.getStaleRunningRuns(any()) }
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }

    @Test
    fun caller_cancellation_propagates_and_releases_recovery_lease() = runTest {
        coEvery { operationRunDao.getStaleRunning(STALE_CUTOFF) } throws CancellationException("TEST_CANCELLED")
        try {
            recovery.recoverStaleRuns(STALE_CUTOFF)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Identity is not asserted across coroutine stacktrace recovery.
        }
        coVerify(exactly = 0) { bankStatementImportRunDao.getStaleRunningRuns(any()) }
        assertTrue(leaseRegistry.awaitNoActiveWorkers(0))
    }
}
