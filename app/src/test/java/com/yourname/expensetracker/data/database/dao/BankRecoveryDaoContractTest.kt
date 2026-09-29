package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
import com.yourname.expensetracker.data.database.entity.OperationRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room predicates and affected-row results, not a mock of the SQL contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BankRecoveryDaoContractTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: BankStatementImportRunDao

    @Before
    fun setUp() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        dao = database.bankStatementImportRunDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insert(startedAt: Long, status: String = "RUNNING"): Long = dao.insert(
        BankStatementImportRun(
            statementReceiptId = null,
            sourceFingerprint = null,
            correlationId = "test-$startedAt-$status",
            status = status,
            startedAt = startedAt
        )
    )

    @Test
    fun stale_running_transition_is_conditional_and_repeat_is_a_noop() = runTest {
        val id = insert(99)
        assertEquals(1, dao.markStaleFailed(id, 100, 200, "STALE_RUNNING_ABORTED"))
        val terminal = dao.getById(id)!!
        assertEquals("STALE_FAILED", terminal.status)
        assertEquals(200L, terminal.completedAt)
        assertEquals("STALE_RUNNING_ABORTED", terminal.errorSummary)
        assertEquals(0, dao.markStaleFailed(id, 100, 300, "STALE_RUNNING_ABORTED"))
        assertEquals(terminal, dao.getById(id))
    }

    @Test
    fun cutoff_boundary_and_all_terminal_statuses_are_preserved() = runTest {
        for (startedAt in listOf(100L, 101L)) {
            val id = insert(startedAt)
            val before = dao.getById(id)
            assertEquals(0, dao.markStaleFailed(id, 100, 200, "STALE_RUNNING_ABORTED"))
            assertEquals(before, dao.getById(id))
        }
        for (status in listOf("COMPLETED", "COMPLETED_WITH_SKIPS", "FAILED", "STALE_FAILED", "CANCELLED")) {
            val id = insert(99, status)
            val before = dao.getById(id)
            assertEquals(0, dao.markStaleFailed(id, 100, 200, "STALE_RUNNING_ABORTED"))
            assertEquals(before, dao.getById(id))
        }
    }

    @Test
    fun completion_after_stale_selection_is_not_overwritten() = runTest {
        val id = insert(99)
        assertEquals(listOf(id), dao.getStaleRunningRuns(100).map { it.id })
        dao.finalize(id, "COMPLETED", 150, 3, 3, 2, 1, 0, 0, null)
        val completed = dao.getById(id)
        assertEquals(0, dao.markStaleFailed(id, 100, 200, "STALE_RUNNING_ABORTED"))
        assertEquals(completed, dao.getById(id))
    }

    @Test
    fun deletion_after_selection_and_missing_row_return_zero() = runTest {
        val id = insert(99)
        assertEquals(listOf(id), dao.getStaleRunningRuns(100).map { it.id })
        withContext(Dispatchers.IO) { database.clearAllTables() }
        assertEquals(0, dao.markStaleFailed(id, 100, 200, "STALE_RUNNING_ABORTED"))
        assertEquals(0, dao.markStaleFailed(Long.MAX_VALUE, 100, 200, "STALE_RUNNING_ABORTED"))
        assertNull(dao.getById(id))
    }

    @Test
    fun operation_run_terminal_race_keeps_first_terminal_state() = runTest {
        val operationDao = database.operationRunDao()
        val id = operationDao.insert(OperationRun(correlationId = "operation", operationType = "BANK_SYNC", status = "RUNNING", startedAt = 99))
        assertEquals(listOf(id), operationDao.getStaleRunning(100).map { it.id })
        assertEquals(1, operationDao.finalizeIfRunning(id, "SUCCESS", 150, null))
        val completed = operationDao.getById(id)
        assertEquals(0, operationDao.finalizeIfRunning(id, "CANCELLED", 200, "STALE_RUNNING_ABORTED"))
        assertEquals(completed, operationDao.getById(id))
    }
}
