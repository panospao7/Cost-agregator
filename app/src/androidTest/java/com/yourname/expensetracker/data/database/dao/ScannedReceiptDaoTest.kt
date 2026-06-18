package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannedReceiptDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ScannedReceiptDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.scannedReceiptDao()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeReceipt(
        rawOcrText: String = "TOTAL 12.50",
        parsedMerchant: String = "Test Store",
        createdAt: Long = System.currentTimeMillis()
    ) = ScannedReceipt(
        imagePath = null,
        rawOcrText = rawOcrText,
        parsedTotal = 12.50,
        parsedMerchant = parsedMerchant,
        parsedDate = createdAt,
        parsedItems = null,
        parsedTaxAmount = 1.50,
        confidence = 0.92f,
        createdAt = createdAt
    )

    private fun makeExpense(
        amount: Double = 12.50,
        merchant: String = "Test Store",
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    @Test
    fun insertScannedReceipt_andRetrieve() = runBlocking {
        val receipt = makeReceipt(rawOcrText = "TOTAL 9.99", parsedMerchant = "My Market")
        val id = dao.insert(receipt)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("TOTAL 9.99", loaded!!.rawOcrText)
        assertEquals("My Market", loaded.parsedMerchant)
    }

    @Test
    fun queryReceiptsByDateRange_returnsExpectedWindow() = runBlocking {
        val base = 1_700_000_000_000L
        val oldTs = base - 10_000L
        val inRangeTs = base - 1_000L
        val newestTs = base + 1_000L

        dao.insert(makeReceipt(rawOcrText = "old", createdAt = oldTs))
        dao.insert(makeReceipt(rawOcrText = "in-range", createdAt = inRangeTs))
        dao.insert(makeReceipt(rawOcrText = "newest", createdAt = newestTs))

        val results = dao.getRecentReceipts(base - 2_000L)

        assertEquals(2, results.size)
        assertEquals("newest", results[0].rawOcrText)
        assertEquals("in-range", results[1].rawOcrText)
    }

    @Test
    fun updateOcrText_persistsChange() = runBlocking {
        val id = dao.insert(makeReceipt(rawOcrText = "TOTAL ???"))
        val original = dao.getById(id)!!

        dao.update(original.copy(rawOcrText = "TOTAL 25.40"))

        val updated = dao.getById(id)
        assertNotNull(updated)
        assertEquals("TOTAL 25.40", updated!!.rawOcrText)
    }

    @Test
    fun deleteReceipt_verifiesRemoved() = runBlocking {
        val id = dao.insert(makeReceipt())
        val inserted = dao.getById(id)!!

        dao.delete(inserted)

        assertNull(dao.getById(id))
        assertEquals(0, dao.getCount())
    }

    // ── B.4 Batch 9: receipt status lifecycle after linking ───────────────

    @Test
    fun linkToExpense_transitionsMatchStatusToAutoMatched() = runBlocking {
        // Insert parent expense (FK target)
        val expenseId = expenseDao.insert(makeExpense())

        // Insert an unmatched receipt
        val receiptId = dao.insert(makeReceipt())

        // Precondition: receipt starts UNMATCHED
        val before = dao.getById(receiptId)!!
        assertEquals(MatchStatus.UNMATCHED, before.matchStatus)
        assertNull(before.expenseId)

        // Act: link
        dao.linkToExpense(receiptId, expenseId)

        // Assert: status transitions to AUTO_MATCHED and expenseId is set
        val after = dao.getById(receiptId)!!
        assertEquals(MatchStatus.AUTO_MATCHED, after.matchStatus)
        assertEquals(expenseId, after.expenseId)
    }

    @Test
    fun linkToExpense_doesNotAppearInUnmatchedResults() = runBlocking {
        val expenseId = expenseDao.insert(makeExpense())
        val linkedId = dao.insert(makeReceipt(rawOcrText = "linked"))
        val unmatchedId = dao.insert(makeReceipt(rawOcrText = "still unmatched"))

        dao.linkToExpense(linkedId, expenseId)

        val unmatched = dao.getUnmatchedReceipts()
        assertEquals(1, unmatched.size)
        assertEquals("still unmatched", unmatched[0].rawOcrText)
    }

    @Test
    fun newReceipt_defaultsToUnmatched() = runBlocking {
        val id = dao.insert(makeReceipt())
        val receipt = dao.getById(id)!!
        assertEquals(MatchStatus.UNMATCHED, receipt.matchStatus)
    }
}
