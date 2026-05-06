package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for the email receipt pipeline.
 *
 * These tests verify that [EmailReceiptSource] entities can be inserted,
 * linked to [ScannedReceipt] rows, and queried through their DAOs,
 * including deduplication by fingerprint and email-message ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmailReceiptPipelineScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val now = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Insert email receipt source and verify stored fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `email receipt source inserted and queryable`() = runTest {
        // GIVEN: a ScannedReceipt to satisfy the FK constraint
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/email_receipt_001.pdf",
                rawOcrText = "Amazon Invoice #INV-123\nTotal: 49.99",
                parsedTotal = 49.99,
                parsedMerchant = "Amazon",
                parsedDate = now,
                currency = "EUR",
                confidence = 0.97f,
                sourceType = "EMAIL",
                documentType = "INVOICE",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now,
                parsedItems = null,
                parsedTaxAmount = null
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        // AND: an EmailReceiptSource pointing to the receipt
        val source = EmailReceiptSource(
            receiptId = receiptId,
            emailSender = "order-update@amazon.eu",
            emailSubject = "Your Amazon.in order #INV-123 has been dispatched",
            emailMessageId = "<msg001@amazon.eu>",
            parsedAt = now,
            provider = "amazon",
            confidence = 0.97,
            fingerprint = "amazon_49.99_20240501"
        )

        // WHEN: inserting the email receipt source
        val sourceId = db.emailReceiptDao().insert(source)
        assertTrue("sourceId should be positive", sourceId > 0L)

        // THEN: the source exists with correct fields
        val saved = db.emailReceiptDao().getById(sourceId)
        assertNotNull("EmailReceiptSource should exist in DB", saved)
        assertEquals("receiptId should match", receiptId, saved!!.receiptId)
        assertEquals("emailSender should match", "order-update@amazon.eu", saved.emailSender)
        assertEquals("provider should match", "amazon", saved.provider)
        assertEquals("confidence should match", 0.97, saved.confidence, 0.001)
        assertEquals("fingerprint should match", "amazon_49.99_20240501", saved.fingerprint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Email receipt deduplication by message ID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `email receipt deduplication by message ID via insertOrIgnore`() = runTest {
        // GIVEN: a ScannedReceipt to satisfy the FK constraint
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/email_receipt_002.pdf",
                rawOcrText = "Uber Ride Receipt\nTotal: 12.50",
                parsedTotal = 12.50,
                parsedMerchant = "Uber",
                parsedDate = now,
                currency = "EUR",
                confidence = 0.95f,
                sourceType = "EMAIL",
                documentType = "RECEIPT",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now,
                parsedItems = null,
                parsedTaxAmount = null
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        // AND: first insert of the email source
        val firstId = db.emailReceiptDao().insertOrIgnore(
            EmailReceiptSource(
                receiptId = receiptId,
                emailSender = "uber@uber.com",
                emailSubject = "Your Uber ride receipt",
                emailMessageId = "<msg002@uber.com>",
                parsedAt = now,
                provider = "uber",
                confidence = 0.95,
                fingerprint = "uber_12.50_20240501"
            )
        )
        assertTrue("first insert should succeed (id > 0)", firstId > 0L)

        // WHEN: inserting the same emailMessageId again
        val secondId = db.emailReceiptDao().insertOrIgnore(
            EmailReceiptSource(
                receiptId = receiptId,
                emailSender = "uber@uber.com",
                emailSubject = "Your Uber ride receipt",
                emailMessageId = "<msg002@uber.com>",
                parsedAt = now,
                provider = "uber",
                confidence = 0.95,
                fingerprint = "uber_12.50_20240501"
            )
        )

        // THEN: second insert should return -1 (IGNORE due to unique constraint)
        assertEquals("Duplicate emailMessageId should be ignored", -1L, secondId)

        // AND: only one row exists
        val all = db.emailReceiptDao().getAll()
        assertEquals("Should have exactly 1 email receipt source", 1, all.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Email receipt fingerprint deduplication
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `email receipt queryable by fingerprint`() = runTest {
        // GIVEN: a ScannedReceipt and an EmailReceiptSource with a known fingerprint
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/email_receipt_003.pdf",
                rawOcrText = "Apple Purchase\nTotal: 1.99",
                parsedTotal = 1.99,
                parsedMerchant = "Apple",
                parsedDate = now,
                currency = "EUR",
                confidence = 0.99f,
                sourceType = "EMAIL",
                documentType = "INVOICE",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now,
                parsedItems = null,
                parsedTaxAmount = null
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        val fingerprint = "apple_1.99_20240501"
        db.emailReceiptDao().insert(
            EmailReceiptSource(
                receiptId = receiptId,
                emailSender = "no_reply@apple.com",
                emailSubject = "Your receipt from Apple",
                emailMessageId = "<msg003@apple.com>",
                parsedAt = now,
                provider = "apple",
                confidence = 0.99,
                fingerprint = fingerprint
            )
        )

        // WHEN: querying by fingerprint
        val byFingerprint = db.emailReceiptDao().getByFingerprint(fingerprint)

        // THEN: the source is found with correct provider
        assertNotNull("Should find email receipt by fingerprint", byFingerprint)
        assertEquals("provider should be apple", "apple", byFingerprint!!.provider)
        assertEquals("emailMessageId should match", "<msg003@apple.com>", byFingerprint.emailMessageId)
    }
}
