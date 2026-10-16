package com.yourname.expensetracker.domain

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent
import com.yourname.expensetracker.domain.receipt.lifecycle.RoomReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.DebugExpenseAuditWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.RoomTransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * U-004 (batch G3 / slice S1): the receipt/transaction lifecycle event writers, the debug expense
 * audit writer and the receipt insert resolver are regular DB writers and must consult
 * [DatabaseWriteBarrier] immediately before each DAO write.
 *
 * Contract under test, per writer:
 *  - barrier open: exactly one DAO write;
 *  - barrier blocked: [DatabaseAccessBlockedException] and the DAO is never called;
 *  - [CancellationException] from the barrier is never swallowed.
 */
class LifecycleWritersWriteBarrierTest {

    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } answers { currentMode }
    }
    private val barrier = DatabaseWriteBarrier(maintenanceMode)
    private val timeProvider = FakeTimeProvider(1_716_163_200_000L)

    private val receiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val receiptEventDao = mockk<ReceiptEventDao>(relaxed = true)
    private val transactionEventDao = mockk<TransactionEventDao>(relaxed = true)
    private val diagnosticWriter = mockk<DiagnosticEventWriter>(relaxed = true)

    private fun block() {
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
    }

    private fun assertBlocked(e: DatabaseAccessBlockedException) {
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, e.mode)
    }

    private fun context() = TransactionContext(
        correlationId = "corr-1",
        occurredAt = 1_716_163_200_000L,
        source = "test"
    )

    private fun receiptEvent() = ReceiptLifecycleEvent(
        receiptId = 1L,
        sourceType = "TEST",
        documentType = "RECEIPT",
        eventType = "TEST_EVENT"
    )

    private fun transactionEvent() = TransactionLifecycleEvent(
        expenseId = 1L,
        eventType = "TEST_EVENT",
        source = "TEST"
    )

    private fun receiptWriter(writeBarrier: DatabaseWriteBarrier = barrier) =
        RoomReceiptLifecycleEventWriter(receiptEventDao, EventMetadataSanitizer(), timeProvider, writeBarrier)

    private fun transactionWriter(writeBarrier: DatabaseWriteBarrier = barrier) =
        RoomTransactionLifecycleEventWriter(transactionEventDao, EventMetadataSanitizer(), timeProvider, writeBarrier)

    private fun auditWriter(writeBarrier: DatabaseWriteBarrier = barrier) =
        DebugExpenseAuditWriter(transactionEventDao, diagnosticWriter, timeProvider, writeBarrier)

    private fun resolver(writeBarrier: DatabaseWriteBarrier = barrier) =
        ReceiptInsertResolver(receiptDao, writeBarrier)

    private fun cancellingBarrier(cancellation: CancellationException): DatabaseWriteBarrier {
        val cancelling = mockk<DatabaseWriteBarrier>()
        every { cancelling.checkWritesAllowed(any<String>()) } throws cancellation
        return cancelling
    }

    // -- ReceiptInsertResolver ------------------------------------------------

    @Test
    fun `receipt insert resolver inserts once when barrier is open`() = runTest {
        coEvery { receiptDao.insert(any<ScannedReceipt>()) } returns 5L

        val result = resolver().insertOrResolve(mockk<ScannedReceipt>(relaxed = true))

        assertEquals(ReceiptInsertResult.Inserted(5L), result)
        coVerify(exactly = 1) { receiptDao.insert(any<ScannedReceipt>()) }
    }

    @Test
    fun `receipt insert resolver does not touch the dao when barrier denies`() = runTest {
        block()

        try {
            resolver().insertOrResolve(mockk<ScannedReceipt>(relaxed = true))
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertBlocked(e)
        }

        coVerify(exactly = 0) { receiptDao.insert(any<ScannedReceipt>()) }
    }

    @Test
    fun `receipt insert resolver does not swallow cancellation from the barrier`() = runTest {
        val cancellation = CancellationException("cancelled")

        try {
            resolver(cancellingBarrier(cancellation)).insertOrResolve(mockk<ScannedReceipt>(relaxed = true))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { receiptDao.insert(any<ScannedReceipt>()) }
    }

    // -- DebugExpenseAuditWriter ----------------------------------------------

    @Test
    fun `debug audit writer inserts delete-all event once when barrier is open`() = runTest {
        auditWriter().writeDeleteAllEvent(affectedCount = 3, correlationId = "c")

        coVerify(exactly = 1) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `debug audit writer inserts restore-snapshot event once when barrier is open`() = runTest {
        auditWriter().writeRestoreSnapshotEvent(beforeCount = 1, restoredCount = 2, correlationId = "c")

        coVerify(exactly = 1) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `debug audit writer delete-all does not touch the dao when barrier denies`() = runTest {
        block()

        try {
            auditWriter().writeDeleteAllEvent(affectedCount = 3, correlationId = "c")
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertBlocked(e)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `debug audit writer restore-snapshot does not touch the dao when barrier denies`() = runTest {
        block()

        try {
            auditWriter().writeRestoreSnapshotEvent(beforeCount = 1, restoredCount = 2, correlationId = "c")
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertBlocked(e)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `debug audit writer does not swallow cancellation from the barrier`() = runTest {
        val cancellation = CancellationException("cancelled")

        try {
            auditWriter(cancellingBarrier(cancellation)).writeDeleteAllEvent(affectedCount = 1, correlationId = null)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    // -- RoomReceiptLifecycleEventWriter --------------------------------------

    @Test
    fun `receipt lifecycle event writer inserts once when barrier is open`() = runTest {
        receiptWriter().write(context(), receiptEvent())

        coVerify(exactly = 1) { receiptEventDao.insert(any<ReceiptEvent>()) }
    }

    @Test
    fun `receipt lifecycle event writer does not touch the dao when barrier denies`() = runTest {
        block()

        try {
            receiptWriter().write(context(), receiptEvent())
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertBlocked(e)
        }

        coVerify(exactly = 0) { receiptEventDao.insert(any<ReceiptEvent>()) }
    }

    @Test
    fun `receipt lifecycle event writer does not swallow cancellation from the barrier`() = runTest {
        val cancellation = CancellationException("cancelled")

        try {
            receiptWriter(cancellingBarrier(cancellation)).write(context(), receiptEvent())
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { receiptEventDao.insert(any<ReceiptEvent>()) }
    }

    // -- RoomTransactionLifecycleEventWriter ----------------------------------

    @Test
    fun `transaction lifecycle event writer inserts once when barrier is open`() = runTest {
        transactionWriter().write(context(), transactionEvent())

        coVerify(exactly = 1) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `transaction lifecycle event writer does not touch the dao when barrier denies`() = runTest {
        block()

        try {
            transactionWriter().write(context(), transactionEvent())
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertBlocked(e)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `transaction lifecycle event writer does not swallow cancellation from the barrier`() = runTest {
        val cancellation = CancellationException("cancelled")

        try {
            transactionWriter(cancellingBarrier(cancellation)).write(context(), transactionEvent())
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { transactionEventDao.insert(any<TransactionEvent>()) }
    }

    @Test
    fun `blocked exception is not a cancellation`() {
        block()

        val thrown = runCatching { barrier.checkWritesAllowed("probe") }.exceptionOrNull()

        assertTrue(thrown is DatabaseAccessBlockedException)
        assertTrue(thrown !is CancellationException)
    }
}
