package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R27 S2b: receipt mutations that were previously protected only by an early
 * `checkWritesAllowed` must be counted as in-flight writes so a backup/restore
 * drain ([DatabaseWriteBarrier.awaitNoInFlightWrites]) waits for them.
 *
 * Uses a REAL [DatabaseWriteBarrier] (a relaxed mock would not run the block).
 */
class ReceiptLifecycleCoordinatorWriteDrainTest {

    private val now = 1_712_000_000_000L

    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptExpenseLinkDao: ReceiptExpenseLinkDao
    private lateinit var coordinator: ReceiptLifecycleCoordinator

    @Before
    fun setup() {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        barrier = DatabaseWriteBarrier(maintenanceMode)

        scannedReceiptDao = mockk(relaxed = true)
        receiptExpenseLinkDao = mockk(relaxed = true)

        val transactionRunner = mockk<DomainTransactionRunner>(relaxed = true)
        val ctx = TransactionContext(
            correlationId = "test-correlation",
            operationId = "test",
            source = "ReceiptLifecycleCoordinator",
            occurredAt = now
        )
        coEvery { transactionRunner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (arg<suspend (TransactionContext) -> Any>(5))(ctx)
        }

        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now

        coordinator = ReceiptLifecycleCoordinator(
            database = mockk(relaxed = true),
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptRepository = mockk(relaxed = true),
            receiptLinkService = mockk(relaxed = true),
            assetStore = mockk(relaxed = true),
            inputValidator = mockk(relaxed = true),
            scannedReceiptDao = scannedReceiptDao,
            receiptExpenseLinkDao = receiptExpenseLinkDao,
            receiptEventDao = mockk(relaxed = true),
            emailReceiptDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            writeBarrier = barrier,
            transactionLifecycleCoordinator = mockk(relaxed = true),
            postCommitActionRunner = mockk(relaxed = true),
            assetCleanupCoordinator = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = mockk(relaxed = true),
            receiptInsertResolver = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true)
        )
    }

    private fun receiptWithoutAsset() = ScannedReceipt(
        id = 7L,
        imagePath = null,
        rawOcrText = "",
        parsedTotal = 25.0,
        parsedMerchant = "Test Shop",
        parsedDate = now,
        parsedItems = "[]",
        parsedTaxAmount = null,
        confidence = 0.95f,
        processingStatus = "CAPTURED"
    )

    @Test
    fun `drain waits for a migrated deleteReceipt transaction that was previously check-only`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { scannedReceiptDao.getById(7L) } returns receiptWithoutAsset()
        coEvery { receiptExpenseLinkDao.deleteAllLinksForReceipt(7L) } coAnswers {
            entered.complete(Unit)
            release.await()
        }

        val writer = launch { coordinator.deleteReceipt(7L) }
        entered.await()

        assertEquals(1, barrier.inFlightWriteCount())
        assertFalse(
            "drain must time out while the migrated write is still in flight",
            barrier.awaitNoInFlightWrites(100L)
        )

        release.complete(Unit)
        writer.join()

        assertEquals(0, barrier.inFlightWriteCount())
        assertTrue(barrier.awaitNoInFlightWrites(100L))
    }
}
