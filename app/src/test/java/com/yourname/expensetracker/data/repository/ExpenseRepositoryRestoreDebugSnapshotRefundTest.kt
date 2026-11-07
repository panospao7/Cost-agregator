package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.lifecycle.DebugExpenseAuditWriter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.every
import io.mockk.mockk
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
 * Refund netting S3, INV-1 on the debug restore path: [ExpenseRepository.restoreDebugSnapshot]
 * deletes every expense (which cascades the refund links away) and re-inserts the snapshot rows, so
 * a snapshotted `refundNettedAmount` would no longer equal the sum of its (now absent) links. The
 * restored rows must carry 0.0. Real in-memory Room database and real transaction; only the
 * diagnostics sink is a mock. Synthetic data only.
 *
 * Unit tests run against the debug build type, where the debug-only operation is allowed.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseRepositoryRestoreDebugSnapshotRefundTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExpenseRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        val maintenance = mockk<RestoreMaintenanceMode>()
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenance.isWritesAllowed() } returns true
        val barrier = DatabaseWriteBarrier(maintenance)
        val time = FakeTimeProvider(1_700_000_000_000L)
        repository = ExpenseRepository(
            writeBarrier = barrier,
            database = db,
            expenseDao = db.expenseDao(),
            userCorrectionDao = db.userCorrectionDao(),
            pendingReviewDao = db.pendingReviewDao(),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk(relaxed = true),
            debugExpenseAuditWriter = DebugExpenseAuditWriter(
                transactionEventDao = db.transactionEventDao(),
                diagnosticEventWriter = mockk(relaxed = true),
                timeProvider = time,
                writeBarrier = barrier
            ),
            timeProvider = time
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seedNettedPair(): Pair<Long, Long> {
        val purchaseId = db.expenseDao().insert(
            Expense(
                amount = 100.0,
                currency = "EUR",
                merchant = "Synthetic Store",
                merchantKey = "synthetic store",
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L,
                refundNettedAmount = 40.0
            )
        )
        val refundId = db.expenseDao().insert(
            Expense(
                amount = 40.0,
                currency = "EUR",
                merchant = "Synthetic Store",
                merchantKey = "synthetic store",
                transactionType = TransactionType.DEPOSIT,
                date = 1_700_086_400_000L,
                refundNettedAmount = 40.0
            )
        )
        assertTrue(purchaseId > 0L && refundId > 0L)
        val linkId = db.expenseRefundLinkDao().insert(
            ExpenseRefundLink(
                refundExpenseId = refundId,
                purchaseExpenseId = purchaseId,
                appliedAmount = 40.0,
                currency = "EUR",
                createdAt = 1_700_086_400_000L,
                source = "USER_CONFIRMED"
            )
        )
        assertTrue(linkId > 0L)
        return purchaseId to refundId
    }

    @Test
    fun `INV-1 restoreDebugSnapshot re-inserts netted rows with refundNettedAmount zero and keeps every other field`() = runTest {
        val (purchaseId, refundId) = seedNettedPair()
        val snapshot = repository.createDebugSnapshot()
        assertEquals(
            "fixture: the snapshot carries the netted amounts",
            listOf(40.0, 40.0),
            snapshot.expenses.map { it.refundNettedAmount }
        )

        repository.restoreDebugSnapshot(snapshot)

        val purchase = db.expenseDao().getById(purchaseId)
        val refund = db.expenseDao().getById(refundId)
        assertNotNull(purchase)
        assertNotNull(refund)
        assertEquals(0.0, purchase!!.refundNettedAmount, 0.0)
        assertEquals(0.0, refund!!.refundNettedAmount, 0.0)
        assertEquals(100.0, purchase.amount, 0.0)
        assertEquals("Synthetic Store", purchase.merchant)
        assertEquals(TransactionType.PURCHASE, purchase.transactionType)
        assertEquals(40.0, refund.amount, 0.0)
        assertEquals(TransactionType.DEPOSIT, refund.transactionType)
    }

    @Test
    fun `INV-1 after restoreDebugSnapshot no link rows remain and the column equals the empty link sum`() = runTest {
        seedNettedPair()
        val snapshot = repository.createDebugSnapshot()

        repository.restoreDebugSnapshot(snapshot)

        assertEquals(0, db.expenseRefundLinkDao().countAll())
        val restored = db.expenseDao().getAllUncapped()
        assertEquals(2, restored.size)
        assertTrue(restored.all { it.refundNettedAmount == 0.0 })
    }

    @Test
    fun `INV-1 control restoreDebugSnapshot of rows that were never netted keeps them at zero`() = runTest {
        val id = db.expenseDao().insert(
            Expense(
                amount = 25.0,
                currency = "EUR",
                merchant = "Plain Synthetic",
                merchantKey = "plain synthetic",
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L
            )
        )
        val snapshot = repository.createDebugSnapshot()

        repository.restoreDebugSnapshot(snapshot)

        assertEquals(0.0, db.expenseDao().getById(id)!!.refundNettedAmount, 0.0)
        assertEquals(25.0, db.expenseDao().getById(id)!!.amount, 0.0)
    }

    @Test
    fun `INV-1 restoreDebugSnapshot of an empty snapshot leaves an empty table and no links`() = runTest {
        seedNettedPair()

        repository.restoreDebugSnapshot(ExpenseRepository.DebugExpenseSnapshot(expenses = emptyList()))

        assertEquals(0, db.expenseDao().getAllUncapped().size)
        assertEquals(0, db.expenseRefundLinkDao().countAll())
    }
}
