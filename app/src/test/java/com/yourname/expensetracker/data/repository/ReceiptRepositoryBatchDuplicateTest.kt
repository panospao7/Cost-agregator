package com.yourname.expensetracker.data.repository

import android.net.Uri
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.debug.DebugIssueDetector
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-12 12a review follow-up (ISSUE-2): duplicates reported by
 * [ReceiptLifecycleCoordinator.processReceiptInput] (`inserted = false`) must be
 * surfaced separately in [ReceiptRepository.BatchResult.duplicateCount] instead of
 * being counted as successes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptRepositoryBatchDuplicateTest {

    private val scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val receiptLifecycleCoordinatorInner = mockk<ReceiptLifecycleCoordinator>()
    private val receiptLifecycleCoordinator = mockk<Lazy<ReceiptLifecycleCoordinator>>(relaxed = true)

    private lateinit var repository: ReceiptRepository

    private fun outcome(inserted: Boolean): ReceiptLifecycleCoordinator.ReceiptProcessOutcome =
        ReceiptLifecycleCoordinator.ReceiptProcessOutcome(
            savedReceipt = ScannedReceipt(
                imagePath = "/tmp/receipt.jpg",
                rawOcrText = "sanitized",
                parsedTotal = 10.0,
                parsedMerchant = "Store",
                parsedDate = 1_700_000_000_000L,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.9f
            ),
            inserted = inserted,
            postCommitBatch = null
        )

    @Before
    fun setup() {
        every { receiptLifecycleCoordinator.get() } returns receiptLifecycleCoordinatorInner
        repository = ReceiptRepository(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true),
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
            merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk<DebugIssueDetector>(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = mockk<TimeProvider>(relaxed = true),
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
            coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            assetStore = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            receiptLifecycleCoordinator = receiptLifecycleCoordinator,
            writeBarrier = mockk(relaxed = true),
            privacySettingsRepository = mockk(relaxed = true),
            receiptEventDao = mockk(relaxed = true),
            receiptInsertResolver = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
        )
    }

    @Test
    fun `processBatch counts inserted and duplicate items separately`() = runTest {
        val uris = listOf(
            Uri.parse("content://test/1.jpg"),
            Uri.parse("content://test/2.jpg"),
            Uri.parse("content://test/3.jpg"),
            Uri.parse("content://test/4.jpg"),
            Uri.parse("content://test/5.jpg")
        )
        // 2 newly inserted receipts + 3 duplicates (inserted = false), 0 failures.
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[0], any()) } returns Result.success(outcome(inserted = true))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[1], any()) } returns Result.success(outcome(inserted = true))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[2], any()) } returns Result.success(outcome(inserted = false))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[3], any()) } returns Result.success(outcome(inserted = false))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[4], any()) } returns Result.success(outcome(inserted = false))

        val result = repository.processBatch(uris) { _, _ -> }

        assertEquals(2, result.successCount)
        assertEquals(3, result.duplicateCount)
        assertEquals(0, result.failureCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `processBatch treats duplicate items as success-shaped but not as saves`() = runTest {
        // Chosen semantics: BatchItemResult.success stays true for duplicates
        // (not failures), while duplicateCount carries them separately. A batch
        // of only duplicates must therefore report zero saves AND zero failures.
        val uris = listOf(
            Uri.parse("content://test/dup-1.jpg"),
            Uri.parse("content://test/dup-2.jpg"),
            Uri.parse("content://test/dup-3.jpg")
        )
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(any(), any()) } returns Result.success(outcome(inserted = false))

        val result = repository.processBatch(uris) { _, _ -> }

        assertEquals(0, result.successCount)
        assertEquals(3, result.duplicateCount)
        assertEquals(0, result.failureCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `processBatch keeps true failures unchanged alongside duplicates`() = runTest {
        val uris = listOf(
            Uri.parse("content://test/ok.jpg"),
            Uri.parse("content://test/dup-1.jpg"),
            Uri.parse("content://test/dup-2.jpg"),
            Uri.parse("content://test/fail.jpg")
        )
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[0], any()) } returns Result.success(outcome(inserted = true))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[1], any()) } returns Result.success(outcome(inserted = false))
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[2], any()) } returns Result.success(outcome(inserted = false))
        // Controlled coordinator failure message — no raw URI/path content.
        coEvery { receiptLifecycleCoordinatorInner.processReceiptInput(uris[3], any()) } returns
            Result.failure(IllegalArgumentException("Receipt input validation failed"))

        val result = repository.processBatch(uris) { _, _ -> }

        assertEquals(1, result.successCount)
        assertEquals(2, result.duplicateCount)
        assertEquals(1, result.failureCount)
        // No double counting: every item lands in exactly one bucket.
        assertEquals(uris.size, result.successCount + result.duplicateCount + result.failureCount)
        assertEquals(1, result.errors.size)
        assertEquals("Receipt input validation failed", result.errors.first())
    }
}
