package com.yourname.expensetracker.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import timber.log.Timber

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
 *
 * The ASSETS_RESTORING resume runs on an injected application scope; by default the tests
 * inject an eager [Dispatchers.Unconfined] scope so completion stays synchronous, and the
 * non-blocking test injects a deferred dispatcher to prove the startup call does not wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppStartupCoordinatorRecoveryTest {

    private lateinit var context: Context
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // RestoreMaintenanceMode.enter()/enterCriticalRecoveryRequired() call
        // pauseAllWorkers() → WorkManager.getInstance(); initialise the test instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Deterministic clean slate: clear any persisted mode + journal files.
        RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L)).reset()
        listOf(
            "restore_journal.json",
            "restore_journal_last_failure.json",
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        logs.clear()
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
    }

    private fun newCoordinator(
        mode: RestoreMaintenanceMode,
        journal: RestoreJournal,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider =
            com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L),
        resumeScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ): AppStartupCoordinator =
        AppStartupCoordinator(
            appContext = context,
            backgroundLifecycleObserver = mockk(relaxed = true),
            syncProactiveBriefingWorkUseCase = mockk(relaxed = true),
            restoreJournal = journal,
            restoreMaintenanceMode = mode,
            restoreDatabaseOpener = mockk<RestoreDatabaseOpener>(relaxed = true),
            restoreInternalWriteScope = com.yourname.expensetracker.data.backup.RestoreInternalWriteScope(mode),
            workerExecutionGuard = mockk(relaxed = true),
            restoreJournalImporter = mockk(relaxed = true),
            intakeRecoveryScheduler = mockk(relaxed = true),
            bankSyncStartupRecovery = mockk<BankSyncStartupRecovery>(relaxed = true),
            timeProvider = timeProvider,
            applicationScope = resumeScope
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

    // ── P7-002: ASSETS_RESTORING startup recovery ─────────────────

    /** Creates a minimal valid SQLite file (passes PRAGMA integrity/FK checks). */
    private fun createValidSqliteFile(file: File) {
        file.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS recovery_probe (id INTEGER PRIMARY KEY, note TEXT)")
        } finally {
            db.close()
        }
    }

    /** Builds an ASSETS_RESTORING journal with [tasks] and returns the written entry. */
    private fun writeAssetsRestoringJournal(
        journal: RestoreJournal,
        tasks: List<RestoreJournal.AssetRestoreTask>,
        extractTempDirPath: String?,
        liveDbFile: File,
        safetyBackupPath: String? = null
    ): RestoreJournal.JournalEntry {
        var entry = journal.beginJournal(
            sourceBackupPath = File(context.cacheDir, "src.costbackup").absolutePath,
            stagedDbPath = context.getDatabasePath("staged_restore.db").absolutePath,
            liveDbPath = liveDbFile.absolutePath
        )
        entry = entry.copy(
            assetTasks = tasks,
            extractTempDirPath = extractTempDirPath
        )
        journal.writeJournal(entry)
        return journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING, safetyBackupPath = safetyBackupPath)
    }

    @Test
    fun `ASSETS_RESTORING journal with only completed tasks commits journal and requires restart`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.COMPLETED,
                    targetPath = "restored_5.jpg"
                )
            ),
            extractTempDirPath = File(context.cacheDir, "extract_missing_dir").absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Recovered restore must land in the resettable restart-required mode",
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )
        assertFalse("Writes must stay blocked until the forced restart", mode.isWritesAllowed())
        assertFalse("Journal must be consumed so the next startup can reset the mode", journal.hasJournal())
        assertNotNull(
            "Success journal must preserve the operation trail",
            journal.readSuccessJournal()
        )
    }

    @Test
    fun `ASSETS_RESTORING journal resumes pending asset task with deterministic target name`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)

        val extractDir = File(context.cacheDir, "extract_test_resume")
        val sourceFile = File(extractDir, "receipts/5_photo.jpg")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    // RP-03 fix: the journal name must equal the identity-derived name
                    // (restored_{receiptId}.{allowlisted ext}) - it is a cross-check now.
                    targetPath = File(File(context.filesDir, "receipts"), "restored_5.jpg").absolutePath
                )
            ),
            extractTempDirPath = extractDir.absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )
        val finalFile = File(File(context.filesDir, "receipts"), "restored_5.jpg")
        assertTrue("Resumed asset must be restored under the identity-derived target name", finalFile.exists())
        assertTrue("Resumed asset must keep its bytes", finalFile.length() == 5L)
        assertFalse("Temp copy must be gone after the durable rename", File(File(context.filesDir, "receipts"), "restored_5.jpg.tmp").exists())
        assertFalse(journal.hasJournal())
        assertEquals(
            "Resumed task must be recorded COMPLETED in the success journal",
            RestoreJournal.AssetRestoreStatus.COMPLETED,
            journal.readSuccessJournal()!!.assetTasks.first().status
        )
        // No temp residue in the extraction workspace is required — sources may stay
        // (they are cache files owned by the operation), but the journal is consumed.
    }

    // -- RP-03 fix: startup asset-resume hardening -----------------

    @Test
    fun `ASSETS_RESTORING resume does not block the startup thread`() = runTest {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "restored_5.jpg"
                )
            ),
            extractTempDirPath = null,
            liveDbFile = liveDbFile
        )

        // Deferred dispatcher: nothing runs until the scheduler is advanced.
        val deferredScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        newCoordinator(mode, journal, resumeScope = deferredScope).checkRestoreJournal()

        // The startup call returned WITHOUT running the resume: the fail-closed
        // maintenance-mode decision is already in place and the journal is intact.
        assertEquals(
            "Write barrier must be armed synchronously before the async resume",
            RestoreMaintenanceMode.Mode.ASSETS_RESTORING,
            mode.currentMode()
        )
        assertTrue("Journal must still be present while the resume is pending", journal.hasJournal())

        // Draining the dispatcher completes the idempotent resume and consumes the journal.
        testScheduler.advanceUntilIdle()
        assertEquals(
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )
        assertFalse(journal.hasJournal())
    }

    @Test
    fun `ASSETS_RESTORING resume derives name from task identity and rejects tampered target path`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        val extractDir = File(context.cacheDir, "extract_tampered_target")
        val sourceFile = File(extractDir, "receipts/5_photo.jpg")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeBytes(byteArrayOf(1, 2, 3))

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    // Tampered journal name - must never become the write target.
                    targetPath = "../../evil_hijack.jpg"
                )
            ),
            extractTempDirPath = extractDir.absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        val successEntry = journal.readSuccessJournal()!!
        assertEquals(
            "Tampered target name must fail the task closed",
            RestoreJournal.AssetRestoreStatus.FAILED,
            successEntry.assetTasks.first().status
        )
        assertEquals(
            RestoreJournal.ASSET_REASON_INVALID_TARGET,
            successEntry.assetTasks.first().error
        )
        val receiptsDir = File(context.filesDir, "receipts")
        assertTrue(
            "No file may be written when the journal name mismatches the derived name",
            receiptsDir.listFiles().isNullOrEmpty()
        )
    }

    @Test
    fun `ASSETS_RESTORING resume rejects non-allowlisted source extension`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        val extractDir = File(context.cacheDir, "extract_bad_ext")
        val sourceFile = File(extractDir, "receipts/5_payload.exe")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeBytes(byteArrayOf(0, 0x4d, 0x5a))

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_payload.exe",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "restored_5.exe"
                )
            ),
            extractTempDirPath = extractDir.absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        val successEntry = journal.readSuccessJournal()!!
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, successEntry.assetTasks.first().status)
        assertEquals(RestoreJournal.ASSET_REASON_INVALID_TARGET, successEntry.assetTasks.first().error)
        assertFalse("Non-allowlisted extension must not produce a final file", File(File(context.filesDir, "receipts"), "restored_5.exe").exists())
    }

    @Test
    fun `ASSETS_RESTORING resume never overwrites an existing final file on collision`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        val extractDir = File(context.cacheDir, "extract_collision")
        val sourceFile = File(extractDir, "receipts/5_photo.jpg")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeBytes(byteArrayOf(1, 1, 1))

        // Foreign file already owns the deterministic final name.
        val foreignBytes = byteArrayOf(9, 9, 9, 9)
        val finalFile = File(File(context.filesDir, "receipts"), "restored_5.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(foreignBytes)
        }

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "restored_5.jpg"
                )
            ),
            extractTempDirPath = extractDir.absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        val successEntry = journal.readSuccessJournal()!!
        assertEquals(
            "Existing final file must cause a fail-closed collision failure",
            RestoreJournal.AssetRestoreStatus.FAILED,
            successEntry.assetTasks.first().status
        )
        assertEquals(RestoreJournal.ASSET_REASON_TARGET_COLLISION, successEntry.assetTasks.first().error)
        assertTrue("Foreign file bytes must be untouched", finalFile.readBytes().contentEquals(foreignBytes))
        assertFalse("Temp copy must not linger after collision rejection", File(finalFile.parentFile, "restored_5.jpg.tmp").exists())
    }

    @Test
    fun `ASSETS_RESTORING resume fails duplicate receipt-id tasks closed`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        val extractDir = File(context.cacheDir, "extract_duplicate_ids")
        listOf("5_a.jpg", "5_b.jpg").forEach { name ->
            val f = File(extractDir, "receipts/$name")
            f.parentFile?.mkdirs()
            f.writeBytes(byteArrayOf(1))
        }

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_a.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "restored_5.jpg"
                ),
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_b.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "restored_5.jpg"
                )
            ),
            extractTempDirPath = extractDir.absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        val successEntry = journal.readSuccessJournal()!!
        assertEquals(
            "ALL duplicate receipt-id tasks must fail (no first-wins)",
            listOf(RestoreJournal.AssetRestoreStatus.FAILED, RestoreJournal.AssetRestoreStatus.FAILED),
            successEntry.assetTasks.map { it.status }
        )
        assertTrue(
            "No task may carry a different reason",
            successEntry.assetTasks.all { it.error == RestoreJournal.ASSET_REASON_DUPLICATE_TASK }
        )
        assertFalse("No final file may be created for duplicate tasks", File(File(context.filesDir, "receipts"), "restored_5.jpg").exists())
    }

    @Test
    fun `ASSETS_RESTORING journal with missing asset sources marks FAILED and never rolls back verified DB`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        createValidSqliteFile(liveDbFile)
        val liveDbBytesBefore = liveDbFile.readBytes()

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 7L,
                    sourceRelativePath = "7_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING
                )
            ),
            extractTempDirPath = File(context.cacheDir, "extract_dir_that_is_gone").absolutePath,
            liveDbFile = liveDbFile
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Best-effort image loss must NOT roll back a verified DB into critical lock",
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )
        assertFalse(journal.hasJournal())
        val successEntry = journal.readSuccessJournal()
        assertNotNull(successEntry)
        assertEquals(
            "Missing source must be recorded FAILED(SOURCE_MISSING)",
            "SOURCE_MISSING",
            successEntry!!.assetTasks.first().error
        )
        assertEquals(
            RestoreJournal.AssetRestoreStatus.FAILED,
            successEntry.assetTasks.first().status
        )
        assertTrue("Verified DB bytes must be untouched", liveDbFile.readBytes().contentEquals(liveDbBytesBefore))
    }

    @Test
    fun `ASSETS_RESTORING journal with corrupt swapped DB rolls back from safety backup to NORMAL`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        liveDbFile.parentFile?.mkdirs()
        liveDbFile.writeBytes("this is definitely not a sqlite database".toByteArray())
        val safetyBackupFile = File(context.filesDir, "safety_backup_ok.db")
        createValidSqliteFile(safetyBackupFile)

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = emptyList(),
            extractTempDirPath = null,
            liveDbFile = liveDbFile,
            safetyBackupPath = safetyBackupFile.absolutePath
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Successful rollback in a fresh process resets to NORMAL (same as swap-crash recovery)",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertTrue(mode.isWritesAllowed())
        assertFalse(journal.hasJournal())
        assertTrue("Live DB must be a valid SQLite file after rollback", liveDbFile.exists() && liveDbFile.length() > 0)
    }

    @Test
    fun `ASSETS_RESTORING journal without safety backup consumes verified pre_restore snapshot`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        liveDbFile.parentFile?.mkdirs()
        liveDbFile.writeBytes("corrupt swapped db".toByteArray())
        val preRestoreFile = File(context.filesDir, "expense_tracker_db.pre_restore")
        // Create the valid snapshot under a plain name first — the legacy Robolectric
        // SQLite driver cannot open WAL against a double-extension filename.
        val seedFile = File(context.filesDir, "pre_restore_seed.db")
        createValidSqliteFile(seedFile)
        assertTrue("seed rename into .pre_restore position failed", seedFile.renameTo(preRestoreFile))

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = emptyList(),
            extractTempDirPath = null,
            liveDbFile = liveDbFile,
            safetyBackupPath = null
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Verified .pre_restore snapshot (P7-004 recovery hook) must rescue the app",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertFalse("pre_restore snapshot must be consumed and cleaned after successful recovery", preRestoreFile.exists())
        assertFalse(journal.hasJournal())
    }

    // -- RP-03 fix: .pre_restore residue after safety-backup recovery --

    @Test
    fun `safety backup recovery removes stale pre_restore residue`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        liveDbFile.parentFile?.mkdirs()
        liveDbFile.writeBytes("corrupt swapped db".toByteArray())
        val safetyBackupFile = File(context.filesDir, "safety_backup_wins.db")
        createValidSqliteFile(safetyBackupFile)
        // Stale .pre_restore from an older generation - must not linger forever once
        // the journal-recorded safety backup has been consumed successfully.
        val stalePreRestore = File(context.filesDir, "expense_tracker_db.pre_restore").apply {
            writeBytes("stale older snapshot".toByteArray())
        }

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = emptyList(),
            extractTempDirPath = null,
            liveDbFile = liveDbFile,
            safetyBackupPath = safetyBackupFile.absolutePath
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Recovery must consume the safety backup and reset to NORMAL",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertFalse(
            "Stale .pre_restore must be deleted after successful safety-backup recovery",
            stalePreRestore.exists()
        )
        assertFalse(journal.hasJournal())
    }

    @Test
    fun `failed safety recovery keeps pre_restore as the last recovery source`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val liveDbFile = File(context.filesDir, "expense_tracker_db")
        liveDbFile.parentFile?.mkdirs()
        liveDbFile.writeBytes("corrupt swapped db".toByteArray())
        // No usable safety backup and an unusable .pre_restore - both candidates fail
        // verification, so recovery must fail closed and keep every source on disk.
        val stalePreRestore = File(context.filesDir, "expense_tracker_db.pre_restore").apply {
            writeBytes("not a sqlite file either".toByteArray())
        }

        writeAssetsRestoringJournal(
            journal = journal,
            tasks = emptyList(),
            extractTempDirPath = null,
            liveDbFile = liveDbFile,
            safetyBackupPath = File(context.filesDir, "missing_safety_backup.db").absolutePath
        )

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Unusable recovery sources must fail closed into the critical mode",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode.currentMode()
        )
        assertTrue(
            ".pre_restore must be kept on unsafe outcomes (last recovery source)",
            stalePreRestore.exists()
        )
    }

    @Test
    fun `failed crash recovery enters CRITICAL_RECOVERY_REQUIRED and blocks writes`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
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
    fun `startup recovery failure retains only bounded journal error and logs no throwable`() {
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        writeUnrecoverableSwapJournal(journal)

        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals("UNKNOWN_ERROR", journal.readFailureJournal()?.error)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { it.first == null && !it.second.contains("missing_safety_backup.db") })
    }

    @Test
    fun `CRITICAL_RECOVERY_REQUIRED survives the next restart with writes still blocked`() {
        // ── First startup: recovery fails, enters critical mode, renames journal away ──
        run {
            val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
            val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
            writeUnrecoverableSwapJournal(journal)
            newCoordinator(mode, journal).checkRestoreJournal()
            assertEquals(
                RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                mode.currentMode()
            )
        }

        // ── Second startup (process restart): fresh instances read persisted prefs/files ──
        val mode2 = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        val journal2 = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
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
        val mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        mode.exit(forceRestartRequired = true)
        assertEquals(
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )

        // Fresh startup, no journal on disk.
        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        assertFalse(journal.hasJournal())
        newCoordinator(mode, journal).checkRestoreJournal()

        assertEquals(
            "Restart-required success mode should reset to NORMAL after restart",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertTrue("Writes should resume after a successful restore + restart", mode.isWritesAllowed())
    }

    /**
     * T3A / G-TIME-01: The startup stale-run recovery cutoff must be derived from
     * the injected [com.yourname.expensetracker.domain.util.TimeProvider], never the
     * wall clock. Exercised via the [AppStartupCoordinator.startupStaleThresholdMs]
     * seam so the computation is asserted deterministically without bootstrapping
     * [ProcessLifecycleOwner] / the full initialize path.
     */
    @Test
    fun `stale threshold is computed from injected time provider not wall clock`() {
        val fixedTime = 1_900_000_000_000L
        val fakeTime = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val coordinator = newCoordinator(
            mode = RestoreMaintenanceMode(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L)),
            journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L)),
            timeProvider = fakeTime
        )

        val expectedCutoff = fixedTime - 15 * 60 * 1000L
        assertEquals(
            "Stale cutoff must be computed from the fixed provider time",
            expectedCutoff, coordinator.startupStaleThresholdMs()
        )

        // Deterministic time progression: moving the provider moves the cutoff.
        fakeTime.advanceTime(60_000L)
        assertEquals(
            "Stale cutoff must track the injected provider, not the wall clock",
            fixedTime + 60_000L - 15 * 60 * 1000L,
            coordinator.startupStaleThresholdMs()
        )
    }
}
