package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy contract for [RoomReceiptLifecycleEventWriter]: `receipt_events.errorDetails` is a
 * bounded diagnostic code field. Free text (exception messages, paths, OCR/receipt text) handed to
 * the writer must never reach the stored row; code-shaped values (reason codes, "code=X, class=Y")
 * are kept.
 */
class ReceiptLifecycleEventWriterPrivacyTest {

    private val dao = mockk<ReceiptEventDao>(relaxed = true)
    private val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val writer = RoomReceiptLifecycleEventWriter(
        dao,
        EventMetadataSanitizer(),
        FakeTimeProvider(1_716_163_200_000L),
        barrier
    )

    private fun context() = TransactionContext(
        correlationId = "corr-1",
        occurredAt = 1_716_163_200_000L,
        source = "test"
    )

    private suspend fun storedFor(errorDetails: String?): ReceiptEvent {
        val slot = slot<ReceiptEvent>()
        coEvery { dao.insert(capture(slot)) } returns 1L
        writer.write(
            context(),
            ReceiptLifecycleEvent(
                receiptId = 1L,
                sourceType = "TEST",
                documentType = "RECEIPT",
                eventType = "TEST_EVENT",
                errorDetails = errorDetails
            )
        )
        return slot.captured
    }

    @Test
    fun `exception message text in errorDetails is never stored`() = runTest {
        val messages = listOf(
            "secret-789",
            "secret-789 failed at /data/user/0/com.app/files/receipt.jpg",
            "java.io.IOException: secret-789",
            "x".repeat(500)
        )
        for (message in messages) {
            val stored = storedFor(message)

            assertNull("[$message] errorDetails must be dropped", stored.errorDetails)
            val allFields = listOfNotNull(stored.message, stored.metadata, stored.errorDetails, stored.actor)
            assertTrue("[$message] no stored field may contain the secret", allFields.none { it.contains("secret-789") })
        }
    }

    @Test
    fun `code shaped errorDetails is preserved`() = runTest {
        assertEquals("RECEIPT_ALREADY_LINKED", storedFor("RECEIPT_ALREADY_LINKED").errorDetails)
        assertEquals(
            "code=WORKER_UNHANDLED_EXCEPTION, class=java.lang.RuntimeException",
            storedFor("code=WORKER_UNHANDLED_EXCEPTION, class=java.lang.RuntimeException").errorDetails
        )
        assertNull(storedFor(null).errorDetails)
        coVerify(atLeast = 1) { dao.insert(any<ReceiptEvent>()) }
    }

    // Only UPPER_SNAKE reason codes pass; lowercase identifiers, file names, names, numbers, IBAN-like
    // values and over-long input are dropped. A single uppercase word still passes (accepted; an
    // explicit allowlist would be the next step).
    @Test
    fun `identifier-like or personal-data-like errorDetails is dropped`() = runTest {
        val values = listOf(
            "transaction_matching_exception",
            "receipt_2024.pdf",
            "John.Smith",
            "12.50",
            "4111111111111111",
            "GR1601101250000000012300695",
            "message=receipt_2024.pdf",
            "A".repeat(200)
        )
        for (value in values) {
            assertNull("[$value] errorDetails must be dropped", storedFor(value).errorDetails)
        }
    }
}
