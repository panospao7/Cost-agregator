package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * DB-backed tests for the P9-P1-08 diagnostics writers on
 * [ReceiptMatchLifecycleService].
 *
 * Each writer is exercised through a real in-memory Room database (matching the
 * [com.yourname.expensetracker.data.database.dao.ReceiptEventDaoTest] convention)
 * with a relaxed-but-stubbed [DatabaseWriteBarrier] and a fixed [TimeProvider],
 * asserting that a [com.yourname.expensetracker.data.database.entity.ReceiptEvent]
 * row with the correct eventType and receiptId is persisted via the real DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptMatchLifecycleServiceTest {

    private lateinit var database: AppDatabase
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var service: ReceiptMatchLifecycleService

    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        scannedReceiptDao = database.scannedReceiptDao()
        receiptEventDao = database.receiptEventDao()
        every { timeProvider.now() } returns FIXED_NOW
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        service = ReceiptMatchLifecycleService(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            receiptEventDao = receiptEventDao,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertReceipt(
        documentType: String = "RETAIL_RECEIPT",
        processingStatus: String = "PARSED",
        sourceType: String = "CAMERA"
    ): Long {
        return scannedReceiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "sample",
                parsedTotal = 12.34,
                parsedMerchant = "Store",
                parsedDate = FIXED_NOW,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.9f,
                sourceType = sourceType,
                documentType = documentType,
                processingStatus = processingStatus,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW
            )
        )
    }

    @Test
    fun `recordMatchAttempted writes a MATCH_ATTEMPTED event`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchAttempted(receiptId, lookbackDays = 7)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_ATTEMPTED", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals(FIXED_NOW, events[0].occurredAt)
        assertEquals("system:match_lifecycle", events[0].actor)
    }

    @Test
    fun `recordMatchNotFound writes a MATCH_NOT_FOUND event`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchNotFound(receiptId)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_NOT_FOUND", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
    }

    @Test
    fun `recordMatchSkippedDocumentType writes a MATCH_SKIPPED_DOCUMENT_TYPE event`() = runTest {
        val receiptId = insertReceipt(documentType = "BANK_STATEMENT")

        service.recordMatchSkippedDocumentType(receiptId, documentType = "BANK_STATEMENT")

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_SKIPPED_DOCUMENT_TYPE", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals("BANK_STATEMENT", events[0].documentType)
    }

    @Test
    fun `recordAutoMatchLinkFailed writes an AUTO_MATCH_LINK_FAILED event with reason`() = runTest {
        val receiptId = insertReceipt()

        service.recordAutoMatchLinkFailed(
            receiptId = receiptId,
            expenseId = 555L,
            reason = "RECEIPT_ALREADY_LINKED"
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals("RECEIPT_ALREADY_LINKED", events[0].errorDetails)
        assertTrue(events[0].message!!.contains("555"))
    }

    @Test
    fun `diagnostics writers check the write barrier`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchAttempted(receiptId, lookbackDays = 7)

        io.mockk.verify {
            writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordMatchAttempted")
        }
    }

    @Test
    fun `diagnostics writers no-op for unknown receipt`() = runTest {
        // No receipt inserted; getById returns null -> transaction returns early.
        service.recordMatchAttempted(999L, lookbackDays = 7)
        service.recordMatchNotFound(999L)
        service.recordMatchSkippedDocumentType(999L, documentType = "BANK_STATEMENT")
        service.recordAutoMatchLinkFailed(999L, expenseId = 1L, reason = "x")

        assertEquals(0, receiptEventDao.getEventsForReceipt(999L).size)
    }

    @Test
    fun `recordAutoMatchLinkFailed tolerates null expenseId`() = runTest {
        val receiptId = insertReceipt()

        service.recordAutoMatchLinkFailed(receiptId, expenseId = null, reason = null)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        // Null reason is sanitized to the fallback diagnostic code
        assertEquals("WORKER_UNHANDLED_EXCEPTION", events[0].errorDetails)
    }

    // ── PR12M-1: defensive sanitization boundaries ───────────────────────────

    @Test
    fun `recordAutoMatchLinkFailed_sanitizes_raw_reason`() = runTest {
        val receiptId = insertReceipt()

        // Pass a path-like string that is NOT a valid reason code;
        // it must be sanitized to the fallback code.
        service.recordAutoMatchLinkFailed(
            receiptId = receiptId,
            expenseId = 555L,
            reason = "C:\\Users\\foo\\receipt.txt",
            errorClass = "java.lang.RuntimeException"
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        // The path-like reason is replaced by the fallback diagnostic code
        assertEquals("code=WORKER_UNHANDLED_EXCEPTION, class=java.lang.RuntimeException", events[0].errorDetails)
    }

    @Test
    fun `recordNotificationSuppressed_sanitizes_reason_code`() = runTest {
        val receiptId = insertReceipt()

        // Pass an invalid reason code with characters that are not
        // alphanumeric or underscore; it must be sanitized.
        service.recordNotificationSuppressed(
            receiptId = receiptId,
            expenseId = 555L,
            reasonCode = "some invalid code with spaces and \n newlines",
            errorClass = null
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("NOTIFICATION_SUPPRESSED", events[0].eventType)
        // The invalid reason code is sanitized to the fallback code
        assertEquals("WORKER_UNHANDLED_EXCEPTION", events[0].errorDetails)
        // The message also uses the sanitized value (not the raw input)
        assertTrue(events[0].message!!.contains("WORKER_UNHANDLED_EXCEPTION"))
        assertFalse(events[0].message!!.contains("some invalid code"))
    }
}
