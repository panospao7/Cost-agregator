package com.yourname.expensetracker.data.backup

import android.content.Context
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DIAG-03: the DataStore maintenance sink must never persist exception or write-failure
 * message text; only the exception class simple name may be stored.
 */
class DataStoreMaintenanceSafeDiagnosticSinkPrivacyTest {

    private companion object {
        const val SECRET = "secret-456"
    }

    private lateinit var tempDir: File
    private lateinit var sink: DataStoreMaintenanceSafeDiagnosticSink

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("datastore_sink_privacy_").toFile()
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns tempDir
        every { ctx.applicationContext } returns ctx
        sink = DataStoreMaintenanceSafeDiagnosticSink(ctx, FakeTimeProvider(fixedTime = 1_700_000_000_000L))
        // The preferencesDataStore delegate is a JVM-wide singleton; reset leftovers.
        runBlocking { sink.clearOlderThan(Long.MAX_VALUE) }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun exception_and_write_failure_messages_are_not_stored() = runTest {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "verify",
            outcome = EventOutcome.FAILED_FINAL,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.VALIDATION_FAILED,
            correlationId = "corr-12345678",
            exception = IllegalStateException(SECRET),
            isTerminal = true
        )

        sink.recordDiagnosticEvent(
            event = event,
            mode = RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
            writeFailure = java.io.IOException("write $SECRET")
        )

        val record = sink.observeRecent().first().single()
        assertFalse(record.toString().contains(SECRET))
        assertEquals("IllegalStateException", record.exceptionClass)
        assertEquals("IllegalStateException", record.exceptionMessageSafe)
        assertEquals("IOException", record.writeFailureClass)
        assertEquals("IOException", record.writeFailureMessageSafe)
    }

    @Test
    fun absent_exception_and_write_failure_store_no_message_fields() = runTest {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "verify",
            outcome = EventOutcome.COMPLETED,
            severity = EventSeverity.INFO,
            correlationId = "corr-12345678"
        )

        sink.recordDiagnosticEvent(event, RestoreMaintenanceMode.Mode.NORMAL, writeFailure = null)

        val record = sink.observeRecent().first().single()
        assertEquals(null, record.exceptionMessageSafe)
        assertEquals(null, record.writeFailureMessageSafe)
    }
}
