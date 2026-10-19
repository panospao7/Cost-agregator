package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R26-V: [NotificationDiagnosticEmitter.emit] coalesces routine per-notification
 * pass/drop INFO diagnostics into hourly per-key counts. Everything else is written
 * unchanged, and the ordered / non-cancellable entry points bypass coalescing.
 */
class NotificationDiagnosticEmitterCoalescingTest {

    private val hourMs = NotificationDiagnosticEmitter.COALESCE_WINDOW_MS
    private val timeProvider = FakeTimeProvider(1_716_163_200_000L)
    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } answers { currentMode }
    }
    private val sink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
    private val written = mutableListOf<DiagnosticEvent>()
    private val writer = mockk<DiagnosticEventWriter>().also {
        coEvery { it.emit(any()) } answers { written += firstArg<DiagnosticEvent>() }
    }

    private val emitter = NotificationDiagnosticEmitter(
        writer,
        sink,
        maintenanceMode,
        Dispatchers.Unconfined,
        timeProvider
    )

    private fun received(
        correlationId: String,
        stage: String = "listener",
        severity: EventSeverity = EventSeverity.INFO,
        reasonCode: DiagnosticReasonCode? = null,
        isTerminal: Boolean = false,
        exception: Throwable? = null,
        metadata: SafeEventMetadata = SafeEventMetadata.empty()
    ) = DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = stage,
        outcome = EventOutcome.RECEIVED,
        severity = severity,
        reasonCode = reasonCode,
        correlationId = correlationId,
        isTerminal = isTerminal,
        exception = exception,
        metadata = metadata
    )

    private fun filterDrop(correlationId: String) = DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = "filter",
        outcome = EventOutcome.DROPPED,
        reasonCode = DiagnosticReasonCode.FILTER_REJECTED,
        correlationId = correlationId,
        isTerminal = true
    )

    private fun dedupeDuplicate(correlationId: String) = DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = "dedupe",
        outcome = EventOutcome.DUPLICATE,
        reasonCode = DiagnosticReasonCode.DUPLICATE,
        correlationId = correlationId,
        isTerminal = true
    )

    private fun problem(correlationId: String) = DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = "intake",
        outcome = EventOutcome.FAILED_RETRYABLE,
        reasonCode = DiagnosticReasonCode.ENQUEUE_FAILED,
        correlationId = correlationId,
        isTerminal = true
    )

    private fun countOf(event: DiagnosticEvent): Int =
        JSONObject(event.metadata.toJson()).getInt("count")

    @Test
    fun `V1 first listener RECEIVED is written`() = runTest {
        val event = received("c1")
        emitter.emit(event)
        assertEquals(listOf(event), written)
    }

    @Test
    fun `V2 repeats within the window are suppressed`() = runTest {
        emitter.emit(received("c1"))
        emitter.emit(received("c2"))
        timeProvider.advanceTime(hourMs - 1)
        emitter.emit(received("c3"))
        emitter.emit(received("c4"))
        assertEquals(1, written.size)
        assertEquals("c1", written.single().correlationId)
    }

    @Test
    fun `V3 first event after the window writes a summary with the count then the event`() = runTest {
        val first = received("c1")
        emitter.emit(first)
        emitter.emit(received("c2"))
        emitter.emit(received("c3"))
        emitter.emit(received("c4"))
        timeProvider.advanceTime(hourMs)
        val next = received("c5")
        emitter.emit(next)

        assertEquals(3, written.size)
        val summary = written[1]
        assertEquals(AppPipeline.NOTIFICATION, summary.pipeline)
        assertEquals("listener", summary.stage)
        assertEquals(EventOutcome.RECEIVED, summary.outcome)
        assertEquals(EventSeverity.INFO, summary.severity)
        assertEquals(3, countOf(summary))
        assertFalse(summary.correlationId in setOf("c1", "c2", "c3", "c4", "c5"))
        assertEquals(null, summary.entityId)
        assertEquals(null, summary.sourceType)
        assertEquals(next, written[2])
    }

    @Test
    fun `V4 problem event for a suppressed notification writes its RECEIVED row first and decrements the count`() = runTest {
        emitter.emit(received("c1"))
        val suppressed = received("c2")
        emitter.emit(suppressed)
        val problem = problem("c2")
        emitter.emit(problem)

        assertEquals(3, written.size)
        assertEquals(suppressed, written[1])
        assertEquals(problem, written[2])

        // The only suppressed event was surfaced, so the next window opens without a summary.
        timeProvider.advanceTime(hourMs)
        emitter.emit(received("c3"))
        assertEquals(4, written.size)
        assertEquals("c3", written[3].correlationId)
    }

    @Test
    fun `V5 warnings exceptions non-info other stages and captures are always written`() = runTest {
        emitter.emit(received("open")) // opens the listener window
        val always = listOf(
            received("w", severity = EventSeverity.WARNING),
            received("e", exception = IllegalStateException("x")),
            received("err", severity = EventSeverity.ERROR),
            received("dbg", severity = EventSeverity.DEBUG),
            received("other", stage = "capture_gate"),
            received("reason", reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED),
            received("term", isTerminal = true),
            DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake",
                outcome = EventOutcome.CREATED,
                correlationId = "capture"
            ),
            DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "filter",
                outcome = EventOutcome.DROPPED,
                severity = EventSeverity.WARNING,
                reasonCode = DiagnosticReasonCode.FILTER_REJECTED,
                correlationId = "filter-warn",
                isTerminal = true
            )
        )
        always.forEach { emitter.emit(it) }
        always.forEach { emitter.emit(it) }

        assertEquals(1 + always.size * 2, written.size)
        assertEquals(always + always, written.drop(1))
    }

    @Test
    fun `V6 ordered and non-cancellable entry points bypass coalescing`() = runTest {
        emitter.emit(received("c1")) // opens the window
        emitter.emit(received("c2")) // suppressed
        assertEquals(1, written.size)

        emitter.emitOrdered(received("o1"), problem("o1"))
        assertEquals(3, written.size)
        emitter.emitNonCancellable(received("n1"))
        assertEquals(4, written.size)
        emitter.emitOrderedNonCancellable(received("on1"), filterDrop("on1"))
        assertEquals(6, written.size)
        assertEquals(
            listOf("c1", "o1", "o1", "n1", "on1", "on1"),
            written.map { it.correlationId }
        )
    }

    @Test
    fun `V7 non-NORMAL maintenance mode uses the sink path and never coalesces`() = runTest {
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
        emitter.emit(received("c1"))
        emitter.emit(received("c2"))
        emitter.emit(received("c3"))

        assertTrue(written.isEmpty())
        coVerify(exactly = 3) {
            sink.recordDiagnosticEvent(any(), RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, null)
        }

        // Sink-routed events never opened a window, so the first NORMAL event is written.
        currentMode = RestoreMaintenanceMode.Mode.NORMAL
        emitter.emit(received("c4"))
        assertEquals(listOf("c4"), written.map { it.correlationId })
    }

    @Test
    fun `V8 filter and dedupe terminal drops coalesce per stage with separate counts`() = runTest {
        emitter.emit(filterDrop("f1"))
        emitter.emit(filterDrop("f2"))
        emitter.emit(filterDrop("f3"))
        emitter.emit(dedupeDuplicate("d1"))
        emitter.emit(dedupeDuplicate("d2"))
        assertEquals(listOf("f1", "d1"), written.map { it.correlationId })

        timeProvider.advanceTime(hourMs)
        emitter.emit(filterDrop("f4"))
        emitter.emit(dedupeDuplicate("d3"))

        assertEquals(6, written.size)
        val filterSummary = written[2]
        assertEquals("filter", filterSummary.stage)
        assertEquals(EventOutcome.DROPPED, filterSummary.outcome)
        assertEquals(DiagnosticReasonCode.FILTER_REJECTED, filterSummary.reasonCode)
        assertTrue(filterSummary.isTerminal)
        assertEquals(2, countOf(filterSummary))
        assertEquals("f4", written[3].correlationId)

        val dedupeSummary = written[4]
        assertEquals("dedupe", dedupeSummary.stage)
        assertEquals(EventOutcome.DUPLICATE, dedupeSummary.outcome)
        assertEquals(DiagnosticReasonCode.DUPLICATE, dedupeSummary.reasonCode)
        assertTrue(dedupeSummary.isTerminal)
        assertEquals(1, countOf(dedupeSummary))
        assertEquals("d3", written[5].correlationId)
    }

    @Test
    fun `V9 CancellationException from the writer propagates and is not routed to the sink`() = runTest {
        coEvery { writer.emit(any()) } throws CancellationException("cancelled")
        try {
            emitter.emit(received("c1"))
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected
        }
        try {
            emitter.emitOrdered(received("c2"), problem("c2"))
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected
        }
        coVerify(exactly = 0) { sink.recordDiagnosticEvent(any(), any(), any()) }
    }

    @Test
    fun `V9b non-cancellation writer failure still falls back to the sink`() = runTest {
        coEvery { writer.emit(any()) } throws IllegalStateException("db down")
        emitter.emit(received("c1"))
        coVerify(exactly = 1) {
            sink.recordDiagnosticEvent(any(), RestoreMaintenanceMode.Mode.NORMAL, any())
        }
    }

    @Test
    fun `V10 remembered RECEIVED events are bounded to 64 and the eldest is evicted`() = runTest {
        emitter.emit(received("open")) // written, opens window
        for (i in 1..65) {
            emitter.emit(received("s$i")) // 65 suppressed; s1 is evicted when s65 arrives
        }
        assertEquals(1, written.size)

        // Evicted id: only the problem event is written; no RECEIVED row is recovered.
        val evictedProblem = problem("s1")
        emitter.emit(evictedProblem)
        assertEquals(listOf("open", "s1"), written.map { it.correlationId })
        assertEquals(EventOutcome.FAILED_RETRYABLE, written[1].outcome)

        // Retained id: its RECEIVED row is recovered first, then the problem event.
        emitter.emit(problem("s2"))
        assertEquals(4, written.size)
        assertEquals(EventOutcome.RECEIVED, written[2].outcome)
        assertEquals("s2", written[2].correlationId)
        assertEquals(EventOutcome.FAILED_RETRYABLE, written[3].outcome)

        // 65 suppressed, one recovered (decremented), evicted one is not decremented.
        timeProvider.advanceTime(hourMs)
        emitter.emit(received("after"))
        val summary = written[4]
        assertEquals(EventOutcome.RECEIVED, summary.outcome)
        assertEquals(64, countOf(summary))
        assertNotEquals("after", summary.correlationId)
        assertEquals("after", written[5].correlationId)
    }

    @Test
    fun `V11 caller cancellation after planning still writes the summary and the event`() = runTest {
        emitter.emit(received("c1"))
        emitter.emit(received("c2"))
        emitter.emit(received("c3"))
        timeProvider.advanceTime(hourMs)

        val activeAtWrite = mutableListOf<Boolean>()
        var callerJob: Job? = null
        coEvery { writer.emit(any()) } coAnswers {
            written += firstArg<DiagnosticEvent>()
            activeAtWrite += currentCoroutineContext().isActive
            // Cancel the caller right after the summary row (second row overall) is written.
            if (written.size == 2) callerJob?.cancel()
        }

        val job = launch {
            callerJob = coroutineContext[Job]
            emitter.emit(received("c5"))
        }
        job.join()

        assertTrue(job.isCancelled)
        // c1, summary(count = 2), c5: the event after the cancelling write is still written.
        assertEquals(3, written.size)
        assertEquals(2, countOf(written[1]))
        assertEquals("c5", written[2].correlationId)
        // Both planned rows were written in a non-cancellable context.
        assertEquals(listOf(true, true), activeAtWrite)
    }

    @Test
    fun `V12 coalescible terminal drop releases its remembered RECEIVED slot`() = runTest {
        emitter.emit(received("open"))
        for (i in 1..64) emitter.emit(received("s$i")) // fills the remembered LRU exactly
        emitter.emit(filterDrop("s2")) // s2 finished normally: its slot is released
        emitter.emit(received("s65")) // fits without evicting s1
        assertEquals(listOf("open", "s2"), written.map { it.correlationId })

        // s1 is still remembered: RECEIVED row recovered first, then the problem.
        emitter.emit(problem("s1"))
        assertEquals(4, written.size)
        assertEquals("s1", written[2].correlationId)
        assertEquals(EventOutcome.RECEIVED, written[2].outcome)
        assertEquals(EventOutcome.FAILED_RETRYABLE, written[3].outcome)

        // s2 was released: a later problem event for it writes no recovered RECEIVED row.
        emitter.emit(problem("s2"))
        assertEquals(5, written.size)
        assertEquals(EventOutcome.FAILED_RETRYABLE, written[4].outcome)
    }

    @Test
    fun `V13 backwards clock is treated as an expired window and still summarises`() = runTest {
        emitter.emit(received("c1"))
        emitter.emit(received("c2"))
        timeProvider.advanceTime(-1L) // now < windowStart
        emitter.emit(received("c3"))

        assertEquals(3, written.size)
        assertEquals(1, countOf(written[1]))
        assertEquals("c3", written[2].correlationId)

        // The new window starts at the rewound time and suppresses normally again.
        emitter.emit(received("c4"))
        assertEquals(3, written.size)
    }

    @Test
    fun `V14 recovered RECEIVED from an older window does not decrement the new window`() = runTest {
        emitter.emit(received("c1"))
        emitter.emit(received("c2")) // window A: suppressed + remembered
        emitter.emit(received("c3")) // window A count = 2
        timeProvider.advanceTime(hourMs)
        emitter.emit(received("c4")) // summary(A = 2), opens window B
        emitter.emit(received("c5")) // window B count = 1
        assertEquals(listOf("c1"), written.take(1).map { it.correlationId })
        assertEquals(3, written.size)
        assertEquals(2, countOf(written[1]))

        // c2 is recovered from window A; window B must not be decremented (documented +1 overcount).
        emitter.emit(problem("c2"))
        assertEquals(5, written.size)
        assertEquals("c2", written[3].correlationId)
        assertEquals(EventOutcome.RECEIVED, written[3].outcome)

        timeProvider.advanceTime(hourMs)
        emitter.emit(received("c6"))
        assertEquals(7, written.size)
        assertEquals(1, countOf(written[5]))
        assertEquals("c6", written[6].correlationId)
    }

    @Test
    fun `V15 refresh is counted separately from listener`() = runTest {
        emitter.emit(received("l1", stage = "listener"))
        emitter.emit(received("r1", stage = "refresh"))
        emitter.emit(received("l2", stage = "listener"))
        emitter.emit(received("r2", stage = "refresh"))
        emitter.emit(received("r3", stage = "refresh"))
        assertEquals(listOf("l1", "r1"), written.map { it.correlationId })

        timeProvider.advanceTime(hourMs)
        emitter.emit(received("l3", stage = "listener"))
        emitter.emit(received("r4", stage = "refresh"))

        assertEquals(6, written.size)
        assertEquals("listener", written[2].stage)
        assertEquals(1, countOf(written[2]))
        assertEquals("l3", written[3].correlationId)
        assertEquals("refresh", written[4].stage)
        assertEquals(2, countOf(written[4]))
        assertEquals("r4", written[5].correlationId)
    }

    @Test
    fun `V16 summary metadata contains only count even when the event carries hashed metadata`() = runTest {
        fun withMetadata(id: String) = received(
            id,
            metadata = SafeEventMetadata.builder()
                .putHashed("pkg", "com.example.bank")
                .put("postTime", 123L)
                .build()
        )
        emitter.emit(withMetadata("c1"))
        emitter.emit(withMetadata("c2"))
        timeProvider.advanceTime(hourMs)
        emitter.emit(withMetadata("c3"))

        assertEquals(3, written.size)
        val summary = written[1]
        val keys = JSONObject(summary.metadata.toJson()).keys().asSequence().toSet()
        assertEquals(setOf("count"), keys)
        assertEquals(1, countOf(summary))
    }
}
