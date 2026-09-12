package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P7-CURRENT-016 — restore/reset diagnostics ledger import.
 *
 * The restore/reset path bans Room after the DB swap (P7-CURRENT-005), so terminal
 * FAILURE outcomes live only in the on-disk failure journal until ingested into the
 * queryable [OperationRun]/[OperationRunEvent] ledger on the next healthy startup.
 *
 * Uses a real [RestoreJournal] (Robolectric filesDir) so the on-disk journal lifecycle is
 * genuine; the ledger DAOs are mocked to assert exactly what gets persisted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreJournalImporterFailureTest {

    private lateinit var journal: RestoreJournal
    private val operationRunDao = mockk<OperationRunDao>()
    private val operationRunEventDao = mockk<OperationRunEventDao>()
    private val timeProvider = mockk<TimeProvider>().also {
        coEvery { it.now() } returns 1_700_000_000_000L
    }
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var importer: RestoreJournalImporter

    private fun writeBarrier(mode: RestoreMaintenanceMode.Mode): DatabaseWriteBarrier {
        // GR-14u45b: the DatabaseBarrierTest construction pattern — real
        // barrier over a mode-stubbed RestoreMaintenanceMode.
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns
            (mode == RestoreMaintenanceMode.Mode.NORMAL)
        return barrier
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        importer = RestoreJournalImporter(
            journal,
            operationRunDao,
            operationRunEventDao,
            timeProvider,
            writeBarrier(RestoreMaintenanceMode.Mode.NORMAL)
        )
    }

    /** Writes a terminal failure journal (active journal renamed to the failure file). */
    private fun writeFailureJournal(error: String): String {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/s.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "BUNDLE_VALIDATED",
            outcome = "FAILED_FINAL",
            severity = "ERROR",
            reasonCode = "VALIDATION_FAILED",
            isTerminal = true
        )
        journal.failJournal(entry, error)
        return entry.operationCorrelationId
    }

    @Test
    fun `failure journal is imported into the OperationRun ledger`() = runTest {
        val cid = writeFailureJournal("Incorrect password")

        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        val runSlot = slot<OperationRun>()
        coEvery { operationRunDao.insert(capture(runSlot)) } returns 42L
        coEvery { operationRunEventDao.getByRunId(42L) } returns emptyList()
        val eventSlot = slot<OperationRunEvent>()
        coEvery { operationRunEventDao.insert(capture(eventSlot)) } returns 1L

        importer.importLastFailureJournalIfPresent()

        // Run row reflects the failure.
        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        assertEquals("FAILED_FINAL", runSlot.captured.status)
        assertEquals(cid, runSlot.captured.correlationId)
        assertEquals("Incorrect password", runSlot.captured.errorSummary)

        // The terminal event is persisted.
        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
        assertEquals("BUNDLE_VALIDATED", eventSlot.captured.stage)
        assertEquals("FAILED_FINAL", eventSlot.captured.outcome)
        assertEquals(cid, eventSlot.captured.correlationId)

        // Journal is marked imported.
        org.junit.Assert.assertTrue(journal.isFailureJournalImported(cid))
    }

    @Test
    fun `failure import is idempotent across restarts`() = runTest {
        val cid = writeFailureJournal("Verification failed")

        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 7L
        coEvery { operationRunEventDao.getByRunId(7L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L

        importer.importLastFailureJournalIfPresent()
        // Second startup: journal now marked imported → must short-circuit.
        importer.importLastFailureJournalIfPresent()

        // Only the first call inserts.
        coVerify(exactly = 1) { operationRunDao.insert(any()) }
    }

    @Test
    fun `no failure journal is a no-op`() = runTest {
        importer.importLastFailureJournalIfPresent()
        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
    }

    // ── GR-14u45b write-barrier gating ────────────────────────────────────────

    @Test
    fun `blocked mode skips the success-journal import without any write`() = runTest {
        // The write barrier must gate the import entry BEFORE any
        // write-capable call: zero DAO inserts and zero journal marking.
        val cid = writeSuccessJournal()
        val blockedImporter = RestoreJournalImporter(
            journal,
            operationRunDao,
            operationRunEventDao,
            timeProvider,
            writeBarrier(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        )

        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        // The journal stays unmarked so a later healthy startup retries.
        org.junit.Assert.assertFalse(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `blocked mode skips the failure-journal import without any write`() = runTest {
        val cid = writeFailureJournal("Incorrect password")
        val blockedImporter = RestoreJournalImporter(
            journal,
            operationRunDao,
            operationRunEventDao,
            timeProvider,
            writeBarrier(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        )

        blockedImporter.importLastFailureJournalIfPresent()

        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        org.junit.Assert.assertFalse(journal.isFailureJournalImported(cid))
    }

    @Test
    fun `cancellation from the barrier propagates`() = runTest {
        val cancellingBarrier = mockk<DatabaseWriteBarrier>()
        coEvery { cancellingBarrier.checkWritesAllowed(any<String>()) } throws
            CancellationException("cancelled")
        val cancellingImporter = RestoreJournalImporter(
            journal,
            operationRunDao,
            operationRunEventDao,
            timeProvider,
            cancellingBarrier
        )

        val thrown = runCatching {
            cancellingImporter.importLastSuccessJournalIfPresent()
        }.exceptionOrNull()

        assertEquals(true, thrown is CancellationException)
        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
    }

    /** Writes a terminal success journal (active journal renamed to the success file). */
    private fun writeSuccessJournal(): String {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/s.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "RESTORE_VERIFIED",
            outcome = "SUCCESS",
            severity = "INFO",
            reasonCode = null,
            isTerminal = true
        )
        journal.commitJournal(entry)
        return entry.operationCorrelationId
    }
}
