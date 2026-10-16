package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/**
 * U-004 (batch G2): [RoomDiagnosticEventWriter] and [RoomOperationRunRecorder] are
 * regular DB writers and must consult [DatabaseWriteBarrier] before every write.
 *
 * Contract under test:
 *  - barrier open: writes behave exactly as before;
 *  - barrier blocked: no DAO write happens; the writer-level entry points that the
 *    Composite* wrappers already guard (`emit`, `start`) throw
 *    [DatabaseAccessBlockedException] (the composites route it to the safe sink),
 *    while the best-effort handle operations never fail the business operation and
 *    report to the safe sink with bounded stage names only;
 *  - [CancellationException] is never swallowed.
 */
class DiagnosticWritersWriteBarrierTest {

    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } answers { currentMode }
    }
    private val barrier = DatabaseWriteBarrier(maintenanceMode)
    private val timeProvider = FakeTimeProvider(1_716_163_200_000L)

    private val diagnosticDao = mockk<PipelineDiagnosticEventDao>(relaxed = true)
    private val runDao = mockk<OperationRunDao>(relaxed = true)
    private val eventDao = mockk<OperationRunEventDao>(relaxed = true)
    private val safeSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)

    private fun diagnosticWriter(writeBarrier: DatabaseWriteBarrier = barrier) =
        RoomDiagnosticEventWriter(diagnosticDao, EventMetadataSanitizer(), timeProvider, writeBarrier)

    private fun recorder(writeBarrier: DatabaseWriteBarrier = barrier) = RoomOperationRunRecorder(
        runDao = runDao,
        eventDao = eventDao,
        sanitizer = EventMetadataSanitizer(),
        timeProvider = timeProvider,
        safeSink = safeSink,
        restoreMaintenanceMode = maintenanceMode,
        writeBarrier = writeBarrier
    )

    private fun block() {
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
    }

    private fun event() = DiagnosticEvent(
        pipeline = AppPipeline.BACKUP_RESTORE,
        stage = "stage",
        outcome = EventOutcome.COMPLETED
    )

    // ── RoomDiagnosticEventWriter ───────────────────────────────────────────

    @Test
    fun `diagnostic writer inserts when barrier is open`() = runTest {
        diagnosticWriter().emit(event())

        coVerify(exactly = 1) { diagnosticDao.insert(any<PipelineDiagnosticEvent>()) }
    }

    @Test
    fun `diagnostic writer does not insert and throws blocked exception when barrier denies`() = runTest {
        block()

        try {
            diagnosticWriter().emit(event())
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, e.mode)
        }

        coVerify(exactly = 0) { diagnosticDao.insert(any<PipelineDiagnosticEvent>()) }
    }

    @Test
    fun `diagnostic writer does not swallow cancellation from the barrier`() = runTest {
        val cancelling = mockk<DatabaseWriteBarrier>()
        val cancellation = CancellationException("cancelled")
        every { cancelling.checkWritesAllowed(any<String>()) } throws cancellation

        try {
            diagnosticWriter(cancelling).emit(event())
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { diagnosticDao.insert(any<PipelineDiagnosticEvent>()) }
    }

    @Test
    fun `composite diagnostic writer routes room barrier denial to the safe sink`() = runTest {
        // The composite's own mode view and barrier are open; only the Room writer's
        // barrier denies, so the composite must catch the exception and use the safe sink.
        val compositeMode = mockk<RestoreMaintenanceMode>()
        every { compositeMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val composite = CompositeDiagnosticEventWriter(
            roomWriter = diagnosticWriter(),
            safeSink = safeSink,
            restoreMaintenanceMode = compositeMode,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        )
        block()

        composite.emit(event())

        coVerify(exactly = 0) { diagnosticDao.insert(any<PipelineDiagnosticEvent>()) }
        coVerify(exactly = 1) {
            safeSink.recordDiagnosticEvent(any(), any(), match { it is DatabaseAccessBlockedException })
        }
    }

    // ── RoomOperationRunRecorder ────────────────────────────────────────────

    @Test
    fun `recorder start inserts run and STARTED event when barrier is open`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L

        val handle = recorder().start("EXPORT_CSV")

        assertEquals(7L, handle.runId)
        coVerify(exactly = 1) { runDao.insert(any<OperationRun>()) }
        coVerify(exactly = 1) { eventDao.insert(any<OperationRunEvent>()) }
    }

    @Test
    fun `recorder start does not insert and throws blocked exception when barrier denies`() = runTest {
        block()

        try {
            recorder().start("EXPORT_CSV")
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, e.mode)
        }

        coVerify(exactly = 0) { runDao.insert(any<OperationRun>()) }
        coVerify(exactly = 0) { eventDao.insert(any<OperationRunEvent>()) }
    }

    @Test
    fun `recorder start does not swallow cancellation from the barrier`() = runTest {
        val cancelling = mockk<DatabaseWriteBarrier>()
        val cancellation = CancellationException("cancelled")
        every { cancelling.checkWritesAllowed(any<String>()) } throws cancellation

        try {
            recorder(cancelling).start("EXPORT_CSV")
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { runDao.insert(any<OperationRun>()) }
    }

    @Test
    fun `handle event is best-effort and writes nothing when barrier denies`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        val handle = recorder().start("EXPORT_CSV")
        block()

        handle.event(stage = "STEP", outcome = EventOutcome.COMPLETED)

        // Only the STARTED event (written while open) reached the DAO.
        coVerify(exactly = 1) { eventDao.insert(any<OperationRunEvent>()) }
        coVerify(exactly = 1) {
            safeSink.recordDiagnosticEvent(
                match { it.stage == "operation_event_write_failed" && it.correlationId == handle.correlationId },
                any(),
                any()
            )
        }
    }

    @Test
    fun `handle increment is best-effort and writes nothing when barrier denies`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        val handle = recorder().start("EXPORT_CSV")
        block()

        handle.increment(processed = 1, succeeded = 1)

        coVerify(exactly = 0) { runDao.incrementCounters(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            safeSink.recordDiagnosticEvent(
                match { it.stage == "operation_increment_failed" },
                any(),
                any()
            )
        }
    }

    @Test
    fun `handle increment persists counters when barrier is open`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        val handle = recorder().start("EXPORT_CSV")

        handle.increment(processed = 2, succeeded = 1, failed = 1)

        coVerify(exactly = 1) { runDao.incrementCounters(7L, 2, 1, 1, 0, 0, 0) }
    }

    @Test
    fun `handle terminal finalization never throws and writes nothing when barrier denies`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        val handle = recorder().start("EXPORT_CSV")
        block()

        handle.success()
        handle.failedFinal("reason", IllegalStateException("boom"))

        coVerify(exactly = 0) { runDao.finalizeIfRunning(any(), any(), any(), any()) }
        assertTrue(handle.runId == 7L)
        // G2: the blocked terminal outcome is not lost — a bounded terminal safe-sink record
        // (no free-text summary / exception message in metadata) is written per finalize.
        val recorded = mutableListOf<DiagnosticEvent>()
        coVerify(exactly = 2) { safeSink.recordDiagnosticEvent(capture(recorded), any(), any()) }
        assertTrue(recorded.all { it.stage == "operation_finalize_failed" && it.isTerminal })
        assertTrue(recorded.all { it.correlationId == handle.correlationId })
        assertEquals(
            listOf(EventOutcome.COMPLETED, EventOutcome.FAILED_FINAL),
            recorded.map { it.outcome }
        )
        val metadata = recorded.map { JSONObject(it.metadata.toJson()) }
        metadata.forEach { json ->
            assertEquals(setOf("operationType", "terminalStatus"), json.keys().asSequence().toSet())
        }
        assertEquals(listOf("SUCCESS", "FAILED_FINAL"), metadata.map { it.getString("terminalStatus") })
    }

    @Test
    fun `handle terminal finalization writes when barrier is open`() = runTest {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        coEvery { runDao.finalizeIfRunning(any(), any(), any(), any()) } returns 1
        val handle = recorder().start("EXPORT_CSV")

        handle.success()

        coVerify(exactly = 1) { runDao.finalizeIfRunning(7L, "SUCCESS", any(), any()) }
        // STARTED + terminal event.
        coVerify(exactly = 2) { eventDao.insert(any<OperationRunEvent>()) }
    }

    @Test
    fun `composite recorder falls back to safe handle when room start is denied`() = runTest {
        // Composite's own mode view and barrier are open; only the Room recorder's barrier denies.
        val compositeMode = mockk<RestoreMaintenanceMode>()
        every { compositeMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val composite = CompositeOperationRunRecorder(
            roomRecorder = recorder(),
            safeSink = safeSink,
            restoreMaintenanceMode = compositeMode,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            timeProvider = timeProvider
        )
        block()

        val handle = composite.start("EXPORT_CSV")

        assertTrue(handle is SafeSinkOperationRunHandle)
        coVerify(exactly = 0) { runDao.insert(any<OperationRun>()) }
    }
}
