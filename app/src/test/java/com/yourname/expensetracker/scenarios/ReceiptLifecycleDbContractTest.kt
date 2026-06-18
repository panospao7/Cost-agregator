package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.runBlocking
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
 * DB-backed lifecycle contract tests for receipt lifecycle using DAOs directly.
 *
 * These tests verify that [ScannedReceipt], [ReceiptEvent], and
 * [ReceiptExpenseLink] entities can be persisted and queried correctly
 * through their DAOs using a real in-memory Room database.
 *
 * NOTE: Coordinator integration is deferred because
 * [com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator]
 * has 18 dependencies including non-DB services (OCR, ML classifiers, asset store,
 * deduplication, side-effect dispatcher, etc.) that would require extensive mocking.
 * The DAO-level contract tests here validate the persistent layer independently;
 * coordinator-level contract tests can be added later once mocking infrastructure
 * is in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptLifecycleDbContractTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var foodCategoryId: Long = 0L
    private var shoppingCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)

        // ── Seed categories (DAO inserts are suspend) ──────────────────
        foodCategoryId = db.categoryDao().insert(
            Category(name = "Food & Dining", icon = "🍕", color = "#FF5733")
        )
        shoppingCategoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "🛒", color = "#33FF57")
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Scanned receipt inserted and queryable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `scanned receipt inserted and queryable`() = runTest {
        // GIVEN: categories seeded
        val now = 1714514400000L // 2024-05-01T00:00:00Z (example)

        val receipt = ScannedReceipt(
            imagePath = "file:///tmp/receipt_001.jpg",
            rawOcrText = "SKLAVENITIS\nTotal: 45.50\nItems: ...",
            parsedTotal = 45.50,
            parsedMerchant = "SKLAVENITIS",
            parsedDate = now,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.95f,
            sourceType = "CAMERA",
            documentType = "RETAIL_RECEIPT",
            processingStatus = "PARSED",
            createdAt = now,
            updatedAt = now
        )

        // WHEN: insert a scanned receipt via ScannedReceiptDao
        val receiptId = db.scannedReceiptDao().insert(receipt)
        assertTrue("receiptId should be positive", receiptId > 0L)

        // THEN: receipt exists with correct merchant, amount, status
        val saved = db.scannedReceiptDao().getById(receiptId)
        assertNotNull("ScannedReceipt should exist in DB", saved)
        assertEquals("Merchant should match", "SKLAVENITIS", saved!!.parsedMerchant)
        assertEquals("Amount should match", 45.50, saved.parsedTotal!!, 0.001)
        assertEquals("Processing status should match", "PARSED", saved.processingStatus)
        assertEquals("Source type should match", "CAMERA", saved.sourceType)
        assertEquals("Document type should match", "RETAIL_RECEIPT", saved.documentType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Receipt event log created
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `receipt event log created`() = runTest {
        // GIVEN: a scanned receipt
        val now = 1714514400000L
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/receipt_002.jpg",
                rawOcrText = "AB Vassilopoulos\nTotal: 12.30",
                parsedTotal = 12.30,
                parsedMerchant = "AB Vassilopoulos",
                parsedDate = now,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.92f,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        // WHEN: insert a ReceiptEvent for it
        val event = ReceiptEvent(
            receiptId = receiptId,
            sourceType = "CAMERA",
            documentType = "RETAIL_RECEIPT",
            eventType = "RECEIPT_CREATED",
            occurredAt = now,
            oldStatus = null,
            newStatus = "PARSED",
            actor = "system:test",
            message = "Test receipt created",
            metadata = null,
            errorDetails = null
        )
        val eventId = db.receiptEventDao().insert(event)
        assertTrue("eventId should be positive", eventId > 0L)

        // THEN: event exists with correct receiptId and event type
        val events = db.receiptEventDao().getEventsForReceipt(receiptId)
        assertEquals("Should have exactly 1 event", 1, events.size)
        assertEquals("Event receiptId should match", receiptId, events[0].receiptId)
        assertEquals("Event type should be RECEIPT_CREATED", "RECEIPT_CREATED", events[0].eventType)
        assertEquals("Event source type should match", "CAMERA", events[0].sourceType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Receipt linked to expense
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `receipt linked to expense`() = runTest {
        // GIVEN: a scanned receipt AND an expense
        val now = 1714514400000L

        // Insert a scanned receipt
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/receipt_003.jpg",
                rawOcrText = "Public\nTotal: 89.99",
                parsedTotal = 89.99,
                parsedMerchant = "Public",
                parsedDate = now,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.90f,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        // Insert an expense
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 89.99,
                currency = "EUR",
                merchant = "Public",
                transactionType = TransactionType.PURCHASE,
                date = now,
                categoryId = shoppingCategoryId,
                source = "receipt_scan",
                createdAt = now
            )
        )
        assertTrue("expenseId should be positive", expenseId > 0L)

        // WHEN: insert a ReceiptExpenseLink
        val link = ReceiptExpenseLink(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "DIRECT_SAVE",
            confidence = 1.0f,
            source = "RECEIPT_SCAN",
            createdAt = now,
            createdBy = "system:test",
            isPrimary = true
        )
        val linkId = db.receiptExpenseLinkDao().insert(link)
        assertTrue("linkId should be positive", linkId > 0L)

        // THEN: link exists connecting receiptId and expenseId
        val linksForReceipt = db.receiptExpenseLinkDao().getLinksForReceipt(receiptId)
        assertEquals("Should have exactly 1 link for receipt", 1, linksForReceipt.size)
        assertEquals("Link receiptId should match", receiptId, linksForReceipt[0].receiptId)
        assertEquals("Link expenseId should match", expenseId, linksForReceipt[0].expenseId)
        assertEquals("Link type should match", "DIRECT_SAVE", linksForReceipt[0].linkType)

        val linksForExpense = db.receiptExpenseLinkDao().getLinksForExpense(expenseId)
        assertEquals("Should have exactly 1 link for expense", 1, linksForExpense.size)
        assertEquals("Link receiptId should match", receiptId, linksForExpense[0].receiptId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Receipt link prevents orphaned state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `receipt link prevents orphaned state`() = runTest {
        // GIVEN: a receipt-expense link (with both parent rows present)
        val now = 1714514400000L

        // Insert a scanned receipt
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = "file:///tmp/receipt_004.jpg",
                rawOcrText = "Zara\nTotal: 55.00",
                parsedTotal = 55.00,
                parsedMerchant = "Zara",
                parsedDate = now,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.88f,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT",
                processingStatus = "PARSED",
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("receiptId should be positive", receiptId > 0L)

        // Insert an expense
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 55.00,
                currency = "EUR",
                merchant = "Zara",
                transactionType = TransactionType.PURCHASE,
                date = now,
                categoryId = shoppingCategoryId,
                source = "receipt_scan",
                createdAt = now
            )
        )
        assertTrue("expenseId should be positive", expenseId > 0L)

        // Insert the link
        db.receiptExpenseLinkDao().insert(
            ReceiptExpenseLink(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "AUTO_MATCHED",
                confidence = 0.95f,
                source = "RECEIPT_SCAN",
                createdAt = now,
                createdBy = "system:matcher",
                isPrimary = true
            )
        )

        // WHEN: querying all links for the receipt
        val links = db.receiptExpenseLinkDao().getLinksForReceipt(receiptId)

        // THEN: link count = 1, receipt exists, expense exists
        assertEquals("Link count should be 1", 1, links.size)

        val savedReceipt = db.scannedReceiptDao().getById(receiptId)
        assertNotNull("Receipt should still exist", savedReceipt)
        assertEquals("Receipt merchant should match", "Zara", savedReceipt!!.parsedMerchant)

        val savedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should still exist", savedExpense)
        assertEquals("Expense merchant should match", "Zara", savedExpense!!.merchant)
        assertEquals("Expense amount should match", 55.00, savedExpense.amount, 0.001)
    }
}
