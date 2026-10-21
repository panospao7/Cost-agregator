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
import org.junit.Assert.fail
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
    // Test 3: Receipt linked to expense (DIRECT_SAVE and AUTO_MATCHED link types)
    // ─────────────────────────────────────────────────────────────────────────

    private class LinkRow(
        val name: String,
        val imagePath: String,
        val merchant: String,
        val amount: Double,
        val receiptConfidence: Float,
        val linkType: String,
        val linkConfidence: Float,
        val createdBy: String
    )

    @Test
    fun `receipt linked to expense persists link and keeps both parent rows`() = runTest {
        val now = 1714514400000L
        val rows = listOf(
            LinkRow("DIRECT_SAVE", "file:///tmp/receipt_003.jpg", "Public", 89.99, 0.90f, "DIRECT_SAVE", 1.0f, "system:test"),
            LinkRow("AUTO_MATCHED", "file:///tmp/receipt_004.jpg", "Zara", 55.00, 0.88f, "AUTO_MATCHED", 0.95f, "system:matcher")
        )
        assertEquals("link rows size", 2, rows.size)

        // Each row inserts its own receipt, expense and link and every assertion is scoped to
        // that row's generated ids, so rows cannot interfere with one another in the shared DB.
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                // GIVEN: a scanned receipt AND an expense
                val receiptId = db.scannedReceiptDao().insert(
                    ScannedReceipt(
                        imagePath = row.imagePath,
                        rawOcrText = "${row.merchant}\nTotal: ${row.amount}",
                        parsedTotal = row.amount,
                        parsedMerchant = row.merchant,
                        parsedDate = now,
                        parsedItems = null,
                        parsedTaxAmount = null,
                        currency = "EUR",
                        confidence = row.receiptConfidence,
                        sourceType = "CAMERA",
                        documentType = "RETAIL_RECEIPT",
                        processingStatus = "PARSED",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                assertTrue("${row.name}: receiptId should be positive", receiptId > 0L)

                val expenseId = db.expenseDao().insert(
                    Expense(
                        amount = row.amount,
                        currency = "EUR",
                        merchant = row.merchant,
                        transactionType = TransactionType.PURCHASE,
                        date = now,
                        categoryId = shoppingCategoryId,
                        source = "receipt_scan",
                        createdAt = now
                    )
                )
                assertTrue("${row.name}: expenseId should be positive", expenseId > 0L)

                // WHEN: insert a ReceiptExpenseLink
                val linkId = db.receiptExpenseLinkDao().insert(
                    ReceiptExpenseLink(
                        receiptId = receiptId,
                        expenseId = expenseId,
                        linkType = row.linkType,
                        confidence = row.linkConfidence,
                        source = "RECEIPT_SCAN",
                        createdAt = now,
                        createdBy = row.createdBy,
                        isPrimary = true
                    )
                )
                assertTrue("${row.name}: linkId should be positive", linkId > 0L)

                // THEN: link exists connecting receiptId and expenseId (both directions)
                val linksForReceipt = db.receiptExpenseLinkDao().getLinksForReceipt(receiptId)
                assertEquals("${row.name}: links for receipt size", 1, linksForReceipt.size)
                assertEquals("${row.name}: link receiptId", receiptId, linksForReceipt[0].receiptId)
                assertEquals("${row.name}: link expenseId", expenseId, linksForReceipt[0].expenseId)
                assertEquals("${row.name}: link type", row.linkType, linksForReceipt[0].linkType)

                val linksForExpense = db.receiptExpenseLinkDao().getLinksForExpense(expenseId)
                assertEquals("${row.name}: links for expense size", 1, linksForExpense.size)
                assertEquals("${row.name}: expense-side link receiptId", receiptId, linksForExpense[0].receiptId)

                // AND: both parent rows still exist after linking (no orphaned state)
                val savedReceipt = db.scannedReceiptDao().getById(receiptId)
                assertNotNull("${row.name}: receipt should still exist", savedReceipt)
                assertEquals("${row.name}: receipt merchant", row.merchant, savedReceipt!!.parsedMerchant)

                val savedExpense = db.expenseDao().getById(expenseId)
                assertNotNull("${row.name}: expense should still exist", savedExpense)
                assertEquals("${row.name}: expense merchant", row.merchant, savedExpense!!.merchant)
                assertEquals("${row.name}: expense amount", row.amount, savedExpense.amount, 0.001)
            } catch (e: AssertionError) {
                failures += "${row.name}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString(separator = "\n"))
    }
}
