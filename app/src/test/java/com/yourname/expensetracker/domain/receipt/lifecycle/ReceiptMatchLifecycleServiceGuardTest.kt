package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P-03-2: [ReceiptMatchLifecycleService.rejectAllSuggestions] and
 * [ReceiptMatchLifecycleService.clearMatchForReceipt] must not touch a receipt that already
 * carries an expense link (receipt.expenseId != null): no row change and no lifecycle event.
 * An unclaimed receipt (expenseId == null) behaves as before.
 *
 * REAL in-memory Room, real DAOs and the real service; only the write barrier and clock are
 * test doubles. Oracles are hand-derived literals.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptMatchLifecycleServiceGuardTest {

    private val fixedNow = 1_710_000_000_000L
    private val createdAt = 1_700_000_000_000L

    private lateinit var database: AppDatabase
    private lateinit var service: ReceiptMatchLifecycleService

    private val receiptDao: ScannedReceiptDao get() = database.scannedReceiptDao()
    private val eventDao: ReceiptEventDao get() = database.receiptEventDao()
    private val expenseDao: ExpenseDao get() = database.expenseDao()

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns fixedNow
        service = ReceiptMatchLifecycleService(
            database = database,
            scannedReceiptDao = receiptDao,
            receiptEventDao = eventDao,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertExpense(): Long = expenseDao.insert(
        Expense(
            amount = 10.0,
            merchant = "Synthetic Merchant",
            transactionType = TransactionType.PURCHASE,
            date = createdAt
        )
    )

    private suspend fun insertReceipt(
        status: MatchStatus,
        expenseId: Long? = null,
        suggestedExpenseId: Long? = null
    ): Long = receiptDao.insert(
        ScannedReceipt(
            imagePath = null,
            rawOcrText = "synthetic-" + System.nanoTime(),
            parsedTotal = 10.0,
            parsedMerchant = "Synthetic Merchant",
            parsedDate = createdAt,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f,
            expenseId = expenseId,
            suggestedExpenseId = suggestedExpenseId,
            matchConfidence = 0.8f,
            matchStatus = status,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    )

    @Test
    fun INV_P032_rejectAllSuggestions_onClaimedReceipt_isNoOp_forEveryStatus() = runTest {
        for (status in MatchStatus.values()) {
            val expenseId = insertExpense()
            val receiptId = insertReceipt(status, expenseId = expenseId)
            val before = receiptDao.getById(receiptId)

            service.rejectAllSuggestions(receiptId)

            assertEquals("row must be unchanged for $status", before, receiptDao.getById(receiptId))
            assertEquals(expenseId, receiptDao.getById(receiptId)!!.expenseId)
            assertTrue("no event for $status", eventDao.getEventsForReceipt(receiptId).isEmpty())
        }
    }

    @Test
    fun INV_P032_clearMatchForReceipt_onClaimedReceipt_isNoOp_forEveryStatus() = runTest {
        for (status in MatchStatus.values()) {
            val expenseId = insertExpense()
            val receiptId = insertReceipt(status, expenseId = expenseId)
            val before = receiptDao.getById(receiptId)

            service.clearMatchForReceipt(receiptId)

            assertEquals("row must be unchanged for $status", before, receiptDao.getById(receiptId))
            assertEquals(expenseId, receiptDao.getById(receiptId)!!.expenseId)
            assertTrue("no event for $status", eventDao.getEventsForReceipt(receiptId).isEmpty())
        }
    }

    @Test
    fun INV_P032_guardKeysOnExpenseId_notOnMatchStatus() = runTest {
        // Inconsistent legacy row: link present but status still UNMATCHED. The link wins.
        val expenseId = insertExpense()
        val receiptId = insertReceipt(MatchStatus.UNMATCHED, expenseId = expenseId)

        service.rejectAllSuggestions(receiptId)
        service.clearMatchForReceipt(receiptId)

        val row = receiptDao.getById(receiptId)!!
        assertEquals(expenseId, row.expenseId)
        assertEquals(MatchStatus.UNMATCHED, row.matchStatus)
        assertEquals(createdAt, row.updatedAt)
        assertTrue(eventDao.getEventsForReceipt(receiptId).isEmpty())
    }

    @Test
    fun INV_P032_rejectAllSuggestions_onUnclaimedReceipt_rejectsAndWritesEvent() = runTest {
        val suggested = insertExpense()
        val receiptId = insertReceipt(MatchStatus.SUGGESTED, suggestedExpenseId = suggested)

        service.rejectAllSuggestions(receiptId)

        val row = receiptDao.getById(receiptId)!!
        assertEquals(MatchStatus.REJECTED, row.matchStatus)
        assertNull(row.suggestedExpenseId)
        assertNull(row.expenseId)
        assertEquals(fixedNow, row.updatedAt)
        val events = eventDao.getEventsForReceipt(receiptId)
        assertEquals(listOf("MATCH_REJECTED"), events.map { it.eventType })
    }

    @Test
    fun INV_P032_clearMatchForReceipt_onUnclaimedReceipt_clearsAndWritesEvent() = runTest {
        val suggested = insertExpense()
        val receiptId = insertReceipt(MatchStatus.SUGGESTED, suggestedExpenseId = suggested)

        service.clearMatchForReceipt(receiptId)

        val row = receiptDao.getById(receiptId)!!
        assertEquals(MatchStatus.UNMATCHED, row.matchStatus)
        assertNull(row.suggestedExpenseId)
        assertNull(row.matchConfidence)
        assertNull(row.expenseId)
        assertEquals(fixedNow, row.updatedAt)
        val events = eventDao.getEventsForReceipt(receiptId)
        assertEquals(listOf("MATCH_CLEARED"), events.map { it.eventType })
    }

    @Test
    fun INV_P032_missingReceipt_isStillANoOp() = runTest {
        service.rejectAllSuggestions(9_999L)
        service.clearMatchForReceipt(9_999L)

        assertTrue(eventDao.getEventsForReceipt(9_999L).isEmpty())
    }
}
