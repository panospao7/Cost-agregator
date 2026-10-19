package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScannedReceiptDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ScannedReceiptDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.scannedReceiptDao()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private fun makeReceipt(
        rawOcrText: String = "TOTAL 12.50",
        parsedMerchant: String = "Test Store",
        createdAt: Long = BASE_NOW
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
        date: Long = BASE_NOW
    ) = Expense(
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    @Test
    fun insertScannedReceipt_andRetrieve() = runTest {
        val receipt = makeReceipt(rawOcrText = "TOTAL 9.99", parsedMerchant = "My Market")
        val id = dao.insert(receipt)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("TOTAL 9.99", loaded!!.rawOcrText)
        assertEquals("My Market", loaded.parsedMerchant)
    }

    @Test
    fun queryReceiptsByDateRange_returnsExpectedWindow() = runTest {
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
    fun updateOcrText_persistsChange() = runTest {
        val id = dao.insert(makeReceipt(rawOcrText = "TOTAL ???"))
        val original = dao.getById(id)!!

        dao.update(original.copy(rawOcrText = "TOTAL 25.40"))

        val updated = dao.getById(id)
        assertNotNull(updated)
        assertEquals("TOTAL 25.40", updated!!.rawOcrText)
    }

    @Test
    fun deleteReceipt_verifiesRemoved() = runTest {
        val id = dao.insert(makeReceipt())
        val inserted = dao.getById(id)!!

        dao.delete(inserted)

        assertNull(dao.getById(id))
        assertEquals(0, dao.getCount())
    }

    // ── B.4 Batch 9: receipt status lifecycle after linking ───────────────

    @Test
    fun linkToExpense_transitionsMatchStatusToAutoMatched() = runTest {
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
    fun linkToExpense_doesNotAppearInUnmatchedResults() = runTest {
        val expenseId = expenseDao.insert(makeExpense())
        val linkedId = dao.insert(makeReceipt(rawOcrText = "linked"))
        val unmatchedId = dao.insert(makeReceipt(rawOcrText = "still unmatched"))

        dao.linkToExpense(linkedId, expenseId)

        val unmatched = dao.getUnmatchedReceipts()
        assertEquals(1, unmatched.size)
        assertEquals("still unmatched", unmatched[0].rawOcrText)
    }

    @Test
    fun newReceipt_defaultsToUnmatched() = runTest {
        val id = dao.insert(makeReceipt())
        val receipt = dao.getById(id)!!
        assertEquals(MatchStatus.UNMATCHED, receipt.matchStatus)
    }

    private companion object {
        /** Fixed base instant (2026-05-15 12:00 UTC); replaces the wall clock. */
        val BASE_NOW: Long = dateMs(2026, 5, 15, 12, 0)
    }
}
