package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportReadBarrierTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var readBarrier: DatabaseReadBarrier

    @Before
    fun setup() {
        readBarrier = DatabaseReadBarrier(maintenanceMode)
    }

    private fun setMode(mode: RestoreMaintenanceMode.Mode) {
        every { maintenanceMode.currentMode() } returns mode
    }

    // ── restore_blocks_export_generation ─────────────────────────

    @Test
    fun restore_blocks_export_generation() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(
                DatabaseAccessOperation("ExportDataRepository.export", pipeline = "P12"),
                DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
            )
        }
    }

    // ── restart_required_blocks_export_generation ─────────────────

    @Test
    fun restart_required_blocks_export_generation() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(
                DatabaseAccessOperation("ExportOptionsViewModel.generateExport", pipeline = "P12"),
                DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
            )
        }
    }

    // ── backup_export_policy_allows_only_snapshot_reads ───────────

    @Test
    fun export_read_allowed_in_NORMAL() {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        readBarrier.checkReadAllowed(
            DatabaseAccessOperation("export"),
            DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
        ) // must not throw
    }

    @Test
    fun export_read_allowed_in_BACKUP_EXPORTING() {
        setMode(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        readBarrier.checkReadAllowed(
            DatabaseAccessOperation("export"),
            DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
        ) // must not throw
    }

    @Test
    fun normal_app_read_blocked_in_BACKUP_EXPORTING() {
        setMode(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            readBarrier.checkReadAllowed(
                DatabaseAccessOperation("dashboard"),
                DatabaseReadPolicy.NORMAL_APP_READ
            )
        }
    }

    // ── restore_blocks_import ─────────────────────────────────────

    @Test
    fun restore_blocks_all_non_NORMAL_modes_for_normal_reads() {
        val blockingModes = RestoreMaintenanceMode.Mode.entries.filter {
            it != RestoreMaintenanceMode.Mode.NORMAL
        }
        for (mode in blockingModes) {
            setMode(mode)
            assertThrows("Expected block in $mode", DatabaseAccessBlockedException::class.java) {
                readBarrier.checkReadAllowed(
                    DatabaseAccessOperation("dashboard"),
                    DatabaseReadPolicy.NORMAL_APP_READ
                )
            }
        }
    }

    // ── guardedDatabaseRead Flow helper ───────────────────────────

    @Test
    fun guardedDatabaseRead_passes_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        val flow = kotlinx.coroutines.flow.flowOf(1, 2, 3)
            .guardedDatabaseRead(readBarrier, "test")
        assertEquals(listOf(1, 2, 3), flow.toList())
    }

    @Test
    fun guardedDatabaseRead_throws_in_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val flow = kotlinx.coroutines.flow.flowOf(1, 2, 3)
            .guardedDatabaseRead(readBarrier, "test")
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { flow.toList() }
        }
    }

    // ── blockedDuringRestore Flow helper ──────────────────────────

    @Test
    fun blockedDuringRestore_emits_in_NORMAL() = runTest {
        val modeFlow = MutableStateFlow(RestoreMaintenanceMode.Mode.NORMAL)
        val flow = kotlinx.coroutines.flow.flowOf(42)
            .blockedDuringRestore(modeFlow, "test")
        val results = flow.toList()
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun blockedDuringRestore_emits_nothing_in_restore() = runTest {
        val modeFlow = MutableStateFlow(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val flow = kotlinx.coroutines.flow.flowOf(42)
            .blockedDuringRestore(modeFlow, "test")
        val results = flow.toList()
        assertTrue(results.isEmpty())
    }
}
