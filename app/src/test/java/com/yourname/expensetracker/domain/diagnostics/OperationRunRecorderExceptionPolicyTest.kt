package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DIAG-01: exception message text must never reach any stored diagnostics field. Only the
 * exception class simple name may be persisted. Drives the REAL recorders and captures what the
 * DAOs / safe sink receive. Synthetic secret only.
 */
class OperationRunRecorderExceptionPolicyTest {

    private val secret = "secret-123"

    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
    }
    private val barrier = DatabaseWriteBarrier(maintenanceMode)
    private val timeProvider = FakeTimeProvider(1_716_163_200_000L)
    private val runDao = mockk<OperationRunDao>(relaxed = true)
    private val eventDao = mockk<OperationRunEventDao>(relaxed = true)
    private val safeSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)

    private val finalizedSummaries = mutableListOf<String?>()
    private val storedEvents = mutableListOf<OperationRunEvent>()

    private fun roomRecorder(): RoomOperationRunRecorder {
        coEvery { runDao.insert(any<OperationRun>()) } returns 7L
        coEvery { runDao.finalizeIfRunning(any(), any(), any(), any()) } answers {
            finalizedSummaries.add(arg<String?>(3))
            1
        }
        coEvery { eventDao.insert(any<OperationRunEvent>()) } answers {
            storedEvents.add(firstArg<OperationRunEvent>())
            1L
        }
        return RoomOperationRunRecorder(
            runDao = runDao,
            eventDao = eventDao,
            sanitizer = EventMetadataSanitizer(),
            timeProvider = timeProvider,
            safeSink = safeSink,
            restoreMaintenanceMode = maintenanceMode,
            writeBarrier = barrier
        )
    }

    private suspend fun runFailing(recorder: OperationRunRecorder) {
        try {
            recorder.runOperation<Unit>("EXPORT_CSV") {
                throw IllegalStateException("payload $secret leaked")
            }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // rethrown by runOperation
        }
    }

    private fun assertNoSecretStored() {
        assertTrue("expected a finalize write", finalizedSummaries.isNotEmpty())
        assertFalse(finalizedSummaries.toString().contains(secret))
        assertTrue("expected stored events", storedEvents.isNotEmpty())
        assertFalse(storedEvents.joinToString("\n") { it.toString() }.contains(secret))
    }

    @Test
    fun `room runOperation failure stores class name only and never the exception message`() = runTest {
        runFailing(roomRecorder())

        assertNoSecretStored()
        assertEquals(listOf<String?>("IllegalStateException"), finalizedSummaries)
        val terminal = storedEvents.single { it.isTerminal }
        assertEquals("IllegalStateException", terminal.exceptionClass)
        assertEquals("IllegalStateException", terminal.exceptionMessage)
    }

    @Test
    fun `handle failedFinal with exception and no reason text stores only class name in event`() = runTest {
        val handle = roomRecorder().start("EXPORT_CSV")

        handle.event(
            stage = "STEP",
            outcome = EventOutcome.FAILED_FINAL,
            exception = IllegalArgumentException("row $secret invalid")
        )

        val row = storedEvents.last { it.stage == "STEP" }
        assertEquals("IllegalArgumentException", row.exceptionClass)
        assertEquals("IllegalArgumentException", row.exceptionMessage)
        assertFalse(row.toString().contains(secret))
    }

    @Test
    fun `handle failedFinal with a bounded reason stores the reason and class name never the message`() = runTest {
        val handle = roomRecorder().start("EXPORT_CSV")

        handle.failedFinal("FAILED", IllegalStateException(secret))

        assertEquals(listOf<String?>("FAILED"), finalizedSummaries)
        assertFalse(storedEvents.joinToString("\n") { it.toString() }.contains(secret))
        assertEquals("IllegalStateException", storedEvents.single { it.isTerminal }.exceptionMessage)
    }

    @Test
    fun `composite runOperation failure stores class name only and never the exception message`() = runTest {
        val composite = CompositeOperationRunRecorder(
            roomRecorder = roomRecorder(),
            safeSink = safeSink,
            restoreMaintenanceMode = maintenanceMode,
            writeBarrier = barrier,
            timeProvider = timeProvider
        )

        runFailing(composite)

        assertNoSecretStored()
        assertEquals(listOf<String?>("IllegalStateException"), finalizedSummaries)
    }

    @Test
    fun `composite safe-sink handle records class name as status reason not the exception message`() = runTest {
        val blockedMode = mockk<RestoreMaintenanceMode>()
        every { blockedMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
        val recorded = mutableListOf<DiagnosticEvent>()
        coEvery { safeSink.recordDiagnosticEvent(capture(recorded), any(), any()) } returns Unit
        val composite = CompositeOperationRunRecorder(
            roomRecorder = roomRecorder(),
            safeSink = safeSink,
            restoreMaintenanceMode = blockedMode,
            writeBarrier = barrier,
            timeProvider = timeProvider
        )

        runFailing(composite)

        val terminal = recorded.single { it.isTerminal }
        assertEquals(
            "IllegalStateException",
            org.json.JSONObject(terminal.metadata.toJson()).getString("statusReason")
        )
        assertFalse(terminal.metadata.toJson().contains(secret))
    }

    @Test
    fun `throwable summary returns class simple name and ignores the message`() {
        assertEquals(
            "IllegalStateException",
            DiagnosticThrowableSummary.className(IllegalStateException(secret))
        )
        assertEquals(
            "Exception",
            DiagnosticThrowableSummary.className(object : Exception(secret) {})
        )
    }
}
