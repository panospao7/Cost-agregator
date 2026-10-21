package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.PendingWorkerTestFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the mapping between the real [RestoreMaintenanceMode.Mode] state machine and the
 * published [AppOperationalState] flow. Drives REAL transitions (real SharedPreferences under
 * Robolectric, real [RestoreMaintenanceMode.enter] / [RestoreMaintenanceMode.exit]); nothing in
 * [RestoreMaintenanceMode] is mocked.
 *
 * Replaces the former AppOperationalStateTest, which mocked RestoreMaintenanceMode and asserted a
 * value it had built itself. Normal / CriticalRecoveryRequired flows through exit and startup
 * recovery are additionally covered by RestoreMaintenanceModePersistenceTest (split out of the former
 * P7BugFixesTest in B6-21), RestoreMaintenanceModeConfirmationFaultTest
 * and AppStartupCoordinatorRecoveryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreMaintenanceModeOperationalStateTest {

    private lateinit var context: Context
    private val fixedTime = 1716163200000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setWorkerFactory(PendingWorkerTestFactory())
                .build()
        )
        File(context.noBackupFilesDir, "restore_maintenance_critical").delete()
        context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newMode() = RestoreMaintenanceMode(context, FakeTimeProvider(fixedTime))

    private val restoreInProgressModes = listOf(
        RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
        RestoreMaintenanceMode.Mode.RESTORE_STAGING,
        RestoreMaintenanceMode.Mode.RESTORE_SWAPPING,
        RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
        RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK,
        RestoreMaintenanceMode.Mode.ASSETS_RESTORING,
        RestoreMaintenanceMode.Mode.RESETTING_DATABASE
    )

    @Test
    fun fresh_instance_publishes_Normal_and_allows_writes() {
        val mode = newMode()

        assertEquals(AppOperationalState.Normal, mode.operationalStateFlow.value)
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mode.modeFlow.value)
        assertTrue(mode.isWritesAllowed())
    }

    @Test
    fun entering_BACKUP_EXPORTING_publishes_BackupExporting_and_blocks_writes() {
        val mode = newMode()

        mode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)

        assertEquals(AppOperationalState.BackupExporting, mode.operationalStateFlow.value)
        assertFalse(mode.isWritesAllowed())
    }

    @Test
    fun entering_each_restore_mode_publishes_RestoreInProgress_carrying_that_mode() {
        for (target in restoreInProgressModes) {
            context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
                .edit().clear().commit()
            val mode = newMode()

            mode.enter(target)

            assertEquals(
                "operational state for $target",
                AppOperationalState.RestoreInProgress(target),
                mode.operationalStateFlow.value
            )
            assertEquals("mode flow for $target", target, mode.modeFlow.value)
            assertFalse("writes must be blocked in $target", mode.isWritesAllowed())
        }
    }

    @Test
    fun forced_restart_required_exit_publishes_RestartRequiredAfterRestore_and_blocks_writes() {
        val mode = newMode()
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)

        mode.exit(forceRestartRequired = true)

        assertEquals(AppOperationalState.RestartRequiredAfterRestore, mode.operationalStateFlow.value)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, mode.modeFlow.value)
        assertFalse(mode.isWritesAllowed())
    }

    @Test
    fun entering_critical_recovery_publishes_CriticalRecoveryRequired_and_blocks_writes() {
        val mode = newMode()

        mode.enterCriticalRecoveryRequired("TEST_CRITICAL")

        val state = mode.operationalStateFlow.value
        assertTrue(state is AppOperationalState.CriticalRecoveryRequired)
        assertEquals("TEST_CRITICAL", (state as AppOperationalState.CriticalRecoveryRequired).reason)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.modeFlow.value)
        assertFalse(mode.isWritesAllowed())
    }

    @Test
    fun sequential_transitions_update_the_published_state_at_every_step() {
        val mode = newMode()
        val observed = mutableListOf<AppOperationalState>()

        mode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        observed += mode.operationalStateFlow.value
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        observed += mode.operationalStateFlow.value
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
        observed += mode.operationalStateFlow.value
        mode.exit(forceRestartRequired = true)
        observed += mode.operationalStateFlow.value

        assertEquals(
            listOf(
                AppOperationalState.BackupExporting,
                AppOperationalState.RestoreInProgress(RestoreMaintenanceMode.Mode.RESTORE_PREPARING),
                AppOperationalState.RestoreInProgress(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING),
                AppOperationalState.RestartRequiredAfterRestore
            ),
            observed
        )
    }
}
