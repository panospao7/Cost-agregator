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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    private lateinit var context: android.content.Context
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
        context = ApplicationProvider.getApplicationContext()
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
        assertEquals("UNKNOWN_ERROR", runSlot.captured.errorSummary)

        // The terminal event is persisted.
        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
        assertEquals("BUNDLE_VALIDATED", eventSlot.captured.stage)
        assertEquals("FAILED_FINAL", eventSlot.captured.outcome)
        assertEquals(cid, eventSlot.captured.correlationId)

        // Journal is marked imported.
        assertTrue(journal.isFailureJournalImported(cid))
    }

    @Test
    fun `legacy free text imports as controlled code and remains idempotent`() = runTest {
        val cid = writeFailureJournal("VALIDATION_FAILED")
        val failureFile = File(context.filesDir, RestoreJournal.FAILURE_JOURNAL_FILENAME)
        val hostile = "SELECT * FROM receipts /data/private/ledger.db content://receipts/4 Merchant 123.45"
        failureFile.writeText(JSONObject(failureFile.readText()).put("error", hostile).toString())

        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        val runSlot = slot<OperationRun>()
        coEvery { operationRunDao.insert(capture(runSlot)) } returns 42L
        coEvery { operationRunEventDao.getByRunId(42L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L

        importer.importLastFailureJournalIfPresent()
        importer.importLastFailureJournalIfPresent()

        assertEquals("UNKNOWN_ERROR", runSlot.captured.errorSummary)
        assertEquals("/data/staged.db", journal.readFailureJournal()?.stagedDbPath)
        assertTrue(journal.isFailureJournalImported(cid))
        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
    }

    @Test
    fun `known failure code survives import and absent legacy error becomes unknown`() = runTest {
        val cid = writeFailureJournal("WRITE_BARRIER_DENIED")
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        val runSlot = slot<OperationRun>()
        coEvery { operationRunDao.insert(capture(runSlot)) } returns 42L
        coEvery { operationRunEventDao.getByRunId(42L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L

        importer.importLastFailureJournalIfPresent()
        assertEquals("WRITE_BARRIER_DENIED", runSlot.captured.errorSummary)

        val newCid = writeFailureJournal("TIMEOUT")
        val failureFile = File(context.filesDir, RestoreJournal.FAILURE_JOURNAL_FILENAME)
        failureFile.writeText(JSONObject(failureFile.readText()).apply { remove("error") }.toString())
        coEvery { operationRunDao.getByCorrelationId(newCid) } returns null

        importer.importLastFailureJournalIfPresent()
        assertEquals("UNKNOWN_ERROR", runSlot.captured.errorSummary)
    }

    @Test
    fun `partial event import retries only the missing event`() = runTest {
        val cid = writeFailureJournal("PARSER_FAILED")
        journal.appendEventToFailureJournal(cid, "ROLLBACK", "FAILED_FINAL", reasonCode = "UNKNOWN_ERROR")
        var savedRun: OperationRun? = null
        val savedEvents = mutableListOf<OperationRunEvent>()
        var attempts = 0
        coEvery { operationRunDao.getByCorrelationId(cid) } answers { savedRun }
        coEvery { operationRunDao.insert(any()) } answers {
            savedRun = firstArg<OperationRun>().copy(id = 42L)
            42L
        }
        coEvery { operationRunEventDao.getByRunId(42L) } answers { savedEvents.toList() }
        coEvery { operationRunEventDao.insert(any()) } answers {
            attempts++
            if (attempts == 2) throw IllegalStateException("SQL /data/private/ledger.db")
            savedEvents += firstArg<OperationRunEvent>()
            1L
        }

        importer.importLastFailureJournalIfPresent()
        assertFalse(journal.isFailureJournalImported(cid))
        assertEquals(1, savedEvents.size)

        importer.importLastFailureJournalIfPresent()
        assertTrue(journal.isFailureJournalImported(cid))
        assertEquals(2, savedEvents.size)
        assertEquals(2, savedEvents.map { it.eventId }.distinct().size)
        assertEquals("PARSER_FAILED", savedRun?.errorSummary)
        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        coVerify(exactly = 3) { operationRunEventDao.insert(any()) }
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

    @Test
    fun `failure import cancellation from barrier or run insert never marks journal`() = runTest {
        val cid = writeFailureJournal("UNKNOWN_ERROR")
        val cancellation = CancellationException("sensitive receipt details")
        val cancellingBarrier = mockk<DatabaseWriteBarrier>()
        coEvery { cancellingBarrier.checkWritesAllowed(any<String>()) } throws cancellation
        val cancellingImporter = RestoreJournalImporter(
            journal, operationRunDao, operationRunEventDao, timeProvider, cancellingBarrier
        )

        assertSame(cancellation, runCatching { cancellingImporter.importLastFailureJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isFailureJournalImported(cid))
        coVerify(exactly = 0) { operationRunDao.insert(any()) }

        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } throws cancellation
        assertSame(cancellation, runCatching { importer.importLastFailureJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isFailureJournalImported(cid))
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
    }

    @Test
    fun `cancellation from the DAO insert propagates and leaves the journal unmarked`() = runTest {
        // GR-14u46b: runCatching traps CancellationException into
        // Result.failure — without the onFailure rethrow the for-loop
        // continued, every subsequent insert was likewise swallowed, and
        // the outer catch's rethrow never saw the cancellation.  The
        // exception must surface out of BOTH import functions and the
        // journal must stay unmarked (retry-on-next-startup property).
        val successCid = writeSuccessJournal()
        coEvery { operationRunDao.getByCorrelationId(successCid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 11L
        coEvery { operationRunEventDao.getByRunId(11L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } throws
            CancellationException("cancelled")

        val successThrown = runCatching {
            importer.importLastSuccessJournalIfPresent()
        }.exceptionOrNull()

        assertEquals(true, successThrown is CancellationException)
        org.junit.Assert.assertFalse(journal.isSuccessJournalImported(successCid))

        // Fresh journal for the failure path (the success journal file is
        // untouched by the failure import, but reset mocks for clarity).
        val failureCid = writeFailureJournal("Verification failed")
        coEvery { operationRunDao.getByCorrelationId(failureCid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 12L
        coEvery { operationRunEventDao.getByRunId(12L) } returns emptyList()

        val failureThrown = runCatching {
            importer.importLastFailureJournalIfPresent()
        }.exceptionOrNull()

        assertEquals(true, failureThrown is CancellationException)
        org.junit.Assert.assertFalse(journal.isFailureJournalImported(failureCid))
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
