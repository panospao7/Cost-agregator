package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.SideEffectExecutionContext
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-12 12a / P3-009: planner-driven auto-match must be idempotent.
 *
 * - the AUTO_MATCH link uses the link service's atomic unmatched-state CAS
 *   (`requireUnmatchedClaim = true`, same gate as ReceiptMatchingWorker) so an
 *   already linked / suggested / rejected / concurrently claimed receipt is
 *   never overwritten by a stale full-row update;
 * - a receipt claimed by a concurrent run yields a controlled
 *   [SideEffectOutcome.Skipped] with [SideEffectSkipReason.ALREADY_PROCESSED],
 *   never a retryable failure;
 * - `autoMatchExistingExpense = false` omits ONLY the matching action — the
 *   other RETAIL_RECEIPT actions stay in the plan.
 */
class ReceiptSideEffectPlannerClaimTest {

    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var receiptTransactionMatcher: ReceiptTransactionMatcher
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var planner: ReceiptSideEffectPlanner

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        receiptLinkService = mockk(relaxed = true)
        receiptTransactionMatcher = mockk(relaxed = true)
        scannedReceiptDao = mockk(relaxed = true)
        receiptEventDao = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true),
            categorizeReceiptItemsUseCase = mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            receiptTransactionMatcher = receiptTransactionMatcher,
            priceProtectionTracker = mockk<PriceProtectionTracker>(relaxed = true),
            receiptLinkService = receiptLinkService,
            scannedReceiptDao = scannedReceiptDao,
            receiptEventDao = receiptEventDao,
            timeProvider = timeProvider,
            writeBarrier = mockk(relaxed = true),
            database = mockk(relaxed = true)
        )
    }

    private fun unmatchedRetailReceipt(id: Long = 1L) = ScannedReceipt(
        id = id,
        imagePath = null,
        rawOcrText = "OCR text",
        parsedTotal = 25.0,
        parsedMerchant = "Test Shop",
        parsedDate = now,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 0.95f,
        sourceType = "CAMERA",
        documentType = "RETAIL_RECEIPT",
        processingStatus = "PARSED"
    )

    private fun plan(receipt: ScannedReceipt, autoMatch: Boolean = true) =
        planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = null,
                rawStorageMode = RawStorageMode.STORE_RAW,
                correlationId = "test-correlation",
                autoMatchExistingExpense = autoMatch
            )
        )

    private suspend fun runAction(action: com.yourname.expensetracker.domain.sideeffect.PostCommitAction): SideEffectOutcome {
        val context = mockk<SideEffectExecutionContext>(relaxed = true)
        return action.execute(context)
    }

    @Test
    fun `auto match plans with requireUnmatchedClaim and completes when claim succeeds`() = runTest {
        val receipt = unmatchedRetailReceipt()
        val batch = plan(receipt)
        val matchAction = batch.actions.first { it.name == "receipt_transaction_match" }

        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        coEvery { receiptTransactionMatcher.findBestMatch(any(), any()) } returns MatchResult.AutoMatch(
            transaction = mockk { every { id } returns 77L },
            score = 0.92
        )
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 1L,
                expenseId = 77L,
                linkType = "AUTO_MATCH",
                source = "RECEIPT_MATCHER",
                confidence = any(),
                matchStatus = MatchStatus.AUTO_MATCHED,
                writeSourceLink = true,
                requireUnmatchedClaim = true
            )
        } returns Result.success(mockk(relaxed = true))

        val outcome = runAction(matchAction)

        assertTrue("Expected Completed, got $outcome", outcome is SideEffectOutcome.Completed)
        // P3-009 pin: the CAS claim gate is load-bearing — the planner must never
        // take the stale full-row update branch.
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 1L,
                expenseId = 77L,
                linkType = "AUTO_MATCH",
                source = "RECEIPT_MATCHER",
                confidence = any(),
                matchStatus = MatchStatus.AUTO_MATCHED,
                writeSourceLink = true,
                requireUnmatchedClaim = true
            )
        }
    }

    @Test
    fun `already claimed receipt is skipped with ALREADY_PROCESSED and never retried`() = runTest {
        val receipt = unmatchedRetailReceipt()
        val batch = plan(receipt)
        val matchAction = batch.actions.first { it.name == "receipt_transaction_match" }

        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        coEvery { receiptTransactionMatcher.findBestMatch(any(), any()) } returns MatchResult.AutoMatch(
            transaction = mockk { every { id } returns 77L },
            score = 0.92
        )
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 1L,
                expenseId = 77L,
                linkType = "AUTO_MATCH",
                source = "RECEIPT_MATCHER",
                confidence = any(),
                matchStatus = MatchStatus.AUTO_MATCHED,
                writeSourceLink = true,
                requireUnmatchedClaim = true
            )
        } returns Result.failure(ReceiptAlreadyClaimedException(1L))

        val outcome = runAction(matchAction)

        // P3-009: claimed → controlled skip, NOT FailedRetryable and not Completed.
        assertTrue("Expected Skipped, got $outcome", outcome is SideEffectOutcome.Skipped)
        assertEquals(SideEffectSkipReason.ALREADY_PROCESSED, (outcome as SideEffectOutcome.Skipped).reason)
        // The claimed case must not be recorded as a MATCH_FAILED retryable failure.
        coVerify(exactly = 0) {
            receiptEventDao.insert(match { it.eventType == "MATCH_FAILED" })
        }
    }

    @Test
    fun `non-claim link failure stays a retryable failure`() = runTest {
        val receipt = unmatchedRetailReceipt()
        val batch = plan(receipt)
        val matchAction = batch.actions.first { it.name == "receipt_transaction_match" }

        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        coEvery { receiptTransactionMatcher.findBestMatch(any(), any()) } returns MatchResult.AutoMatch(
            transaction = mockk { every { id } returns 77L },
            score = 0.92
        )
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = any(),
                expenseId = any(),
                linkType = any(),
                source = any(),
                confidence = any(),
                matchStatus = any(),
                writeSourceLink = any(),
                requireUnmatchedClaim = any()
            )
        } returns Result.failure(IllegalStateException("expense missing"))

        val outcome = runAction(matchAction)

        assertTrue("Expected FailedRetryable, got $outcome", outcome is SideEffectOutcome.FailedRetryable)
        assertEquals("auto_match_link_failed", (outcome as SideEffectOutcome.FailedRetryable).reason)
    }

    @Test
    fun `autoMatchExistingExpense=false omits only the matching action`() = runTest {
        val receipt = unmatchedRetailReceipt()

        val withMatching = plan(receipt, autoMatch = true)
        val withoutMatching = plan(receipt, autoMatch = false)

        assertTrue(withMatching.actions.any { it.name == "receipt_transaction_match" })
        assertFalse("Expected no matching action when autoMatchExistingExpense=false",
            withoutMatching.actions.any { it.name == "receipt_transaction_match" })
        // Unrelated actions are NOT suppressed.
        assertTrue(withoutMatching.actions.any { it.name == "warranty_extraction" })
        assertTrue(withoutMatching.actions.any { it.name == "receipt_item_categorization" })
        assertTrue(withoutMatching.actions.any { it.name == "price_protection_check" })
    }
}
