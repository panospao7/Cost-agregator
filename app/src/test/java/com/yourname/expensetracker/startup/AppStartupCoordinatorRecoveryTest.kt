package com.yourname.expensetracker.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import io.mockk.mockk
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
 * Fail-closed crash-recovery contract tests for [AppStartupCoordinator.checkRestoreJournal].
 *
 * Covers **P7-CURRENT-003**: a failed startup crash recovery (or failed restore rollback)
 * must keep writes blocked across *repeated* app restarts until manual intervention.
 *
 * These tests exercise the real lifecycle path — real [RestoreJournal] (journal files on
 * disk) and real [RestoreMaintenanceMode] (mode persisted in SharedPreferences) — so the
 * cross-restart persistence is genuinely modelled rather than mocked. Process restart is
 * simulated by constructing a fresh [RestoreMaintenanceMode]/[AppStartupCoordinator] pair
 * that reads the persisted mode and (renamed) journal files, exactly as a new process would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppStartupCoordinatorRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // RestoreMaintenanceMode.enter()/enterCriticalRecoveryRequired() call
        // pauseAllWorkers() → WorkManager.getInstance(); initialise the test instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Deterministic clean slate: clear any persisted mode + journal files.
        RestoreMaintenanceMode(context).reset()
        listOf(
            "restore_journal.json",
            "restore_journal_last_failure.json",
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
    }

    private fun newCoordinator(mode: RestoreMaintenanceMode, journal: RestoreJournal): AppStartupCoordinator =
        AppStartupCoordinator(
            backgroundLifecycleObserver = mockk(relaxed = true),
            syncProactiveBriefingWorkUseCase = mockk(relaxed = true),
            restoreJournal = journal,
            restoreMaintenanceMode = mode,
            restoreDatabaseOpener = mockk<RestoreDatabaseOpener>(relaxed = true),
            workerExecutionGuard = mockk(relaxed = true),
            restoreJournalImporter = mockk(relaxed = true)
        )

    /**
     * Writes a journal in a destructive (SWAPPING) state whose safety backup is unreachable,
     * so [AppStartupCoordinator.checkRestoreJournal] cannot recover and must fail closed.
     */
    private fun writeUnrecoverableSwapJournal(journal: RestoreJournal) {
        var entry = journal.beginJournal(
            sourceBackupPath = File(context.cacheDir, "src.costbackup").absolutePath,
            stagedDbPath = context.getDatabasePath("staged_restore.db").absolutePath,
            liveDbPath = context.getDatabasePath("expense_tracker.db").absolutePath
        )
        // Safety backup path points at a file that does not exist → recovery copy cannot run.
        entry = journal.transitionTo(
            entry,
            RestoreJournal.JournalState.SWAPPING,
            safetyBackupPath = File(context.filesDir, "missing_safety_backup.db").absolutePath
        )
        // Sanity: the recovery decision should classify this as a swap-recovery attempt.
        assertTrue(
            "Expected RecoveredFromSwap for SWAPPING state",
            journal.checkAndRecover() is RestoreJournal.RecoveryResult.RecoveredFromSwap
        )
        // checkAndRecover() above is non-destructive for SWAPPING (it only reads), but to be
        // safe re-establish the journal exactly as written so the coordinator sees it fresh.
        journal.writeJournal(entry)
    }

    @Test
    fun `failed crash recovery enters CRITICAL_RECOVERY_REQUIRED and blocks writes`() {
        val mode = RestoreMaintenanceMode(context)
        val journal = RestoreJournal(context)
        writeUnrecoverableSwapJournal(journal)

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Failed recovery must enter the persistent critical mode",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode.currentMode()
        )
        assertFalse("Writes must be blocked after failed recovery", mode.isWritesAllowed())
        // failJournal() renames the active journal away — no active journal remains.
        assertFalse("Active journal must be renamed to the failure record", journal.hasJournal())
    }

    @Test
    fun `CRITICAL_RECOVERY_REQUIRED survives the next restart with writes still blocked`() {
        // ── First startup: recovery fails, enters critical mode, renames journal away ──
        run {
            val mode = RestoreMaintenanceMode(context)
            val journal = RestoreJournal(context)
            writeUnrecoverableSwapJournal(journal)
            newCoordinator(mode, journal).checkRestoreJournal()
            assertEquals(
                RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                mode.currentMode()
            )
        }

        // ── Second startup (process restart): fresh instances read persisted prefs/files ──
        val mode2 = RestoreMaintenanceMode(context)
        val journal2 = RestoreJournal(context)
        // No active journal exists now, so checkAndRecover() returns NoAction.
        assertFalse("No active journal should remain on second startup", journal2.hasJournal())
        assertEquals(
            "Persisted mode must still be critical before second startup",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode2.currentMode()
        )

        newCoordinator(mode2, journal2).checkRestoreJournal()

        assertEquals(
            "Critical mode must NOT be auto-reset on a subsequent restart",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode2.currentMode()
        )
        assertFalse("Writes must remain blocked across restarts", mode2.isWritesAllowed())
    }

    @Test
    fun `successful restart-required mode IS reset to NORMAL on a clean restart`() {
        // Regression guard: the success "please restart" mode (set after a successful restore)
        // must still auto-reset on the next clean startup, unlike CRITICAL_RECOVERY_REQUIRED.
        val mode = RestoreMaintenanceMode(context)
        mode.exit(forceRestartRequired = true)
        assertEquals(
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )

        // Fresh startup, no journal on disk.
        val journal = RestoreJournal(context)
        assertFalse(journal.hasJournal())
        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Restart-required success mode should reset to NORMAL after restart",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertTrue("Writes should resume after a successful restore + restart", mode.isWritesAllowed())
    }
}
