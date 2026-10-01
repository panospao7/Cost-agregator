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
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptSideEffectPlannerWarrantyTest {

    private lateinit var autoCreateWarrantyUseCase: AutoCreateWarrantyFromReceiptUseCase
    private lateinit var planner: ReceiptSideEffectPlanner

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        autoCreateWarrantyUseCase = mockk()
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = autoCreateWarrantyUseCase,
            categorizeReceiptItemsUseCase = mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            receiptTransactionMatcher = mockk<ReceiptTransactionMatcher>(relaxed = true),
            priceProtectionTracker = mockk<PriceProtectionTracker>(relaxed = true),
            receiptLinkService = mockk(relaxed = true),
            scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true),
            receiptEventDao = mockk<ReceiptEventDao>(relaxed = true),
            timeProvider = timeProvider,
            writeBarrier = mockk(relaxed = true),
            database = mockk(relaxed = true)
        )
    }

    @Test
    fun `warranty failure result becomes controlled retryable side effect outcome`() = runTest {
        coEvery { autoCreateWarrantyUseCase.execute(1L, "RAW OCR") } returns
            WarrantyCreationResult.Failure("private failure detail")

        val receipt = ScannedReceipt(
            id = 1L,
            imagePath = null,
            rawOcrText = "persisted OCR placeholder",
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
        val batch = planner.planAfterReceiptSaved(
            ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = "RAW OCR",
                rawStorageMode = RawStorageMode.STORE_RAW,
                correlationId = "test-correlation"
            )
        )

        val outcome = batch.actions.first { it.name == "warranty_extraction" }
            .execute(mockk<SideEffectExecutionContext>(relaxed = true))

        assertTrue(outcome is SideEffectOutcome.FailedRetryable)
        val failure = outcome as SideEffectOutcome.FailedRetryable
        assertEquals("warranty_creation_failed", failure.reason)
        assertEquals("WarrantyCreationResult.Failure", failure.errorClass)
    }
}
