package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
        // The real barrier crosses withContext(InFlightWriteElement); kotlinx.coroutines
        // stack-trace recovery may deliver a copy whose cause chain holds the original.
        val thrown = runCatching { importer.importLastFailureJournalIfPresent() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(generateSequence(thrown) { it.cause }.any { it === cancellation })
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

    // ── DIAG-03: legacy journal message text is never imported into the ledger ──

    @Test
    fun `legacy failure journal exception message text imports as null`() = runTest {
        val cid = writeFailureJournal("VALIDATION_FAILED")
        journal.appendEventToFailureJournal(
            correlationId = cid,
            stage = "ROLLBACK",
            outcome = "FAILED_FINAL",
            exceptionClass = "IllegalStateException",
            exceptionMessageSafe = "secret-456",
            isTerminal = true
        )
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 42L
        coEvery { operationRunEventDao.getByRunId(42L) } returns emptyList()
        val events = mutableListOf<OperationRunEvent>()
        coEvery { operationRunEventDao.insert(capture(events)) } returns 1L

        importer.importLastFailureJournalIfPresent()

        assertEquals(2, events.size)
        events.forEach { assertEquals(null, it.exceptionMessage) }
        assertTrue(events.none { it.toString().contains("secret-456") })
        assertEquals("IllegalStateException", events.single { it.stage == "ROLLBACK" }.exceptionClass)
    }

    @Test
    fun `legacy success journal exception message text imports as null`() = runTest {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/s.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "RESTORE_VERIFIED",
            outcome = "SUCCESS",
            exceptionClass = "IOException",
            exceptionMessageSafe = "secret-456",
            isTerminal = true
        )
        journal.commitJournal(entry)
        val cid = entry.operationCorrelationId
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 43L
        coEvery { operationRunEventDao.getByRunId(43L) } returns emptyList()
        val events = mutableListOf<OperationRunEvent>()
        coEvery { operationRunEventDao.insert(capture(events)) } returns 1L

        importer.importLastSuccessJournalIfPresent()

        assertEquals(1, events.size)
        assertEquals(null, events.single().exceptionMessage)
        assertFalse(events.single().toString().contains("secret-456"))
        assertEquals("IOException", events.single().exceptionClass)
    }

    // ── fix-1.0.4 follow-up: every journal insert is a counted write-barrier write ──

    private val successOp = "RestoreJournalImporter.importLastSuccessJournalIfPresent"
    private val failureOp = "RestoreJournalImporter.importLastFailureJournalIfPresent"

    /** Records every runWrite label and the in-flight depth observed by each DAO insert. */
    private class BarrierSpy {
        val ops = mutableListOf<DatabaseAccessOperation>()
        val insertDepths = mutableListOf<Int>()
        var depth = 0
    }

    private fun spyBarrier(spy: BarrierSpy): DatabaseWriteBarrier {
        val barrier = mockk<DatabaseWriteBarrier>()
        every { barrier.checkWritesAllowed(any<String>()) } just Runs
        coEvery { barrier.runWrite<Any?>(capture(spy.ops), any()) } coAnswers {
            spy.depth++
            try {
                secondArg<suspend () -> Any?>().invoke()
            } finally {
                spy.depth--
            }
        }
        return barrier
    }

    private fun stubInsertsRecordingDepth(spy: BarrierSpy, cid: String) {
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } coAnswers {
            spy.insertDepths += spy.depth
            21L
        }
        coEvery { operationRunEventDao.getByRunId(21L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } coAnswers {
            spy.insertDepths += spy.depth
            1L
        }
    }

    private fun importerWith(barrier: DatabaseWriteBarrier) = RestoreJournalImporter(
        journal, operationRunDao, operationRunEventDao, timeProvider, barrier
    )

    /** Real barrier whose early admission passes but whose next admission (inside runWrite) is blocked. */
    private fun barrierBlockedAfterEarlyCheck(): DatabaseWriteBarrier {
        every { maintenanceMode.currentMode() } returnsMany listOf(
            RestoreMaintenanceMode.Mode.NORMAL,
            RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        )
        every { maintenanceMode.isWritesAllowed() } returns false
        return DatabaseWriteBarrier(maintenanceMode)
    }

    private fun unblockMaintenance() {
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
    }

    private fun writeSuccessJournalWithEvents(count: Int): String {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/s.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        repeat(count) { i ->
            journal.appendEvent(
                correlationId = entry.operationCorrelationId,
                stage = "STAGE_$i",
                outcome = "SUCCESS",
                severity = "INFO",
                reasonCode = null,
                isTerminal = i == count - 1
            )
        }
        journal.commitJournal(entry)
        return entry.operationCorrelationId
    }

    private fun writeSuccessJournalWithoutEvents(): String {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/s.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        journal.commitJournal(entry)
        return entry.operationCorrelationId
    }

    private fun existingRun(cid: String) = OperationRun(
        id = 9L, correlationId = cid, operationType = "RESTORE_COSTBACKUP", status = "SUCCESS",
        startedAt = 1L, finishedAt = 2L, actor = "user", errorSummary = null
    )

    @Test
    fun `success import runs the run insert and every event insert inside a barrier write`() = runTest {
        val cid = writeSuccessJournalWithEvents(2)
        val spy = BarrierSpy()
        stubInsertsRecordingDepth(spy, cid)

        importerWith(spyBarrier(spy)).importLastSuccessJournalIfPresent()

        // One run insert plus two event inserts: three counted writes, each labelled, each in-flight.
        assertEquals(List(3) { successOp }, spy.ops.map { it.name })
        assertEquals(listOf(1, 1, 1), spy.insertDepths)
        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        coVerify(exactly = 2) { operationRunEventDao.insert(any()) }
        assertTrue(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `legacy zero-event success import runs its summary insert inside a barrier write`() = runTest {
        val cid = writeSuccessJournalWithoutEvents()
        val spy = BarrierSpy()
        stubInsertsRecordingDepth(spy, cid)

        importerWith(spyBarrier(spy)).importLastSuccessJournalIfPresent()

        assertEquals(listOf(successOp), spy.ops.map { it.name })
        assertEquals(listOf(1), spy.insertDepths)
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        assertTrue(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `failure import runs the run insert and every event insert inside a barrier write`() = runTest {
        val cid = writeFailureJournal("Incorrect password")
        val spy = BarrierSpy()
        stubInsertsRecordingDepth(spy, cid)

        importerWith(spyBarrier(spy)).importLastFailureJournalIfPresent()

        assertEquals(List(2) { failureOp }, spy.ops.map { it.name })
        assertEquals(listOf(1, 1), spy.insertDepths)
        assertTrue(journal.isFailureJournalImported(cid))
    }

    @Test
    fun `barrier blocked after the early check inserts no run row and leaves the success journal retryable`() = runTest {
        val cid = writeSuccessJournalWithEvents(1)
        val barrier = barrierBlockedAfterEarlyCheck()
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 7L
        coEvery { operationRunEventDao.getByRunId(7L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L
        val blockedImporter = importerWith(barrier)

        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        assertFalse(journal.isSuccessJournalImported(cid))
        assertEquals("a rejected write must not stay counted in flight", 0, barrier.inFlightWriteCount())

        // Next healthy startup retries the same journal to completion.
        unblockMaintenance()
        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
        assertTrue(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `barrier blocked before an event insert writes no event and does not mark the success journal`() = runTest {
        val cid = writeSuccessJournalWithEvents(1)
        val barrier = barrierBlockedAfterEarlyCheck()
        // The run row already exists, so the first write attempted is the event insert.
        coEvery { operationRunDao.getByCorrelationId(cid) } returns existingRun(cid)
        coEvery { operationRunEventDao.getByRunId(9L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L
        val blockedImporter = importerWith(barrier)

        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        assertFalse(journal.isSuccessJournalImported(cid))
        assertEquals(0, barrier.inFlightWriteCount())

        unblockMaintenance()
        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        assertTrue(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `barrier blocked after the early check does not mark a legacy zero-event journal imported`() = runTest {
        val cid = writeSuccessJournalWithoutEvents()
        val barrier = barrierBlockedAfterEarlyCheck()
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 7L
        val blockedImporter = importerWith(barrier)

        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        assertFalse(journal.isSuccessJournalImported(cid))

        unblockMaintenance()
        blockedImporter.importLastSuccessJournalIfPresent()

        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        assertTrue(journal.isSuccessJournalImported(cid))
    }

    @Test
    fun `barrier blocked after the early check does not mark the failure journal imported`() = runTest {
        val cid = writeFailureJournal("Incorrect password")
        val barrier = barrierBlockedAfterEarlyCheck()
        coEvery { operationRunDao.getByCorrelationId(cid) } returns null
        coEvery { operationRunDao.insert(any()) } returns 7L
        coEvery { operationRunEventDao.getByRunId(7L) } returns emptyList()
        coEvery { operationRunEventDao.insert(any()) } returns 1L
        val blockedImporter = importerWith(barrier)

        blockedImporter.importLastFailureJournalIfPresent()

        coVerify(exactly = 0) { operationRunDao.insert(any()) }
        coVerify(exactly = 0) { operationRunEventDao.insert(any()) }
        assertFalse(journal.isFailureJournalImported(cid))
        assertEquals(0, barrier.inFlightWriteCount())

        unblockMaintenance()
        blockedImporter.importLastFailureJournalIfPresent()

        coVerify(exactly = 1) { operationRunDao.insert(any()) }
        coVerify(exactly = 1) { operationRunEventDao.insert(any()) }
        assertTrue(journal.isFailureJournalImported(cid))
    }

    @Test
    fun `cancellation thrown by the barrier write propagates from every insert site and marks nothing`() = runTest {
        val cancellation = CancellationException("cancelled during write")
        val cancellingBarrier = mockk<DatabaseWriteBarrier>()
        every { cancellingBarrier.checkWritesAllowed(any<String>()) } just Runs
        coEvery { cancellingBarrier.runWrite<Any?>(any(), any()) } throws cancellation
        val cancellingImporter = importerWith(cancellingBarrier)

        // Success journal, run insert site.
        val successCid = writeSuccessJournalWithEvents(1)
        coEvery { operationRunDao.getByCorrelationId(successCid) } returns null
        assertSame(cancellation, runCatching { cancellingImporter.importLastSuccessJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isSuccessJournalImported(successCid))

        // Success journal, event insert site (run row already present).
        coEvery { operationRunDao.getByCorrelationId(successCid) } returns existingRun(successCid)
        coEvery { operationRunEventDao.getByRunId(9L) } returns emptyList()
        assertSame(cancellation, runCatching { cancellingImporter.importLastSuccessJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isSuccessJournalImported(successCid))

        // Legacy zero-event success journal.
        val legacyCid = writeSuccessJournalWithoutEvents()
        coEvery { operationRunDao.getByCorrelationId(legacyCid) } returns null
        assertSame(cancellation, runCatching { cancellingImporter.importLastSuccessJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isSuccessJournalImported(legacyCid))

        // Failure journal, run insert site.
        val failureCid = writeFailureJournal("Incorrect password")
        coEvery { operationRunDao.getByCorrelationId(failureCid) } returns null
        assertSame(cancellation, runCatching { cancellingImporter.importLastFailureJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isFailureJournalImported(failureCid))

        // Failure journal, event insert site (run row already present).
        coEvery { operationRunDao.getByCorrelationId(failureCid) } returns existingRun(failureCid)
        coEvery { operationRunEventDao.getByRunId(9L) } returns emptyList()
        assertSame(cancellation, runCatching { cancellingImporter.importLastFailureJournalIfPresent() }.exceptionOrNull())
        assertFalse(journal.isFailureJournalImported(failureCid))

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
