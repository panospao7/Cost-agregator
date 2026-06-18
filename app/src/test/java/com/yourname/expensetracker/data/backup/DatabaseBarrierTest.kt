package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DatabaseBarrierTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var readBarrier: DatabaseReadBarrier

    @Before
    fun setup() {
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        readBarrier = DatabaseReadBarrier(maintenanceMode)
    }

    private fun setMode(mode: RestoreMaintenanceMode.Mode) {
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns (mode == RestoreMaintenanceMode.Mode.NORMAL)
    }

    // ── Write barrier ─────────────────────────────────────────────

    @Test
    fun write_allowed_in_NORMAL() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        writeBarrier.checkWritesAllowed("test_op") // must not throw
    }

    @Test
    fun write_blocked_in_BACKUP_EXPORTING() {
        setMode(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        val ex = assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed("test_op")
        }
        assertEquals(DatabaseAccessType.WRITE, ex.accessType)
        assertEquals(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING, ex.mode)
    }

    @Test
    fun write_blocked_in_RESTORE_PREPARING() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed("test_op")
        }
    }

    @Test
    fun write_blocked_in_RESTORE_COMPLETE_RESTART_REQUIRED() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed("test_op")
        }
    }

    @Test
    fun write_blocked_in_RESETTING_DATABASE() {
        setMode(RestoreMaintenanceMode.Mode.RESETTING_DATABASE)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed("test_op")
        }
    }

    @Test
    fun write_blocked_in_CRITICAL_RECOVERY_REQUIRED() {
        setMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed("test_op")
        }
    }

    @Test
    fun runWrite_executes_block_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        val result = writeBarrier.runWrite(DatabaseAccessOperation("op")) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun runWrite_throws_in_non_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_STAGING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeBarrier.runWrite(DatabaseAccessOperation("op")) { } }
        }
    }

    // ── Read barrier — NORMAL_APP_READ ────────────────────────────

    @Test
    fun normal_read_allowed_in_NORMAL() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.NORMAL_APP_READ)
    }

    @Test
    fun normal_read_blocked_in_BACKUP_EXPORTING() {
        setMode(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        val ex = assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.NORMAL_APP_READ)
        }
        assertEquals(DatabaseAccessType.READ, ex.accessType)
    }

    @Test
    fun normal_read_blocked_in_RESTORE_VERIFYING() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.NORMAL_APP_READ)
        }
    }

    // ── Read barrier — EXPORT_OR_BACKUP_SNAPSHOT_READ ─────────────

    @Test
    fun export_read_allowed_in_NORMAL() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
    }

    @Test
    fun export_read_allowed_in_BACKUP_EXPORTING() {
        setMode(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
    }

    @Test
    fun export_read_blocked_in_RESTORE_VERIFYING() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(DatabaseAccessOperation("op"), DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        }
    }

    // ── Read barrier — RESTORE_INTERNAL_STAGED_DB_READ ───────────

    @Test
    fun staged_db_read_always_blocked_through_app_singleton() {
        for (mode in RestoreMaintenanceMode.Mode.entries) {
            setMode(mode)
            assertThrows("Expected block in mode $mode", DatabaseAccessBlockedException::class.java) {
                readBarrier.checkReadAllowed(
                    DatabaseAccessOperation("staged_op"),
                    DatabaseReadPolicy.RESTORE_INTERNAL_STAGED_DB_READ
                )
            }
        }
    }

    // ── Compatibility overload ────────────────────────────────────

    @Test
    fun string_overload_still_works_for_write() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        writeBarrier.checkWritesAllowed("legacy_op")
    }

    @Test
    fun string_overload_still_works_for_read() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        readBarrier.checkReadAllowed("legacy_op")
    }

    @Test
    fun string_overload_read_blocked_in_restore() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed("legacy_op")
        }
    }

    // ── Exception carries correct metadata ───────────────────────

    @Test
    fun exception_carries_operation_metadata() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
        val op = DatabaseAccessOperation(
            name = "saveExpense",
            pipeline = "P2",
            entity = "Expense"
        )
        val ex = assertThrows(DatabaseAccessBlockedException::class.java) {
            writeBarrier.checkWritesAllowed(op)
        }
        assertEquals("saveExpense", ex.operation.name)
        assertEquals("P2", ex.operation.pipeline)
        assertEquals("Expense", ex.operation.entity)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, ex.mode)
    }
}
