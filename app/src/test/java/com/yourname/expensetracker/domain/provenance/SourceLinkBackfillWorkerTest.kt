package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR9 — SourceLinkBackfillWorker write barrier and cancellation safety tests.
 *
 * Verifies:
 * - Write barrier is checked per expense (not just once at start)
 * - CancellationException is rethrown, not swallowed
 * - Non-CancellationException errors are caught and counted
 */
class SourceLinkBackfillWorkerTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var sourceLinkDao: EntitySourceLinkDao
    private lateinit var pendingReviewDao: PendingReviewDao
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var rawNotificationDao: RawNotificationDao
    private lateinit var emailReceiptDao: EmailReceiptDao
    private lateinit var receiptExpenseLinkDao: ReceiptExpenseLinkDao
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var writeBarrier: DatabaseWriteBarrier

    @Before
    fun setup() {
        expenseDao = mockk()
        sourceLinkDao = mockk()
        pendingReviewDao = mockk()
        scannedReceiptDao = mockk()
        rawNotificationDao = mockk()
        emailReceiptDao = mockk()
        receiptExpenseLinkDao = mockk()
        timeProvider = FakeTimeProvider(0L)
        writeBarrier = mockk(relaxed = true)

        // Default: all DAOs return empty results so backfillExpense proceeds cleanly
        coEvery { sourceLinkDao.getForExpense(any()) } returns emptyList()
        coEvery { sourceLinkDao.insert(any()) } returns 1L
        coEvery { receiptExpenseLinkDao.getLinksForExpense(any()) } returns emptyList()
        coEvery { pendingReviewDao.getPendingUncapped() } returns emptyList()
    }

    private fun buildWorker(): SourceLinkBackfillWorker {
        return SourceLinkBackfillWorker(
            expenseDao = expenseDao,
            sourceLinkDao = sourceLinkDao,
            pendingReviewDao = pendingReviewDao,
            scannedReceiptDao = scannedReceiptDao,
            rawNotificationDao = rawNotificationDao,
            emailReceiptDao = emailReceiptDao,
            receiptExpenseLinkDao = receiptExpenseLinkDao,
            timeProvider = timeProvider,
            writeBarrier = writeBarrier
        )
    }

    private fun testExpense(id: Long, source: String? = "MANUAL"): Expense {
        return Expense(
            id = id,
            amount = 100.0,
            currency = "EUR",
            merchant = "Test $id",
            transactionType = mockk(relaxed = true),
            date = timeProvider.now(),
            source = source
        )
    }

    // ─── Test 1: Write barrier is checked per expense ───

    @Test
    fun `backfill_observes_write_barrier_per_expense`() = runTest {
        val expense1 = testExpense(1L)
        val expense2 = testExpense(2L)

        coEvery { expenseDao.getAll() } returns listOf(expense1, expense2)

        // Throw DatabaseAccessBlockedException only for expense_2.
        // Note: runBackfill calls checkWritesAllowed("SourceLinkBackfillWorker.runBackfill")
        // before the loop, so we match the exact strings.
        every { writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.runBackfill") } answers { }
        every { writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.expense_1") } answers { }
        every { writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.expense_2") } throws
            DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = DatabaseAccessOperation("SourceLinkBackfillWorker.expense_2"),
                mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
            )

        val worker = buildWorker()
        val result = worker.runBackfill()

        // Verify barrier was checked for both expenses
        coVerify(exactly = 1) {
            writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.expense_1")
        }
        coVerify(exactly = 1) {
            writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.expense_2")
        }

        // sourceLinkDao.getForExpense should be called for expense 1 (barrier passed)
        // but NOT for expense 2 (barrier threw before backfillExpense)
        coVerify(exactly = 1) { sourceLinkDao.getForExpense(1L) }
        coVerify(exactly = 0) { sourceLinkDao.getForExpense(2L) }

        // Result: 1 error (expense 2), both expenses "processed" (counted)
        assertEquals(2, result.totalExpenses)
        assertEquals(2, result.processedExpenses)
        assertEquals(1, result.errors)
    }

    // ─── Test 2: CancellationException is rethrown ───

    @Test
    fun `backfill_rethrows_cancellation_exception`() = runTest {
        val expense1 = testExpense(1L)
        val expense2 = testExpense(2L)

        coEvery { expenseDao.getAll() } returns listOf(expense1, expense2)

        // Throw CancellationException on the first expense
        coEvery { sourceLinkDao.getForExpense(1L) } throws kotlinx.coroutines.CancellationException("Cancelled")

        val worker = buildWorker()

        try {
            worker.runBackfill()
            // Should not reach here
            assertTrue("Expected CancellationException to propagate", false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected: CE rethrown from the loop's catch block
        }

        // Only expense 1 was attempted (CE stopped the loop)
        coVerify(exactly = 1) { sourceLinkDao.getForExpense(1L) }
    }

    // ─── Test 3: Non-CancellationException errors are caught and counted ───

    @Test
    fun `backfill_catches_non_cancellation_exceptions_per_expense`() = runTest {
        val expense1 = testExpense(1L)
        val expense2 = testExpense(2L)
        val expense3 = testExpense(3L)

        coEvery { expenseDao.getAll() } returns listOf(expense1, expense2, expense3)

        // Expense 2 throws RuntimeException during backfill
        coEvery { sourceLinkDao.getForExpense(2L) } throws RuntimeException("DB error on expense 2")

        val worker = buildWorker()
        val result = worker.runBackfill()

        // All three expenses processed (error caught and loop continues)
        assertEquals(3, result.totalExpenses)
        assertEquals(3, result.processedExpenses)
        assertEquals(1, result.errors)

        // Expense 1 and 3 were backfilled normally
        coVerify(exactly = 1) { sourceLinkDao.getForExpense(1L) }
        coVerify(exactly = 1) { sourceLinkDao.getForExpense(3L) }
    }
}
