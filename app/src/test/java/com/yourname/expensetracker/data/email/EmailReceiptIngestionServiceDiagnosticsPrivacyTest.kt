package com.yourname.expensetracker.data.email

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MerchantMatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * W2 call-site privacy test for the email ingestion path.
 *
 * Drives [EmailReceiptIngestionService] through its PRIMARY constructor with a capturing
 * [DiagnosticEventWriter] and asserts that no raw sender / subject / body / messageId /
 * merchant / path sentinel appears in any non-exception field of any emitted event.
 *
 * Production references (EmailReceiptIngestionService.kt):
 *  - front_door RECEIVED metadata: putHashed("messageId"), putHashed("sender")
 *  - provider_detection metadata: put("provider")
 *  - coordinator FAILED_FINAL metadata: put("retryable", false)
 *  - ingestion FAILED_FINAL: sourceIdHash = hmacSha256Prefix(messageId, ..., 16), exception = e
 *
 * Note: the ingestion event carries the raw Throwable to the writer. Only pattern-based
 * sanitization (file paths, IBANs, JWTs, bearer tokens and runs of 12+ digits are replaced,
 * then the result is capped at 256 chars; a message longer than 512 chars is replaced
 * entirely with [REDACTED]; see EventMetadataSanitizer.sanitizeStringValue) applies at
 * persistence, so free text outside those patterns survives it. That is a KNOWN, UNTESTED gap
 * (release follow-up, see RoomDiagnosticEventWriterPrivacyTest KNOWN GAP P1). Here the
 * exception-derived assertion is limited to the event's other fields.
 */
class EmailReceiptIngestionServiceDiagnosticsPrivacyTest {

    private val sentinelSender = "SENTINEL_SENDER_9k@x.example"
    private val sentinelSubject = "SENTINEL_SUBJECT_9k"
    private val sentinelBody = "SENTINEL_BODY_9k"
    private val sentinelMessageId = "SENTINEL_MSGID_9k"
    private val sentinelMerchant = "SENTINEL_MERCHANT_9k"
    private val sentinelPath = "/data/user/0/x/SENTINEL_PATH_9k.txt"

    /** Explicit allow-list of metadata keys the email path may emit. */
    private val allowedMetadataKeys = setOf("messageId", "sender", "provider", "retryable")
    private val allowedProviders = setOf("amazon", "uber", "apple", "unknown")
    private val allowedReasonCodes = setOf(
        null,
        DiagnosticReasonCode.PARSER_FAILED,
        DiagnosticReasonCode.VALIDATION_FAILED,
        DiagnosticReasonCode.DUPLICATE,
        DiagnosticReasonCode.UNKNOWN_ERROR,
        DiagnosticReasonCode.RESTORE_BLOCKED
    )
    private val hex16 = Regex("^[0-9a-f]{16}$")

    private class CapturingWriter : DiagnosticEventWriter {
        val events = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) {
            events.add(event)
        }
    }

    private val writer = CapturingWriter()
    private val receiptParser = mockk<ReceiptParser>()
    private val coordinator = mockk<ReceiptLifecycleCoordinator>()
    private val merchantNormalizer = mockk<MerchantNormalizer>()
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val amazonParser = mockk<AmazonReceiptParser>()
    private val uberParser = mockk<UberReceiptParser>()
    private val appleParser = mockk<AppleReceiptParser>()

    private lateinit var service: EmailReceiptIngestionService

    @Before
    fun setup() {
        service = EmailReceiptIngestionService(
            receiptParser = receiptParser,
            receiptLifecycleCoordinator = coordinator,
            merchantNormalizer = merchantNormalizer,
            writeBarrier = writeBarrier,
            diagnosticEventWriter = writer,
            hashingService = DefaultSensitiveHashingService()
        )
        setPrivateField(service, "amazonParser", amazonParser)
        setPrivateField(service, "uberParser", uberParser)
        setPrivateField(service, "appleParser", appleParser)

        every { amazonParser.canParse(any(), any(), any()) } returns false
        every { uberParser.canParse(any(), any(), any()) } returns false
        every { appleParser.canParse(any(), any(), any()) } returns false
        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val raw = firstArg<String>()
            MerchantLookupResult(
                canonical = com.yourname.expensetracker.data.database.entity.MerchantCanonical(
                    id = 1L,
                    normalizedName = raw,
                    searchKey = raw.lowercase()
                ),
                alias = null,
                confidence = 1.0f,
                matchType = MerchantMatchType.EXACT_MATCH
            )
        }
    }

    private fun parsersReturn(receipt: ParsedEmailReceipt?) {
        every { amazonParser.parse(any(), any()) } returns receipt
        every { uberParser.parse(any(), any()) } returns receipt
        every { appleParser.parse(any(), any()) } returns receipt
    }

    private fun sentinelReceipt() = ParsedEmailReceipt(
        merchant = sentinelMerchant,
        amount = 12.34,
        currency = "EUR",
        date = FIXED_NOW,
        items = emptyList(),
        orderNumber = "ORD-1",
        confidence = 0.9
    )

    private suspend fun ingest() = service.processEmailReceipt(
        emailBody = sentinelBody,
        sender = sentinelSender,
        subject = sentinelSubject,
        receivedAt = FIXED_NOW,
        messageId = sentinelMessageId
    )

    /** Every non-exception event field, serialized to one string for sentinel scanning. */
    private fun serialize(e: DiagnosticEvent): String = listOf(
        e.eventId, e.pipeline.name, e.stage, e.outcome.name, e.severity.name,
        e.reasonCode?.name, e.entityType, e.entityId, e.sourceType, e.sourceIdHash,
        e.correlationId, e.causationId, e.metadata.toJson(), e.elapsedMs, e.isTerminal
    ).joinToString("|")

    private fun assertNoSentinels(events: List<DiagnosticEvent>) {
        assertTrue("expected at least one event", events.isNotEmpty())
        val sentinels = listOf(
            "SENTINEL_SENDER_9k", "SENTINEL_SUBJECT_9k", "SENTINEL_BODY_9k",
            "SENTINEL_MSGID_9k", "SENTINEL_MERCHANT_9k", "SENTINEL_PATH_9k", "x.example"
        )
        events.forEach { e ->
            val text = serialize(e)
            sentinels.forEach { s ->
                assertFalse("sentinel $s leaked in stage=${e.stage}: $text", text.contains(s))
            }
        }
    }

    private fun assertMetadataWithinAllowList(events: List<DiagnosticEvent>) {
        events.forEach { e ->
            val keys = JSONObject(e.metadata.toJson()).keys().asSequence().toSet()
            assertTrue(
                "stage=${e.stage} has keys outside allow-list: $keys",
                allowedMetadataKeys.containsAll(keys)
            )
            assertTrue("reasonCode ${e.reasonCode} not in fixed set", e.reasonCode in allowedReasonCodes)
            assertTrue(e.pipeline == AppPipeline.EMAIL)
        }
    }

    @Test
    fun `front door RECEIVED event hashes messageId and sender to 16 hex and never carries raw values`() = runTest {
        parsersReturn(null)

        ingest()

        val received = writer.events.single { it.outcome == EventOutcome.RECEIVED }
        assertEquals("front_door", received.stage)
        assertEquals("email", received.sourceType)
        val json = JSONObject(received.metadata.toJson())
        assertEquals(setOf("messageId", "sender"), json.keys().asSequence().toSet())
        assertTrue(json.getString("messageId").matches(hex16))
        assertTrue(json.getString("sender").matches(hex16))
        assertTrue(json.getString("messageId") != sentinelMessageId)
        assertTrue(json.getString("sender") != sentinelSender)
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
    }

    @Test
    fun `provider detection event carries only a fixed provider token`() = runTest {
        parsersReturn(null)

        ingest()

        val detection = writer.events.single { it.stage == "provider_detection" }
        val json = JSONObject(detection.metadata.toJson())
        assertEquals(setOf("provider"), json.keys().asSequence().toSet())
        assertTrue(json.getString("provider") in allowedProviders)
    }

    @Test
    fun `parser failure emits PARSER_FAILED with no sentinel and no free text`() = runTest {
        parsersReturn(null)

        val result = ingest()

        assertTrue(result is EmailReceiptResult.ParseError)
        val parserEvent = writer.events.single { it.stage == "parser" }
        assertEquals(EventOutcome.FAILED_FINAL, parserEvent.outcome)
        assertEquals(DiagnosticReasonCode.PARSER_FAILED, parserEvent.reasonCode)
        assertTrue(parserEvent.isTerminal)
        assertNull(parserEvent.exception)
        assertTrue(parserEvent.metadata.isEmpty())
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
    }

    @Test
    fun `validation failure emits VALIDATION_FAILED and does not leak the parsed merchant`() = runTest {
        parsersReturn(sentinelReceipt().copy(amount = 0.0))

        val result = ingest()

        assertTrue(result is EmailReceiptResult.ParseError)
        val validation = writer.events.single { it.stage == "validation" }
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, validation.reasonCode)
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
    }

    @Test
    fun `coordinator Error emits fixed UNKNOWN_ERROR with only the retryable flag`() = runTest {
        parsersReturn(sentinelReceipt())
        val passedMessageId = slot<String>()
        coEvery {
            coordinator.processEmailReceipt(any(), any(), any(), any(), any(), capture(passedMessageId), any(), any())
        } returns EmailReceiptProcessResult.Error(sentinelPath + " " + sentinelBody)

        val result = ingest()

        assertEquals(EmailReceiptResult.ParseError("PROCESSING_FAILED"), result)
        val coordinatorEvent = writer.events.single { it.stage == "coordinator" }
        assertEquals(EventOutcome.FAILED_FINAL, coordinatorEvent.outcome)
        assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR, coordinatorEvent.reasonCode)
        assertEquals(setOf("retryable"), JSONObject(coordinatorEvent.metadata.toJson()).keys().asSequence().toSet())
        assertNull(coordinatorEvent.exception)
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
        // PRIV-FB58-01: the coordinator receives a hash, never the raw message id.
        assertTrue(passedMessageId.captured != sentinelMessageId)
        assertFalse(passedMessageId.captured.contains("SENTINEL_MSGID_9k"))
    }

    @Test
    fun `thrown exception with sentinel path emits hashed sourceIdHash and no sentinel in non-exception fields`() = runTest {
        parsersReturn(sentinelReceipt())
        coEvery {
            coordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("cannot open $sentinelPath for $sentinelBody")

        val result = ingest()

        assertEquals(EmailReceiptResult.ParseError("Processing error"), result)
        val ingestion = writer.events.single { it.stage == "ingestion" }
        assertEquals(EventOutcome.FAILED_FINAL, ingestion.outcome)
        assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR, ingestion.reasonCode)
        assertNotNull(ingestion.sourceIdHash)
        assertTrue(ingestion.sourceIdHash!!.matches(hex16))
        assertTrue(ingestion.sourceIdHash != sentinelMessageId)
        // The raw Throwable travels to the writer; only its class is a safe derived field here.
        assertEquals("IllegalStateException", ingestion.exception!!::class.java.simpleName)
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
    }

    @Test
    fun `cancellation from the coordinator is rethrown and no ingestion failure event is emitted`() = runTest {
        parsersReturn(sentinelReceipt())
        coEvery {
            coordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        try {
            ingest()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected: never swallowed
        }

        assertTrue(writer.events.none { it.stage == "ingestion" })
        assertNoSentinels(writer.events)
    }

    @Test
    fun `duplicate and success outcomes stay within the allow-list and leak nothing`() = runTest {
        parsersReturn(sentinelReceipt())
        coEvery {
            coordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns EmailReceiptProcessResult.Duplicate(existingReceiptId = 7L)

        ingest()

        val dedupe = writer.events.single { it.stage == "dedupe" }
        assertEquals(DiagnosticReasonCode.DUPLICATE, dedupe.reasonCode)
        assertEquals("receipt", dedupe.entityType)
        assertEquals(7L, dedupe.entityId)
        assertNoSentinels(writer.events)
        assertMetadataWithinAllowList(writer.events)
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private companion object {
        const val FIXED_NOW = 1_730_000_000_000L
    }
}
