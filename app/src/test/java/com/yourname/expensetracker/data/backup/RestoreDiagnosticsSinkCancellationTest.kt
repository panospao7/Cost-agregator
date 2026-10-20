package com.yourname.expensetracker.data.backup

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DIAG-04 / W2-1: the Room operation-run handle path of [RestoreDiagnosticsSink]
 * must propagate [CancellationException] (structured concurrency) while still
 * swallowing ordinary best-effort write failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreDiagnosticsSinkCancellationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class ThrowingHandle(private val failure: Throwable) : OperationRunHandle {
        var eventCalls = 0
        var failedFinalCalls = 0
        override val runId: Long = 1L
        override val correlationId: String = "corr-test"

        override suspend fun event(
            stage: String,
            outcome: EventOutcome,
            reasonCode: DiagnosticReasonCode?,
            severity: EventSeverity,
            metadata: SafeEventMetadata,
            entityType: String?,
            entityId: Long?,
            exception: Throwable?,
            isTerminal: Boolean
        ) {
            eventCalls++
            throw failure
        }

        override suspend fun increment(
            processed: Int,
            succeeded: Int,
            failed: Int,
            skipped: Int,
            warnings: Int,
            errors: Int
        ) = Unit

        override suspend fun success() = Unit
        override suspend fun partialSuccess(summary: String?) = Unit
        override suspend fun failedFinal(reason: String, error: Throwable?) {
            failedFinalCalls++
            throw failure
        }

        override suspend fun failedRetryable(reason: String, error: Throwable?) = Unit
        override suspend fun cancelled(reason: String?) = Unit
    }

    private class RecordingSafeSink : MaintenanceSafeDiagnosticSink {
        var diagnosticEvents = 0
        override suspend fun recordBlockedOperation(
            operation: String,
            mode: RestoreMaintenanceMode.Mode,
            pipeline: String?,
            entity: String?,
            reason: MaintenanceBlockedReason
        ) = Unit

        override suspend fun recordDiagnosticEvent(
            event: DiagnosticEvent,
            mode: RestoreMaintenanceMode.Mode,
            writeFailure: Throwable?
        ) {
            diagnosticEvents++
        }

        override fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>> = emptyFlow()
        override suspend fun clearOlderThan(cutoffMs: Long) = Unit
    }

    private fun newSink(handle: OperationRunHandle?, safeSink: MaintenanceSafeDiagnosticSink): RestoreDiagnosticsSink {
        val directory: File = temporaryFolder.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<android.content.Context>()) {
            override fun getFilesDir(): File = directory
        }
        val timeProvider = FakeTimeProvider(1716163200000L)
        return RestoreDiagnosticsSink(
            operationRunHandle = handle,
            restoreJournal = RestoreJournal(context, timeProvider),
            safeSink = safeSink,
            maintenanceMode = RestoreMaintenanceMode(context, timeProvider),
            correlationId = "corr-test",
            operationType = "RESTORE_COSTBACKUP"
        )
    }

    @Test
    fun event_propagates_cancellation_from_room_handle() {
        val handle = ThrowingHandle(CancellationException("cancelled-by-test"))
        val sink = newSink(handle, RecordingSafeSink())

        assertThrows(CancellationException::class.java) {
            runBlocking { sink.event(stage = "STAGE", outcome = EventOutcome.ATTEMPTED) }
        }
        assertEquals(1, handle.eventCalls)
    }

    @Test
    fun finalizeRunFailed_propagates_cancellation_from_room_handle() {
        val handle = ThrowingHandle(CancellationException("cancelled-by-test"))
        val sink = newSink(handle, RecordingSafeSink())

        assertThrows(CancellationException::class.java) {
            runBlocking { sink.finalizeRunFailed("REASON", IllegalStateException("boom")) }
        }
        assertEquals(1, handle.failedFinalCalls)
    }

    @Test
    fun event_still_swallows_non_cancellation_room_handle_failure() {
        val handle = ThrowingHandle(IllegalStateException("room-write-failed"))
        val safeSink = RecordingSafeSink()
        val sink = newSink(handle, safeSink)

        runBlocking { sink.event(stage = "STAGE", outcome = EventOutcome.ATTEMPTED) }

        assertEquals(1, handle.eventCalls)
        assertEquals(1, safeSink.diagnosticEvents)
    }

    @Test
    fun finalizeRunFailed_still_swallows_non_cancellation_room_handle_failure() {
        val handle = ThrowingHandle(IllegalStateException("room-write-failed"))
        val sink = newSink(handle, RecordingSafeSink())

        runBlocking { sink.finalizeRunFailed("REASON") }

        assertEquals(1, handle.failedFinalCalls)
    }

    @Test
    fun room_handle_is_not_called_after_live_db_swap_started() {
        val handle = ThrowingHandle(CancellationException("must-not-be-reached"))
        val sink = newSink(handle, RecordingSafeSink())
        sink.markLiveDbSwapStarted()

        runBlocking {
            sink.event(stage = "STAGE", outcome = EventOutcome.ATTEMPTED)
            sink.finalizeRunFailed("REASON")
        }

        assertEquals(0, handle.eventCalls)
        assertEquals(0, handle.failedFinalCalls)
    }
}
