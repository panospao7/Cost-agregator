package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertTrue("appended event must persist across transition", events.any { it.stage == "MAINTENANCE_ENTERED" })
    }
}
