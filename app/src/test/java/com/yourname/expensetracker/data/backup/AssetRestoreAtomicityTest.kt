package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P7-P1-04 — Asset restore journal infrastructure atomicity tests.
 *
 * Verifies:
 * - [RestoreJournal.JournalEntry.extractTempDirPath] round-trips through JSON
 * - [RestoreJournal.checkAndRecover] returns [RestoreJournal.RecoveryResult.AssetsIncomplete]
 *   for ASSETS_RESTORING state (DB already swapped — journal must be preserved)
 * - Pre-populated PENDING [RestoreJournal.AssetRestoreTask] items persist correctly
 *   in the journal
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssetRestoreAtomicityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var journal: RestoreJournal

    /** Deterministic epoch-millis injected via FakeTimeProvider for all fromJson calls. */
    private val fixedTime = 1716163200000L // 2024-05-20 00:00 UTC

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clean slate — remove any journal files from prior tests.
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime))
    }

    // ── A) extractTempDirPath round-trip ─────────────────────────────

    @Test
    fun `extractTempDirPath_roundtrips_in_journal_json`() {
        val originalPath = "/cache/costbackup_extract_1718000000000"
        val entry = RestoreJournal.JournalEntry(
            extractTempDirPath = originalPath
        )

        // Serialize
        val json = entry.toJson()
        assertTrue("_extractTempDirPath key must exist in JSON", json.has("_extractTempDirPath"))
        assertEquals(
            "extractTempDirPath must serialize correctly",
            originalPath,
            json.getString("_extractTempDirPath")
        )

        // Deserialize
        val restored = RestoreJournal.JournalEntry.fromJson(json, fixedTime)
        assertEquals(
            "extractTempDirPath must round-trip through JSON",
            originalPath,
            restored.extractTempDirPath
        )
    }

    @Test
    fun `extractTempDirPath_defaults_to_null`() {
        val entry = RestoreJournal.JournalEntry()
        val json = entry.toJson()
        assertTrue(
            "_extractTempDirPath must be JSON NULL when not set",
            json.isNull("_extractTempDirPath")
        )

        val restored = RestoreJournal.JournalEntry.fromJson(json, fixedTime)
        assertEquals(
            "extractTempDirPath must be null by default",
            null,
            restored.extractTempDirPath
        )
    }

    @Test
    fun `extractTempDirPath_does_not_leak_to_diagnostics_json`() {
        val entry = RestoreJournal.JournalEntry(
            extractTempDirPath = "/secret/temp/dir"
        )
        val diag = entry.toDiagnosticsJson()
        assertTrue(
            "_extractTempDirPath must be stripped from diagnostics JSON",
            !diag.has("_extractTempDirPath")
        )
    }

    // ── B) checkAndRecover ASSETS_RESTORING returns AssetsIncomplete ──

    @Test
    fun `checkAndRecover_ASSETS_RESTORING_returns_AssetsIncomplete`() {
        // Create a journal entry with ASSETS_RESTORING state and write to disk
        val sourcePath = tmp.newFile("source.costbackup").absolutePath
        val stagedPath = tmp.newFile("staged.db").absolutePath
        val livePath = tmp.newFile("live.db").absolutePath
        val tempExtractPath = tmp.newFolder("extracted").absolutePath

        val entry = RestoreJournal.JournalEntry(
            state = RestoreJournal.JournalState.ASSETS_RESTORING,
            sourceBackupPath = sourcePath,
            stagedDbPath = stagedPath,
            liveDbPath = livePath,
            extractTempDirPath = tempExtractPath
        )
        journal.writeJournal(entry)

        // checkAndRecover must return AssetsIncomplete (NOT CleanedNonDestructive)
        val result = journal.checkAndRecover()
        assertTrue(
            "ASSETS_RESTORING must yield AssetsIncomplete, not CleanedNonDestructive. Got: ${result.javaClass.simpleName}",
            result is RestoreJournal.RecoveryResult.AssetsIncomplete
        )

        // The journal file must NOT have been deleted (DB was swapped, needs recovery)
        assertTrue(
            "Journal must NOT be deleted for ASSETS_RESTORING state",
            journal.hasJournal()
        )

        // The entry in AssetsIncomplete must carry the extractTempDirPath
        val assetsIncomplete = result as RestoreJournal.RecoveryResult.AssetsIncomplete
        assertEquals(
            "AssetsIncomplete entry must preserve extractTempDirPath",
            tempExtractPath,
            assetsIncomplete.entry.extractTempDirPath
        )
    }

    @Test
    fun `checkAndRecover_SAFETY_BACKUP_CREATED_still_returns_CleanedNonDestructive`() {
        // Regression guard: SAFETY_BACKUP_CREATED is non-destructive and should
        // still return CleanedNonDestructive (only ASSETS_RESTORING changed).
        val entry = RestoreJournal.JournalEntry(
            state = RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
            sourceBackupPath = "/tmp/src.costbackup",
            stagedDbPath = "/tmp/staged.db",
            liveDbPath = "/tmp/live.db"
        )
        journal.writeJournal(entry)

        val result = journal.checkAndRecover()
        assertTrue(
            "SAFETY_BACKUP_CREATED must still yield CleanedNonDestructive",
            result is RestoreJournal.RecoveryResult.CleanedNonDestructive
        )
    }

    // ── C) Pre-populated PENDING tasks ───────────────────────────────

    @Test
    fun `asset_tasks_prepopulated_as_PENDING`() {
        // Verify that AssetRestoreTask with PENDING status can be stored in
        // a JournalEntry and round-trips correctly through JSON serialization.
        val tasks = listOf(
            RestoreJournal.AssetRestoreTask(
                receiptId = 101L,
                sourceRelativePath = "receipts/101_receipt.jpg",
                status = RestoreJournal.AssetRestoreStatus.PENDING
            ),
            RestoreJournal.AssetRestoreTask(
                receiptId = 202L,
                sourceRelativePath = "receipts/202_receipt.png",
                status = RestoreJournal.AssetRestoreStatus.PENDING
            ),
            RestoreJournal.AssetRestoreTask(
                receiptId = 303L,
                sourceRelativePath = "receipts/303_receipt.pdf",
                status = RestoreJournal.AssetRestoreStatus.PENDING
            )
        )

        val entry = RestoreJournal.JournalEntry(assetTasks = tasks)
        journal.writeJournal(entry)

        // Read back from disk
        val readBack = journal.readJournal()
        assertNotNull("Journal with PENDING tasks must be readable", readBack)
        assertEquals(
            "All three PENDING tasks must survive write+read",
            3,
            readBack!!.assetTasks.size
        )

        // Verify each task
        readBack.assetTasks.forEach { task ->
            assertEquals(
                "Task for receiptId=${task.receiptId} must have PENDING status",
                RestoreJournal.AssetRestoreStatus.PENDING,
                task.status
            )
            assertNotNull(
                "Task for receiptId=${task.receiptId} must have sourceRelativePath",
                task.sourceRelativePath
            )
        }

        // Verify specific IDs
        assertEquals(101L, readBack.assetTasks[0].receiptId)
        assertEquals(202L, readBack.assetTasks[1].receiptId)
        assertEquals(303L, readBack.assetTasks[2].receiptId)

        // Verify JSON round-trip directly
        val json = entry.toJson()
        val restored = RestoreJournal.JournalEntry.fromJson(json, fixedTime)
        assertEquals(3, restored.assetTasks.size)
        restored.assetTasks.forEach { task ->
            assertEquals(
                "All tasks must be PENDING in JSON round-trip",
                RestoreJournal.AssetRestoreStatus.PENDING,
                task.status
            )
        }
    }
}
