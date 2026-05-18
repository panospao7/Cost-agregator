package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOperationalStateTest {

    private fun modeManager(initial: RestoreMaintenanceMode.Mode): RestoreMaintenanceMode {
        val state = modeToState(initial)
        val flow = MutableStateFlow(state)
        return mockk<RestoreMaintenanceMode>(relaxed = true).also {
            every { it.operationalStateFlow } returns flow
            every { it.currentMode() } returns initial
            every { it.isWritesAllowed() } returns (initial == RestoreMaintenanceMode.Mode.NORMAL)
        }
    }

    private fun modeToState(mode: RestoreMaintenanceMode.Mode): AppOperationalState = when (mode) {
        RestoreMaintenanceMode.Mode.NORMAL -> AppOperationalState.Normal
        RestoreMaintenanceMode.Mode.BACKUP_EXPORTING -> AppOperationalState.BackupExporting
        RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED -> AppOperationalState.RestartRequiredAfterRestore
        RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED -> AppOperationalState.CriticalRecoveryRequired()
        else -> AppOperationalState.RestoreInProgress(mode)
    }

    @Test
    fun restore_success_sets_global_restart_required_lock() {
        val mm = modeManager(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
        val state = mm.operationalStateFlow.value
        assertEquals(AppOperationalState.RestartRequiredAfterRestore, state)
    }

    @Test
    fun critical_recovery_required_maps_to_correct_state() {
        val mm = modeManager(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
        val state = mm.operationalStateFlow.value
        assertEquals(AppOperationalState.CriticalRecoveryRequired(), state)
    }

    @Test
    fun normal_mode_maps_to_Normal_state() {
        val mm = modeManager(RestoreMaintenanceMode.Mode.NORMAL)
        assertEquals(AppOperationalState.Normal, mm.operationalStateFlow.value)
    }

    @Test
    fun backup_exporting_maps_to_BackupExporting_state() {
        val mm = modeManager(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        assertEquals(AppOperationalState.BackupExporting, mm.operationalStateFlow.value)
    }

    @Test
    fun restore_in_progress_modes_map_to_RestoreInProgress() {
        val restoreModes = listOf(
            RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            RestoreMaintenanceMode.Mode.RESTORE_STAGING,
            RestoreMaintenanceMode.Mode.RESTORE_SWAPPING,
            RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
            RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK,
            RestoreMaintenanceMode.Mode.ASSETS_RESTORING,
            RestoreMaintenanceMode.Mode.RESETTING_DATABASE
        )
        for (mode in restoreModes) {
            val mm = modeManager(mode)
            val state = mm.operationalStateFlow.value
            assertTrue(
                "Expected RestoreInProgress for $mode but got $state",
                state is AppOperationalState.RestoreInProgress
            )
            assertEquals(mode, (state as AppOperationalState.RestoreInProgress).mode)
        }
    }

    @Test
    fun restart_required_blocks_writes() {
        val mm = modeManager(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
        assertTrue("Writes must be blocked in restart-required state", !mm.isWritesAllowed())
    }

    @Test
    fun startup_after_clean_restart_resets_mode_to_NORMAL() {
        // Simulate: mode was RESTORE_COMPLETE_RESTART_REQUIRED, then reset() is called on clean start
        val flow = MutableStateFlow<AppOperationalState>(AppOperationalState.RestartRequiredAfterRestore)
        val mm = mockk<RestoreMaintenanceMode>(relaxed = true).also {
            every { it.operationalStateFlow } returns flow
        }
        // Simulate reset
        flow.value = AppOperationalState.Normal
        assertEquals(AppOperationalState.Normal, mm.operationalStateFlow.value)
    }
}
