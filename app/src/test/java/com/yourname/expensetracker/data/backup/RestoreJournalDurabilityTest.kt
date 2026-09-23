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

    @Test
    fun `fsync failure is surfaced and does not advance the journal`() {
        journal.testWriteTextSynced = { _, _ -> throw java.io.SyncFailedException("injected") }
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        }
        assertTrue("failed write must not publish an active journal", !journal.hasJournal())
    }

    @Test
    fun `rename false uses a checked durable fallback`() {
        journal.testRenameTo = { _, _ -> false }
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        assertEquals(entry, journal.readJournal())
    }

    @Test
    fun `fallback copy or fsync failure is surfaced and active journal is retained`() {
        journal.testRenameTo = { _, _ -> false }
        journal.testWriteTextSynced = { file, _ ->
            if (file.name == "restore_journal.json") throw java.io.SyncFailedException("injected")
        }
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        }
        assertTrue("fallback failure must leave recovery evidence", journal.hasJournal())
    }

    @Test
    fun `success journal preservation failure retains active journal`() {
        var preserve = false
        journal.testWriteTextSynced = { file, _ ->
            if (preserve && file.name == RestoreJournal.SUCCESS_JOURNAL_FILENAME + ".tmp") {
                throw java.io.SyncFailedException("injected")
            }
        }
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        preserve = true
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.commitJournal(entry)
        }
        assertTrue(journal.hasJournal())
    }

    @Test
    fun `absent journal is no action but corrupt bytes are critical`() {
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.NoAction)
        val active = File(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "restore_journal.json"
        )
        active.writeText("   ")
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
        assertTrue("corrupt bytes must remain for recovery", active.exists())
    }

    @Test
    fun `unknown state and malformed asset task are critical`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = File(context.filesDir, "restore_journal.json")
        val base = """{"operationId":"op","operationCorrelationId":"corr","state":"BOGUS","liveDbName":"live","_liveDbPath":"/data/live.db"}"""
        active.writeText(base)
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
        active.writeText("""{"operationId":"op","operationCorrelationId":"corr","state":"PREPARING","_liveDbPath":"/data/live.db","assetTasks":[{"receiptId":1}]}""")
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
    }

    @Test
    fun `valid recovery states remain classified by state`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = File(context.filesDir, "restore_journal.json")
        val states = listOf(
            RestoreJournal.JournalState.PREPARING to RestoreJournal.RecoveryResult.CleanedNonDestructive::class,
            RestoreJournal.JournalState.STAGED to RestoreJournal.RecoveryResult.CleanedNonDestructive::class,
            RestoreJournal.JournalState.SWAPPING to RestoreJournal.RecoveryResult.RecoveredFromSwap::class,
            RestoreJournal.JournalState.VERIFYING to RestoreJournal.RecoveryResult.RecoveredFromSwap::class,
            RestoreJournal.JournalState.ROLLING_BACK to RestoreJournal.RecoveryResult.RecoveredFromSwap::class
        )
        states.forEach { (state, expected) ->
            val entry = journal.beginJournal("/src", "/staged", "/live")
            journal.writeJournal(entry.copy(state = state))
            assertTrue("$state should classify as ${expected.simpleName}", expected.isInstance(journal.checkAndRecover()))
            if (state == RestoreJournal.JournalState.SWAPPING ||
                state == RestoreJournal.JournalState.VERIFYING ||
                state == RestoreJournal.JournalState.ROLLING_BACK
            ) active.delete()
        }
    }
}
