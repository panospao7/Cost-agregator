package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
