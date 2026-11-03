package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.backup.MaintenanceDiagnosticRecord
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P-09-2: every diagnostic source read in [DiagnosticsRepositoryImpl] must rethrow
 * [CancellationException] and still degrade to its empty default on any other failure
 * without hiding the remaining sources.
 */
class DiagnosticsRepositoryImplTest {

    private class Fixture {
        val pipeline = mockk<PipelineDiagnosticEventDao>()
        val operationRun = mockk<OperationRunDao>()
        val operationEvent = mockk<OperationRunEventDao>()
        val worker = mockk<BackgroundJobRunDao>()
        val safeSink = mockk<MaintenanceSafeDiagnosticSink>()
        val journal = mockk<RestoreJournal>()

        val pipelineEvent = mockk<PipelineDiagnosticEvent>(relaxed = true)
        val operationRunRow = mockk<OperationRun>(relaxed = true)
        val operationEventRow = mockk<OperationRunEvent>(relaxed = true)
        val workerRow = BackgroundJobRun(workerName = "W", startedAt = 1L, status = "FAILED")
        val safeRecord = MaintenanceDiagnosticRecord(
            id = "1", operation = "op", mode = "m", pipeline = null, entity = null,
            reason = "r", timestamp = 1L, correlationId = CID,
            outcome = "FAILED_FINAL", severity = "ERROR"
        )
        val journalEvent = RestoreJournal.RestoreJournalEvent(
            correlationId = CID, stage = "s", outcome = "FAILED_FINAL", severity = "ERROR",
            reasonCode = null, occurredAt = 1L, metadataJson = null, exceptionClass = null,
            exceptionMessageSafe = null, isTerminal = true
        )

        init {
            coEvery { pipeline.getByCorrelationId(any()) } returns listOf(pipelineEvent)
            coEvery { operationRun.getByCorrelationId(any()) } returns operationRunRow
            coEvery { operationEvent.getByCorrelationId(any()) } returns listOf(operationEventRow)
            coEvery { worker.getByCorrelationId(any()) } returns listOf(workerRow)
            every { safeSink.observeRecent() } returns flowOf(listOf(safeRecord))
            every { journal.getAllDiagnosticEventsByCorrelationId(any()) } returns listOf(journalEvent)

            coEvery { pipeline.getRecentFailures(any()) } returns listOf(pipelineEvent)
            coEvery { operationEvent.getRecentFailures(any()) } returns listOf(operationEventRow)
            coEvery { worker.getRecentFailedRuns(any()) } returns listOf(workerRow)
            every { journal.getAllDiagnosticEvents() } returns listOf(journalEvent)
        }

        val repo = DiagnosticsRepositoryImpl(pipeline, operationRun, operationEvent, worker, safeSink, journal)
    }

    private class Site(val name: String, val stub: Fixture.(Throwable) -> Unit)

    private val traceSites = listOf(
        Site("pipeline") { t -> coEvery { pipeline.getByCorrelationId(any()) } throws t },
        Site("operation_run") { t -> coEvery { operationRun.getByCorrelationId(any()) } throws t },
        Site("operation_event") { t -> coEvery { operationEvent.getByCorrelationId(any()) } throws t },
        Site("worker") { t -> coEvery { worker.getByCorrelationId(any()) } throws t },
        Site("safe_sink") { t -> every { safeSink.observeRecent() } throws t },
        Site("restore_journal") { t -> every { journal.getAllDiagnosticEventsByCorrelationId(any()) } throws t }
    )

    private val failureSites = listOf(
        Site("pipeline") { t -> coEvery { pipeline.getRecentFailures(any()) } throws t },
        Site("operation_event") { t -> coEvery { operationEvent.getRecentFailures(any()) } throws t },
        Site("worker") { t -> coEvery { worker.getRecentFailedRuns(any()) } throws t },
        Site("safe_sink") { t -> every { safeSink.observeRecent() } throws t },
        Site("restore_journal") { t -> every { journal.getAllDiagnosticEvents() } throws t }
    )

    private fun presence(trace: DiagnosticTrace): Map<String, Boolean> = mapOf(
        "pipeline" to trace.pipelineEvents.isNotEmpty(),
        "operation_run" to trace.operationRuns.isNotEmpty(),
        "operation_event" to trace.operationRunEvents.isNotEmpty(),
        "worker" to trace.workerRuns.isNotEmpty(),
        "safe_sink" to trace.safeSinkEvents.isNotEmpty(),
        "restore_journal" to trace.restoreJournalEvents.isNotEmpty()
    )

    private suspend fun assertRethrowsCancellation(site: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            return
        }
        fail("CancellationException was swallowed at site: $site")
    }

    @Test
    fun `trace with healthy sources returns every source`() = runTest {
        val trace = Fixture().repo.getTraceByCorrelationId(CID)

        assertEquals(CID, trace.correlationId)
        assertTrue(presence(trace).values.all { it })
    }

    @Test
    fun `trace rethrows cancellation at each of the six sites`() = runTest {
        for (site in traceSites) {
            val f = Fixture()
            site.stub(f, CancellationException("cancelled"))

            assertRethrowsCancellation("trace/${site.name}") { f.repo.getTraceByCorrelationId(CID) }
        }
    }

    @Test
    fun `trace degrades one failing source to its default without hiding the others`() = runTest {
        for (site in traceSites) {
            val f = Fixture()
            site.stub(f, IllegalStateException("boom"))

            val present = presence(f.repo.getTraceByCorrelationId(CID))

            val expected = traceSites.associate { it.name to (it.name != site.name) }
            assertEquals("failing site: ${site.name}", expected, present)
        }
    }

    @Test
    fun `trace with every source failing returns empty defaults`() = runTest {
        val f = Fixture()
        for (site in traceSites) site.stub(f, IllegalStateException("boom"))

        val trace = f.repo.getTraceByCorrelationId(CID)

        assertTrue(presence(trace).values.none { it })
    }

    @Test
    fun `failures with healthy sources report all five sources`() = runTest {
        val sources = Fixture().repo.getRecentFailures(50).map { it.source }.toSet()

        assertEquals(failureSites.map { it.name }.toSet(), sources)
    }

    @Test
    fun `failures rethrow cancellation at each of the five sites`() = runTest {
        for (site in failureSites) {
            val f = Fixture()
            site.stub(f, CancellationException("cancelled"))

            assertRethrowsCancellation("failures/${site.name}") { f.repo.getRecentFailures(50) }
        }
    }

    @Test
    fun `failures degrade one failing source without hiding the others`() = runTest {
        for (site in failureSites) {
            val f = Fixture()
            site.stub(f, IllegalStateException("boom"))

            val sources = f.repo.getRecentFailures(50).map { it.source }.toSet()

            val expected = failureSites.map { it.name }.toSet() - site.name
            assertEquals("failing site: ${site.name}", expected, sources)
        }
    }

    @Test
    fun `failures with every source failing return an empty list`() = runTest {
        val f = Fixture()
        for (site in failureSites) site.stub(f, IllegalStateException("boom"))

        assertTrue(f.repo.getRecentFailures(50).isEmpty())
    }

    private companion object {
        const val CID = "corr-1"
    }
}
