package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [ReceiptExpenseLinkDao] covering insert, query by receipt/expense,
 * unlink, and delete-all-links operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptExpenseLinkDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ReceiptExpenseLinkDao
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.receiptExpenseLinkDao()
        scannedReceiptDao = database.scannedReceiptDao()
        expenseDao = database.expenseDao()

        // Pre-insert minimal parent rows so that link inserts (which have FK
        // constraints to scanned_receipts and expenses) do not fail.
        runBlocking {
            for (id in listOf(1L, 2L, 42L)) {
                scannedReceiptDao.insert(
                    ScannedReceipt(
                        id = id,
                        imagePath = null,
                        rawOcrText = "",
                        parsedTotal = null,
                        parsedMerchant = null,
                        parsedDate = null,
                        parsedItems = null,
                        parsedTaxAmount = null,
                        confidence = 0.0f,
                        createdAt = FIXED_NOW
                    )
                )
            }
            for (id in listOf(10L, 20L, 100L)) {
                expenseDao.insert(
                    Expense(
                        id = id,
                        amount = 0.0,
                        merchant = "",
                        transactionType = TransactionType.UNKNOWN,
                        date = FIXED_NOW,
                        createdAt = FIXED_NOW
                    )
                )
            }
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createLink(
        receiptId: Long = 1L,
        expenseId: Long = 10L,
        linkType: String = "AUTO_MATCHED",
        confidence: Float? = 0.95f,
        source: String = "TEST",
        createdAt: Long = FIXED_NOW,
        createdBy: String? = "system",
        isPrimary: Boolean = true,
        metadata: String? = null
    ): ReceiptExpenseLink = ReceiptExpenseLink(
        receiptId = receiptId,
        expenseId = expenseId,
        linkType = linkType,
        confidence = confidence,
        source = source,
        createdAt = createdAt,
        createdBy = createdBy,
        isPrimary = isPrimary,
        metadata = metadata
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `link a receipt to an expense`() = runTest {
        val link = createLink()
        val id = dao.insert(link)

        assertTrue(id > 0)
    }

    @Test
    fun `verify the link exists by receiptId`() = runTest {
        val receiptId = 42L
        val expenseId = 100L
        dao.insert(createLink(receiptId = receiptId, expenseId = expenseId))

        val links = dao.getLinksForReceipt(receiptId)

        assertEquals(1, links.size)
        assertEquals(expenseId, links[0].expenseId)
        assertEquals("AUTO_MATCHED", links[0].linkType)
    }

    @Test
    fun `verify the link exists by expenseId`() = runTest {
        val receiptId = 42L
        val expenseId = 100L
        dao.insert(createLink(receiptId = receiptId, expenseId = expenseId))

        val links = dao.getLinksForExpense(expenseId)

        assertEquals(1, links.size)
        assertEquals(receiptId, links[0].receiptId)
    }

    @Test
    fun `multiple links for same receipt`() = runTest {
        val receiptId = 1L
        dao.insert(createLink(receiptId = receiptId, expenseId = 10L))
        dao.insert(createLink(receiptId = receiptId, expenseId = 20L))

        val links = dao.getLinksForReceipt(receiptId)

        assertEquals(2, links.size)
    }

    @Test
    fun `unlink removes the specific link`() = runTest {
        val receiptId = 1L
        val expenseId = 10L
        dao.insert(createLink(receiptId = receiptId, expenseId = expenseId))

        dao.unlink(receiptId, expenseId)

        val links = dao.getLinksForReceipt(receiptId)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `unlink only removes the targeted pair`() = runTest {
        val receiptId = 1L
        dao.insert(createLink(receiptId = receiptId, expenseId = 10L))
        dao.insert(createLink(receiptId = receiptId, expenseId = 20L))

        dao.unlink(receiptId, 10L)

        val links = dao.getLinksForReceipt(receiptId)
        assertEquals(1, links.size)
        assertEquals(20L, links[0].expenseId)
    }

    @Test
    fun `deleteAllLinksForReceipt removes all links for a receipt`() = runTest {
        val receiptId = 1L
        dao.insert(createLink(receiptId = receiptId, expenseId = 10L))
        dao.insert(createLink(receiptId = receiptId, expenseId = 20L))

        dao.deleteAllLinksForReceipt(receiptId)

        val links = dao.getLinksForReceipt(receiptId)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `deleteAllLinksForReceipt does not affect other receipts`() = runTest {
        dao.insert(createLink(receiptId = 1L, expenseId = 10L))
        dao.insert(createLink(receiptId = 2L, expenseId = 20L))

        dao.deleteAllLinksForReceipt(1L)

        assertTrue(dao.getLinksForReceipt(1L).isEmpty())
        assertEquals(1, dao.getLinksForReceipt(2L).size)
    }

    @Test
    fun `insert with IGNORE strategy does not throw on duplicate unique index`() = runTest {
        dao.insert(createLink(receiptId = 1L, expenseId = 10L))
        // Second insert with same receiptId+expenseId should be ignored (conflict on unique index)
        val id = dao.insert(createLink(receiptId = 1L, expenseId = 10L))

        // IGNORE returns -1 for skipped inserts
        assertEquals(-1L, id)
    }
}
