package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence-layer privacy test (W2 item 4): drives the REAL [RoomDiagnosticEventWriter]
 * with the REAL [EventMetadataSanitizer] and captures the row handed to the DAO. Asserts that
 * sentinel exception paths and sentinel metadata values never reach the stored row.
 *
 * Synthetic sentinels only.
 */
class RoomDiagnosticEventWriterPrivacyTest {

    private val sentinelPath = "/data/user/0/x/SENTINEL_PATH_9k.txt"
    private val sentinelBody = "SENTINEL_BODY_9k"
    private val sentinelSubject = "SENTINEL_SUBJECT_9k"
    private val sentinelSender = "SENTINEL_SENDER_9k@x.example"

    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
    }
    private val dao = mockk<PipelineDiagnosticEventDao>()
    private val stored = mutableListOf<PipelineDiagnosticEvent>()

    private fun writer(): RoomDiagnosticEventWriter {
        coEvery { dao.insert(any<PipelineDiagnosticEvent>()) } answers {
            stored.add(firstArg<PipelineDiagnosticEvent>())
            Unit
        }
        return RoomDiagnosticEventWriter(
            dao = dao,
            sanitizer = EventMetadataSanitizer(),
            timeProvider = FakeTimeProvider(1_716_163_200_000L),
            writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        )
    }

    private fun allStoredText(row: PipelineDiagnosticEvent): String = row.toString()

    @Test
    fun `stored row carries exception class but never the sentinel path from the exception message`() = runTest {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "ingestion",
            outcome = EventOutcome.FAILED_FINAL,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
            // KNOWN GAP P1 - free text survives pattern sanitization; do not assert it is stored
            // (nor absent). Only the path sentinel is asserted below.
            exception = IllegalStateException("cannot open $sentinelPath while handling FREE_TEXT_BODY_SENTINEL_9k"),
            isTerminal = true
        )

        writer().emit(event)

        assertEquals(1, stored.size)
        val row = stored.single()
        assertEquals("IllegalStateException", row.exceptionClass)
        assertFalse(allStoredText(row).contains("SENTINEL_PATH_9k"))
        assertEquals("UNKNOWN_ERROR", row.reasonCode)
    }

    @Test
    fun `stored metadata redacts blocked keys and path values and keeps allow-listed scalars`() = runTest {
        val metadata = SafeEventMetadata.builder()
            .put("emailBody", sentinelBody)
            .put("emailSubject", sentinelSubject)
            .put("filePath", sentinelPath)
            .put("sender", sentinelSender)
            .put("details", "failed at $sentinelPath")
            .put("count", 3)
            .build()
        val event = DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "front_door",
            outcome = EventOutcome.RECEIVED,
            metadata = metadata
        )

        writer().emit(event)

        val row = stored.single()
        val json = JSONObject(row.metadataJson!!)
        val text = allStoredText(row)
        assertFalse(text.contains("SENTINEL_BODY_9k"))
        assertFalse(text.contains("SENTINEL_SUBJECT_9k"))
        assertFalse(text.contains("SENTINEL_PATH_9k"))
        assertFalse(text.contains("SENTINEL_SENDER_9k"))
        assertEquals("[REDACTED]", json.getString("emailBody"))
        assertEquals("[REDACTED]", json.getString("emailSubject"))
        assertEquals("[REDACTED]", json.getString("filePath"))
        assertEquals("[REDACTED]", json.getString("sender"))
        assertEquals(3, json.getInt("count"))
    }

    @Test
    fun `hashed sender metadata is stored as redacted because the sender key is blocked`() = runTest {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "front_door",
            outcome = EventOutcome.RECEIVED,
            metadata = SafeEventMetadata.builder()
                .putHashed("messageId", "SENTINEL_MSGID_9k")
                .putHashed("sender", sentinelSender)
                .build()
        )

        writer().emit(event)

        val row = stored.single()
        val json = JSONObject(row.metadataJson!!)
        val messageIdValue = json.getString("messageId")
        assertTrue(messageIdValue.matches(Regex("^[0-9a-f]{16}$")))
        assertFalse(messageIdValue == "SENTINEL_MSGID_9k")
        // The persistence layer fails closed on the blocked "sender" key even for hashed values.
        assertEquals("[REDACTED]", json.getString("sender"))
        assertFalse(allStoredText(row).contains("SENTINEL_SENDER_9k"))
        assertFalse(allStoredText(row).contains("SENTINEL_MSGID_9k"))
    }

    @Test
    fun `event without exception or metadata stores null exception fields and null metadata`() = runTest {
        writer().emit(
            DiagnosticEvent(
                pipeline = AppPipeline.EMAIL,
                stage = "parser",
                outcome = EventOutcome.FAILED_FINAL,
                reasonCode = DiagnosticReasonCode.PARSER_FAILED
            )
        )

        val row = stored.single()
        assertEquals(null, row.exceptionClass)
        assertEquals(null, row.exceptionMessage)
        assertEquals(null, row.metadataJson)
        assertEquals("PARSER_FAILED", row.reasonCode)
    }
}
