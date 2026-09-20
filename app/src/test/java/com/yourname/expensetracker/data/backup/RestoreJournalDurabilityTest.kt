package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P7-CURRENT-022 — journal write durability.
 *
 * The fsync added to the temp-file write before the atomic rename is a
 * non-observable durability improvement (cannot simulate power loss in a unit
 * test). These tests are the regression guard that the fsync'd write path still
 * produces a correct, fully-readable journal — in particular that the
 * safety-backup path survives a re-read, which is exactly the field crash
 * recovery depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreJournalDurabilityTest {

    private lateinit var journal: RestoreJournal

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clean slate.
        listOf(
            "restore_journal.json",
            "restore_journal_last_failure.json",
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
    }

    @Test
    fun `writeJournal then readJournal round-trips state and paths`() {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/src.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        val updated = journal.transitionTo(
            entry,
            RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
            safetyBackupPath = "/data/safety_backup.db"
        )

        val readBack = journal.readJournal()
        assertNotNull("journal must be readable after fsync'd write", readBack)
        assertEquals(RestoreJournal.JournalState.SAFETY_BACKUP_CREATED, readBack!!.state)
        assertEquals(
            "safety backup path must survive the write+reread (needed for crash recovery)",
            "/data/safety_backup.db",
            readBack.safetyBackupPath
        )
        assertEquals(updated.operationId, readBack.operationId)
    }

    @Test
    fun `appended events survive journal state transitions`() {
        val entry = journal.beginJournal("/cache/s.costbackup", "/data/staged.db", "/data/live.db")
        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "MAINTENANCE_ENTERED",
            outcome = "COMPLETED"
        )
        // A subsequent state write must preserve existing events.
        journal.transitionTo(entry, RestoreJournal.JournalState.STAGED)

        val events = journal.getEventsByCorrelationId(entry.operationCorrelationId)
        assertTrue("appended event must persist across transitions", events.any { it.stage == "MAINTENANCE_ENTERED" })
    }

    /**
     * RP-03B (P7-002 resume): the asset-task ledger must round-trip through the
     * fsync'd journal write so a crash mid-asset-loop can be resumed at startup,
     * and the persisted target must be a basename only (privacy: no absolute paths).
     */
    @Test
    fun `asset task ledger round-trips statuses and basename-only targets`() {
        var entry = journal.beginJournal("/cache/s.costbackup", "/data/staged.db", "/data/live.db")
        entry = entry.copy(
            extractTempDirPath = "/cache/costbackup_extract_x",
            assetTasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "/data/data/app/files/receipts/restored_5.jpg"
                ),
                RestoreJournal.AssetRestoreTask(
                    receiptId = 6L,
                    sourceRelativePath = "6_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.COMPLETED
                )
            )
        )
        journal.writeJournal(entry)
        journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING)

        val readBack = journal.readJournal()!!
        assertEquals(RestoreJournal.JournalState.ASSETS_RESTORING, readBack.state)
        assertEquals("/cache/costbackup_extract_x", readBack.extractTempDirPath)
        assertEquals(2, readBack.assetTasks.size)

        val pending = readBack.assetTasks.first { it.receiptId == 5L }
        assertEquals(RestoreJournal.AssetRestoreStatus.PENDING, pending.status)
        assertEquals("5_photo.jpg", pending.sourceRelativePath)
        assertEquals(
            "target must round-trip as a basename only (no absolute path in journal)",
            "restored_5.jpg",
            pending.targetPath
        )
        assertEquals(
            RestoreJournal.AssetRestoreStatus.COMPLETED,
            readBack.assetTasks.first { it.receiptId == 6L }.status
        )
    }

    /**
     * RP-03 fix: the final asset name must be derived from the task's own identity
     * (receiptId + allowlisted extension) - deterministic across retries - and must
     * reject any extension the backup write side cannot produce.
     */
    @Test
    fun `deriveAssetTargetName is identity-derived, deterministic, and extension-validated`() {
        // Deterministic per task identity - same task always yields the same name.
        assertEquals(
            "restored_5.jpg",
            RestoreJournal.deriveAssetTargetName(5L, "jpg")
        )
        assertEquals(
            "Retry with the same identity must derive the identical name",
            RestoreJournal.deriveAssetTargetName(5L, "jpg"),
            RestoreJournal.deriveAssetTargetName(5L, "jpg")
        )

        // Extension is normalized (case/whitespace) and legacy-tolerated types pass.
        assertEquals("restored_5.jpg", RestoreJournal.deriveAssetTargetName(5L, "JPG"))
        assertEquals("restored_5.jpg", RestoreJournal.deriveAssetTargetName(5L, " jpg "))
        assertEquals("restored_7.png", RestoreJournal.deriveAssetTargetName(7L, "png"))
        assertEquals("restored_7.webp", RestoreJournal.deriveAssetTargetName(7L, "webp"))
        assertEquals("restored_7.jpeg", RestoreJournal.deriveAssetTargetName(7L, "jpeg"))

        // Distinct identities never share a final name key.
        assertNotEquals(
            RestoreJournal.deriveAssetTargetName(5L, "jpg"),
            RestoreJournal.deriveAssetTargetName(6L, "jpg")
        )

        // Anything outside the fixed allowlist fails closed (null).
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "exe"))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "html"))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, ""))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "jpg.exe"))
    }
}
