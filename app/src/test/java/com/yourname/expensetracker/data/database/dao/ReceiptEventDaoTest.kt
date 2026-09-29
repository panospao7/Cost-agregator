package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.domain.receipt.ReceiptOcrCoverage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [ReceiptEventDao] covering insert, query-by-receiptId,
 * and timestamp ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptEventDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ReceiptEventDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.receiptEventDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createEvent(
        receiptId: Long? = 100L,
        sourceType: String = "EMAIL",
        documentType: String = "RECEIPT",
        eventType: String = "RECEIPT_CREATED",
        occurredAt: Long = FIXED_NOW,
        oldStatus: String? = null,
        newStatus: String? = "NEW",
        actor: String? = "test",
        message: String? = "Receipt created",
        metadata: String? = null,
        errorDetails: String? = null
    ): ReceiptEvent = ReceiptEvent(
        receiptId = receiptId,
        sourceType = sourceType,
        documentType = documentType,
        eventType = eventType,
        occurredAt = occurredAt,
        oldStatus = oldStatus,
        newStatus = newStatus,
        actor = actor,
        message = message,
        metadata = metadata,
        errorDetails = errorDetails
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert a RECEIPT_CREATED event`() = runTest {
        val event = createEvent()
        val id = dao.insert(event)

        assertNotNull(id)
        assertEquals(1L, id)
    }

    @Test
    fun `query events by receiptId returns matching events`() = runTest {
        val receiptId = 42L
        val event = createEvent(receiptId = receiptId)
        dao.insert(event)

        val results = dao.getEventsForReceipt(receiptId)

        assertEquals(1, results.size)
        assertEquals("RECEIPT_CREATED", results[0].eventType)
    }

    @Test
    fun `query events by receiptId returns empty list for unknown receipt`() = runTest {
        dao.insert(createEvent(receiptId = 1L))

        val results = dao.getEventsForReceipt(999L)

        assertEquals(0, results.size)
    }

    @Test
    fun `verify ordering by timestamp descending`() = runTest {
        val receiptId = 10L
        val early = createEvent(receiptId = receiptId, occurredAt = FIXED_NOW)
        val late = createEvent(receiptId = receiptId, occurredAt = FIXED_NOW + 2000)

        dao.insert(early)
        dao.insert(late)

        val results = dao.getEventsForReceipt(receiptId)

        assertEquals(2, results.size)
        // Most recent first
        assertEquals(FIXED_NOW + 2000, results[0].occurredAt)
        assertEquals(FIXED_NOW, results[1].occurredAt)
    }

    @Test
    fun `insert multiple events and verify count`() = runTest {
        val receiptId = 20L
        dao.insert(createEvent(receiptId = receiptId, eventType = "RECEIPT_CREATED"))
        dao.insert(createEvent(receiptId = receiptId, eventType = "OCR_COMPLETED"))
        dao.insert(createEvent(receiptId = receiptId, eventType = "EXPENSE_CREATED"))

        val results = dao.getEventsForReceipt(receiptId)

        assertEquals(3, results.size)
    }

    @Test
    fun `events for different receiptIds do not mix`() = runTest {
        dao.insert(createEvent(receiptId = 1L, eventType = "RECEIPT_CREATED"))
        dao.insert(createEvent(receiptId = 2L, eventType = "RECEIPT_CREATED"))
        dao.insert(createEvent(receiptId = 1L, eventType = "OCR_COMPLETED"))

        assertEquals(2, dao.getEventsForReceipt(1L).size)
        assertEquals(1, dao.getEventsForReceipt(2L).size)
    }

    @Test
    fun latestSavedMetadataUsesReceiptEventTypeAndTimestamp() = runTest {
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            occurredAt = FIXED_NOW + 2, metadata = "latest-save"))
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            occurredAt = FIXED_NOW + 1, metadata = "older-save-inserted-later"))
        dao.insert(createEvent(receiptId = 43L, eventType = "RECEIPT_SAVED",
            occurredAt = FIXED_NOW + 3, metadata = "other-receipt"))
        dao.insert(createEvent(receiptId = 42L, eventType = "OCR_COMPLETED",
            occurredAt = FIXED_NOW + 4, metadata = "not-a-save"))
        dao.insert(createEvent(receiptId = null, eventType = "RECEIPT_SAVED",
            occurredAt = FIXED_NOW + 5, metadata = "unowned-event"))

        assertEquals("latest-save", dao.getLatestSavedOcrMetadata(42L))
        assertEquals("other-receipt", dao.getLatestSavedOcrMetadata(43L))
    }

    @Test
    fun latestSavedMetadataBreaksTimestampTiesByEventId() = runTest {
        val first = ReceiptOcrCoverage(1, 2, 1).toSavedMetadata().toJson()
        val second = ReceiptOcrCoverage(2, 2, 0).toSavedMetadata().toJson()
        val firstId = dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            metadata = first))
        val secondId = dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            metadata = second))

        assertTrue(secondId > firstId)
        assertEquals(second, dao.getLatestSavedOcrMetadata(42L))
    }

    @Test
    fun savedMetadataIsAbsentWithoutAnExactSaveEvent() = runTest {
        assertNull(dao.getLatestSavedOcrMetadata(42L))
        dao.insert(createEvent(receiptId = 42L, eventType = "OCR_COMPLETED",
            metadata = ReceiptOcrCoverage(1, 2, 1).toSavedMetadata().toJson()))
        assertNull(dao.getLatestSavedOcrMetadata(42L))
        assertNull(dao.getLatestSavedOcrMetadata(999L))
    }

    @Test
    fun savedMetadataReadIsBoundedWithoutMutatingStoredEvidence() = runTest {
        val metadata = ReceiptOcrCoverage().toSavedMetadata().toJson() +
            " ".repeat(ReceiptOcrCoverage.MAX_SAVED_METADATA_CHARS + 100)
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED", metadata = metadata))

        val projected = assertNotNull(dao.getLatestSavedOcrMetadata(42L))
        assertEquals(ReceiptOcrCoverage.MAX_SAVED_METADATA_CHARS + 1, projected.length)
        assertEquals(metadata.take(ReceiptOcrCoverage.MAX_SAVED_METADATA_CHARS + 1), projected)
        assertNull(ReceiptOcrCoverage.fromSavedMetadata(projected))
        assertEquals(metadata, dao.getEventsForReceipt(42L).single().metadata)
    }

    @Test
    fun newestNullMetadataDoesNotFallBackToOlderPositiveEvidence() = runTest {
        val older = ReceiptOcrCoverage(1, 2, 1).toSavedMetadata().toJson()
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED", metadata = older))
        assertEquals(older, dao.getLatestSavedOcrMetadata(42L))
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            occurredAt = FIXED_NOW + 1, metadata = null))

        assertNull(dao.getLatestSavedOcrMetadata(42L))
        assertEquals(2, dao.getEventsForReceipt(42L).size)
    }

    @Test
    fun retentionRemovesCoverageEvidenceWithoutChangingTheCutoffContract() = runTest {
        val cutoff = FIXED_NOW - 90L * 24 * 60 * 60 * 1000
        val old = ReceiptOcrCoverage(1, 2, 1).toSavedMetadata().toJson()
        val atCutoff = ReceiptOcrCoverage().toSavedMetadata().toJson()
        dao.insert(createEvent(receiptId = 42L, eventType = "RECEIPT_SAVED",
            occurredAt = cutoff - 1, metadata = old))
        dao.insert(createEvent(receiptId = 43L, eventType = "RECEIPT_SAVED",
            occurredAt = cutoff, metadata = atCutoff))
        assertEquals(old, dao.getLatestSavedOcrMetadata(42L))

        assertEquals(1, dao.deleteOlderThan(cutoff))
        assertNull(dao.getLatestSavedOcrMetadata(42L))
        assertEquals(atCutoff, dao.getLatestSavedOcrMetadata(43L))
    }

    // -------------------------------------------------------------------------
    // RP-14 P8-003: receipt-event retention (90d)
    // -------------------------------------------------------------------------

    @Test
    fun `deleteOlderThan removes rows strictly older than the cutoff`() = runTest {
        val cutoff = FIXED_NOW - 90L * 24 * 60 * 60 * 1000
        dao.insert(createEvent(occurredAt = cutoff - 1))
        dao.insert(createEvent(occurredAt = cutoff)) // exactly at cutoff — kept
        dao.insert(createEvent(occurredAt = FIXED_NOW))

        val deleted = dao.deleteOlderThan(cutoff)

        assertEquals(1, deleted)
        assertEquals(2, dao.getEventsForReceipt(100L).size)
    }

    @Test
    fun `deleteOlderThan is idempotent`() = runTest {
        val cutoff = FIXED_NOW - 90L * 24 * 60 * 60 * 1000
        dao.insert(createEvent(occurredAt = cutoff - 1))

        assertEquals(1, dao.deleteOlderThan(cutoff))
        assertEquals(0, dao.deleteOlderThan(cutoff))
    }
}
