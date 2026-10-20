package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ── Write barrier table ───────────────────────────────────────
    // Expectations are written out from DatabaseWriteBarrier.checkWritesAllowed
    // (DatabaseWriteBarrier.kt:30-39): a write is admitted only in Mode.NORMAL; every other
    // mode throws DatabaseAccessBlockedException(WRITE, mode). The persisted resume-pending
    // case lives in RestoreMaintenancePersistedBarrierRobolectricTest.

    private val writeAllowedByMode: Map<RestoreMaintenanceMode.Mode, Boolean> = mapOf(
        RestoreMaintenanceMode.Mode.NORMAL to true,
        RestoreMaintenanceMode.Mode.BACKUP_EXPORTING to false,
        RestoreMaintenanceMode.Mode.RESTORE_PREPARING to false,
        RestoreMaintenanceMode.Mode.RESTORE_STAGING to false,
        RestoreMaintenanceMode.Mode.RESTORE_SWAPPING to false,
        RestoreMaintenanceMode.Mode.RESTORE_VERIFYING to false,
        RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK to false,
        RestoreMaintenanceMode.Mode.ASSETS_RESTORING to false,
        RestoreMaintenanceMode.Mode.RESETTING_DATABASE to false,
        RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED to false,
        RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED to false
    )

    @Test
    fun write_table_covers_every_mode() {
        assertEquals(
            "write table must have exactly one row per Mode so a new Mode forces a decision",
            RestoreMaintenanceMode.Mode.entries.toSet(),
            writeAllowedByMode.keys
        )
    }

    @Test
    fun write_barrier_table_allows_only_NORMAL_and_blocks_every_other_mode_as_WRITE() {
        for (mode in RestoreMaintenanceMode.Mode.entries) {
            val rowName = "write row mode=$mode"
            val expectedAllowed = writeAllowedByMode.getValue(mode)
            // Fresh mock + barrier per row: no stub leaks between rows.
            val rowMaintenance = mockk<RestoreMaintenanceMode>()
            every { rowMaintenance.currentMode() } returns mode
            val barrier = DatabaseWriteBarrier(rowMaintenance)

            if (expectedAllowed) {
                try {
                    barrier.checkWritesAllowed(DatabaseAccessOperation("test_op"))
                } catch (t: Throwable) {
                    throw AssertionError("$rowName: expected write to be allowed but got $t", t)
                }
            } else {
                val ex = assertThrows(
                    "$rowName: expected write to be blocked",
                    DatabaseAccessBlockedException::class.java
                ) {
                    barrier.checkWritesAllowed(DatabaseAccessOperation("test_op"))
                }
                assertEquals("$rowName: accessType", DatabaseAccessType.WRITE, ex.accessType)
                assertEquals("$rowName: ex.mode", mode, ex.mode)
            }
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
        val exception = runCatching {
            writeBarrier.runWrite(DatabaseAccessOperation("op")) { }
        }.exceptionOrNull()
        assertNotNull("runWrite must throw in non-NORMAL mode", exception)
        assertEquals(DatabaseAccessBlockedException::class, exception!!::class)
    }

    // ── Read barrier table (policy x mode) ────────────────────────
    // Allow-sets are written out from DatabaseReadBarrier.checkReadAllowed
    // (DatabaseReadBarrier.kt:20-30):
    //   NORMAL_APP_READ                -> {NORMAL}                     (:21-22)
    //   EXPORT_OR_BACKUP_SNAPSHOT_READ -> {NORMAL, BACKUP_EXPORTING}   (:24-26)
    //   RESTORE_INTERNAL_STAGED_DB_READ-> {} (always blocked on the app singleton)  (:29)
    // Every other (policy, mode) row throws DatabaseAccessBlockedException(READ, mode)  (:31-37).

    private val readAllowedModesByPolicy: Map<DatabaseReadPolicy, Set<RestoreMaintenanceMode.Mode>> = mapOf(
        DatabaseReadPolicy.NORMAL_APP_READ to setOf(
            RestoreMaintenanceMode.Mode.NORMAL
        ),
        DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ to setOf(
            RestoreMaintenanceMode.Mode.NORMAL,
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        ),
        DatabaseReadPolicy.RESTORE_INTERNAL_STAGED_DB_READ to emptySet()
    )

    private fun assertReadTableForPolicy(policy: DatabaseReadPolicy) {
        val allowedModes = readAllowedModesByPolicy.getValue(policy)
        for (mode in RestoreMaintenanceMode.Mode.entries) {
            val rowName = "read row policy=$policy mode=$mode"
            // Fresh mock + barrier per row: no stub leaks between rows.
            val rowMaintenance = mockk<RestoreMaintenanceMode>()
            every { rowMaintenance.currentMode() } returns mode
            val barrier = DatabaseReadBarrier(rowMaintenance)
            val operation = DatabaseAccessOperation("table_op")

            if (mode in allowedModes) {
                try {
                    barrier.checkReadAllowed(operation, policy)
                } catch (t: Throwable) {
                    throw AssertionError("$rowName: expected read to be allowed but got $t", t)
                }
            } else {
                val ex = assertThrows(
                    "$rowName: expected read to be blocked",
                    DatabaseAccessBlockedException::class.java
                ) {
                    barrier.checkReadAllowed(operation, policy)
                }
                assertEquals("$rowName: accessType", DatabaseAccessType.READ, ex.accessType)
                assertEquals("$rowName: ex.mode", mode, ex.mode)
                assertEquals("$rowName: ex.operation", operation, ex.operation)
            }
        }
    }

    @Test
    fun read_table_covers_every_policy() {
        assertEquals(
            "read table must have exactly one allow-set per DatabaseReadPolicy so a new policy forces a decision",
            DatabaseReadPolicy.entries.toSet(),
            readAllowedModesByPolicy.keys
        )
    }

    // One test over every policy: NORMAL_APP_READ allows only NORMAL; EXPORT_OR_BACKUP_SNAPSHOT_READ
    // allows only NORMAL and BACKUP_EXPORTING; RESTORE_INTERNAL_STAGED_DB_READ is always blocked
    // through the app singleton. A policy without a table entry fails with its name.
    @Test
    fun read_table_every_policy_allows_only_its_documented_modes() {
        for (policy in DatabaseReadPolicy.entries) {
            if (policy !in readAllowedModesByPolicy) {
                throw AssertionError("read table has no allow-set entry for policy=$policy")
            }
            try {
                assertReadTableForPolicy(policy)
            } catch (e: AssertionError) {
                throw AssertionError("policy=$policy: ${e.message}", e)
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
