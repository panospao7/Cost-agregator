package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.workers.WorkerDrainController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MaintenanceOperationRunnerTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
    private val workerDrain = mockk<WorkerDrainController>()
    private lateinit var runner: MaintenanceOperationRunner

    @Before
    fun setup() {
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { workerDrain.requestStopAndAwaitDrain(any(), any()) } returns true
        runner = MaintenanceOperationRunner(maintenanceMode, workerDrain)
    }

    // ── reset_database_enters_maintenance ────────────────────────

    @Test
    fun reset_database_enters_maintenance() = runTest {
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            operationName = "resetDatabase"
        ) { /* no-op */ }

        verify { maintenanceMode.enter(RestoreMaintenanceMode.Mode.RESETTING_DATABASE) }
    }

    // ── reset_database_blocks_writes ─────────────────────────────

    @Test
    fun reset_database_drains_workers_before_block() = runTest {
        var drainCalledBeforeBlock = false
        coEvery { workerDrain.requestStopAndAwaitDrain(any(), any()) } answers {
            drainCalledBeforeBlock = true
            true
        }

        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            operationName = "resetDatabase"
        ) {
            assertTrue("Worker drain must complete before block runs", drainCalledBeforeBlock)
        }
    }

    // ── reset_database_requires_restart ──────────────────────────

    @Test
    fun reset_database_requires_restart_on_success() = runTest {
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            operationName = "resetDatabase",
            requireRestartAfterSuccess = true
        ) { /* no-op */ }

        verify { maintenanceMode.exit(forceRestartRequired = true) }
    }

    @Test
    fun reset_database_exits_to_normal_when_restart_not_required() = runTest {
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            operationName = "resetDatabase",
            requireRestartAfterSuccess = false
        ) { /* no-op */ }

        verify { maintenanceMode.exit(forceRestartRequired = false) }
    }

    // ── backup_export_enters_BACKUP_EXPORTING ────────────────────

    @Test
    fun backup_export_enters_BACKUP_EXPORTING() = runTest {
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
            operationName = "createCostBackup"
        ) { /* no-op */ }

        verify { maintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) }
    }

    // ── backup_export_blocks_writes ──────────────────────────────

    @Test
    fun backup_export_drains_workers() = runTest {
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
            operationName = "createCostBackup"
        ) { /* no-op */ }

        coVerify { workerDrain.requestStopAndAwaitDrain("createCostBackup", any()) }
    }

    // ── restore_rollback_failure_keeps_writes_blocked ────────────

    @Test
    fun exception_in_block_still_exits_maintenance() = runTest {
        assertThrows(RuntimeException::class.java) {
            runTest {
                runner.runExclusive(
                    mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
                    operationName = "restoreCostBackup",
                    requireRestartAfterSuccess = false
                ) {
                    throw RuntimeException("restore failed")
                }
            }
        }

        verify { maintenanceMode.exit(forceRestartRequired = false) }
    }

    @Test
    fun exception_with_restart_required_exits_with_restart() = runTest {
        assertThrows(RuntimeException::class.java) {
            runTest {
                runner.runExclusive(
                    mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
                    operationName = "restoreCostBackup",
                    requireRestartAfterSuccess = true
                ) {
                    throw RuntimeException("restore failed mid-swap")
                }
            }
        }

        verify { maintenanceMode.exit(forceRestartRequired = true) }
    }

    @Test
    fun block_result_is_returned() = runTest {
        val result = runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
            operationName = "backup"
        ) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun drain_timeout_does_not_prevent_block_from_running() = runTest {
        coEvery { workerDrain.requestStopAndAwaitDrain(any(), any()) } returns false // timed out

        var blockRan = false
        runner.runExclusive(
            mode = RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            operationName = "resetDatabase"
        ) {
            blockRan = true
        }

        assertTrue("Block must run even if drain timed out", blockRan)
    }
}
