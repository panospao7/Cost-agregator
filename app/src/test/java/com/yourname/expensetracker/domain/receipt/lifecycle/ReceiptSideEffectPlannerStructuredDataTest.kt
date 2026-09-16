package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.SideEffectExecutionContext
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-12 12b (P3-007): item-dependent side effects are gated by the resolved
 * RawStorageMode. Only STORE_RAW permits persisted-item consumers — item
 * categorization and price protection. Every other mode yields the CONTROLLED
 * [SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE] skip (never a
 * silent read of disallowed persisted data, never a retryable failure), which
 * is also the post-restart behaviour when ephemeral data is gone.
 */
class ReceiptSideEffectPlannerStructuredDataTest {

    private lateinit var categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase
    private lateinit var priceProtectionTracker: PriceProtectionTracker
    private lateinit var planner: ReceiptSideEffectPlanner

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        categorizeReceiptItemsUseCase = mockk(relaxed = true)
        priceProtectionTracker = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true),
            categorizeReceiptItemsUseCase = categorizeReceiptItemsUseCase,
            receiptTransactionMatcher = mockk<ReceiptTransactionMatcher>(relaxed = true),
            priceProtectionTracker = priceProtectionTracker,
            receiptLinkService = mockk(relaxed = true),
            scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true),
            receiptEventDao = mockk<ReceiptEventDao>(relaxed = true),
            timeProvider = timeProvider,
            writeBarrier = mockk(relaxed = true),
            database = mockk(relaxed = true)
        )
    }

    private fun retailReceipt(id: Long = 1L) = ScannedReceipt(
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

    private fun plan(mode: RawStorageMode) = planner.planAfterReceiptSaved(
        input = ReceiptSideEffectInput(
            receipt = retailReceipt(),
            ephemeralRawOcrText = null,
            rawStorageMode = mode,
            correlationId = "test-correlation"
        )
    )

    private suspend fun run(action: com.yourname.expensetracker.domain.sideeffect.PostCommitAction) =
        action.execute(mockk<SideEffectExecutionContext>(relaxed = true))

    @Test
    fun `restricted modes skip categorization and price protection with the controlled reason`() = runTest {
        for (mode in listOf(
            RawStorageMode.STORE_REDACTED,
            RawStorageMode.STORE_METADATA_ONLY,
            RawStorageMode.DO_NOT_STORE
        )) {
            val batch = plan(mode)
            val categorization = batch.actions.first { it.name == "receipt_item_categorization" }
            val priceCheck = batch.actions.first { it.name == "price_protection_check" }

            val catOutcome = run(categorization)
            val priceOutcome = run(priceCheck)

            assertTrue("categorization skipped for $mode, got $catOutcome",
                catOutcome is SideEffectOutcome.Skipped &&
                    (catOutcome as SideEffectOutcome.Skipped).reason == SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE)
            assertTrue("price check skipped for $mode, got $priceOutcome",
                priceOutcome is SideEffectOutcome.Skipped &&
                    (priceOutcome as SideEffectOutcome.Skipped).reason == SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE)
        }
        coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any()) }
        coVerify(exactly = 0) { priceProtectionTracker.findBetterDeals(any()) }
    }

    @Test
    fun `STORE_RAW runs categorization and price protection normally`() = runTest {
        val batch = plan(RawStorageMode.STORE_RAW)
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }
        val priceCheck = batch.actions.first { it.name == "price_protection_check" }

        val catOutcome = run(categorization)
        val priceOutcome = run(priceCheck)

        assertTrue("categorization completed, got $catOutcome", catOutcome is SideEffectOutcome.Completed)
        assertTrue("price check completed, got $priceOutcome", priceOutcome is SideEffectOutcome.Completed)
        coVerify(exactly = 1) { categorizeReceiptItemsUseCase(any(), any()) }
        coVerify(exactly = 1) { priceProtectionTracker.findBetterDeals(any()) }
    }
}
