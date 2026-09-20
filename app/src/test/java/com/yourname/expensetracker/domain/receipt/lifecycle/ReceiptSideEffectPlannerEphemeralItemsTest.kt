package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.SideEffectExecutionContext
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-12 12b (P3-007) conditional remainder: ephemeral item pass-through INTO
 * categorization for fresh inserts, mode-gated per the P3-007 matrix.
 *
 * - a fresh insert under STORE_REDACTED with the in-memory
 *   [ReceiptSideEffectInput.ephemeralParsedItems] projection RUNS categorization
 *   using exactly those items, with persistItemTextAllowed = false — the OUTPUT
 *   must not re-persist item-name-derived content;
 * - STORE_RAW ignores any carried items — persisted items are the permitted
 *   representation and full output persistence stays (unchanged behavior);
 * - STORE_METADATA_ONLY / DO_NOT_STORE persist no item data, so categorization
 *   NEVER runs — even with the projection present — and the controlled
 *   STRUCTURED_RECEIPT_DATA_UNAVAILABLE skip is recorded instead;
 * - a restricted STORE_REDACTED insert WITHOUT the projection (process restart)
 *   records the same controlled skip — never raw-data retry;
 * - a duplicate status plans an empty batch — zero categorization;
 * - the ephemeral item content never reaches receipt events or action metadata.
 */
class ReceiptSideEffectPlannerEphemeralItemsTest {

    private lateinit var categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var planner: ReceiptSideEffectPlanner

    private val now = 1_712_000_000_000L

    /** Marker content that must NEVER appear in events, metadata, or diagnostics. */
    private val secretItem = ReceiptParser.LineItem(
        description = "SECRET-ITEM-milk-42.50",
        quantity = 2.0,
        unitPrice = 21.25,
        totalPrice = 42.50,
        itemIndex = 0
    )

    @Before
    fun setup() {
        categorizeReceiptItemsUseCase = mockk(relaxed = true)
        receiptEventDao = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true),
            categorizeReceiptItemsUseCase = categorizeReceiptItemsUseCase,
            receiptTransactionMatcher = mockk<ReceiptTransactionMatcher>(relaxed = true),
            priceProtectionTracker = mockk<PriceProtectionTracker>(relaxed = true),
            receiptLinkService = mockk(relaxed = true),
            scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true),
            receiptEventDao = receiptEventDao,
            timeProvider = timeProvider,
            writeBarrier = mockk(relaxed = true),
            database = mockk(relaxed = true)
        )
    }

    private fun retailReceipt(
        id: Long = 1L,
        parsedItems: String? = null,
        processingStatus: String = "PARSED"
    ) = ScannedReceipt(
        id = id,
        imagePath = null,
        rawOcrText = "OCR text",
        parsedTotal = 25.0,
        parsedMerchant = "Test Shop",
        parsedDate = now,
        parsedItems = parsedItems,
        parsedTaxAmount = null,
        confidence = 0.95f,
        sourceType = "CAMERA",
        documentType = "RETAIL_RECEIPT",
        processingStatus = processingStatus
    )

    private fun plan(mode: RawStorageMode, ephemeralItems: List<ReceiptParser.LineItem>?) =
        planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = retailReceipt(parsedItems = REDACTED_ITEMS_JSON),
                ephemeralRawOcrText = null,
                ephemeralParsedItems = ephemeralItems,
                rawStorageMode = mode,
                correlationId = "test-correlation"
            )
        )

    private suspend fun run(action: com.yourname.expensetracker.domain.sideeffect.PostCommitAction) =
        action.execute(mockk<SideEffectExecutionContext>(relaxed = true))

    @Test
    fun `fresh insert under STORE_REDACTED runs categorization on the ephemeral items with output persistence disallowed`() = runTest {
        val batch = plan(RawStorageMode.STORE_REDACTED, listOf(secretItem))
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

        val outcome = run(categorization)

        assertTrue("Expected Completed for a fresh insert with ephemeral items, got $outcome",
            outcome is SideEffectOutcome.Completed)
        // The use case received EXACTLY the ephemeral projection as its item
        // source AND persistItemTextAllowed = false: the STORE_REDACTED output
        // contract (no item-name-derived content may be persisted).
        coVerify(exactly = 1) { categorizeReceiptItemsUseCase(1L, any(), listOf(secretItem), false) }
    }

    @Test
    fun `fresh insert under STORE_METADATA_ONLY and DO_NOT_STORE records the controlled skip even with ephemeral items`() = runTest {
        for (mode in listOf(RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE)) {
            clearMocks(categorizeReceiptItemsUseCase)
            val batch = plan(mode, listOf(secretItem))
            val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

            val outcome = run(categorization)

            // These modes persist no item data at all, so categorization never
            // runs — the controlled skip is restored even though the ephemeral
            // projection is present.
            assertTrue("Expected Skipped for $mode, got $outcome", outcome is SideEffectOutcome.Skipped)
            assertEquals(SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE,
                (outcome as SideEffectOutcome.Skipped).reason)
            coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `STORE_RAW ignores carried ephemeral items and keeps the persisted item source`() = runTest {
        val batch = plan(RawStorageMode.STORE_RAW, listOf(secretItem))
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

        val outcome = run(categorization)

        assertTrue("Expected Completed, got $outcome", outcome is SideEffectOutcome.Completed)
        // Persisted items are the permitted representation under STORE_RAW: the use
        // case is called WITHOUT the ephemeral projection (third argument null) and
        // with full output persistence (fourth argument true).
        coVerify(exactly = 1) { categorizeReceiptItemsUseCase(1L, any(), null, true) }
    }

    @Test
    fun `process restart without the ephemeral projection records the controlled skip`() = runTest {
        // Simulated restart: the batch (or a re-plan of it) exists but the in-memory
        // projection died with the previous process.
        val batch = plan(RawStorageMode.STORE_REDACTED, ephemeralItems = null)
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

        val outcome = run(categorization)

        assertTrue("Expected Skipped, got $outcome", outcome is SideEffectOutcome.Skipped)
        assertEquals(SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE,
            (outcome as SideEffectOutcome.Skipped).reason)
        coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `empty ephemeral projection is treated as unavailable and skips`() = runTest {
        val batch = plan(RawStorageMode.STORE_METADATA_ONLY, ephemeralItems = emptyList())
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

        val outcome = run(categorization)

        assertTrue("Expected Skipped for an empty projection, got $outcome", outcome is SideEffectOutcome.Skipped)
        assertEquals(SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE,
            (outcome as SideEffectOutcome.Skipped).reason)
        coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate status plans an empty batch with zero categorization calls`() = runTest {
        val duplicate = retailReceipt(processingStatus = "DUPLICATE_DETECTED")
        val batch = planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = duplicate,
                ephemeralRawOcrText = null,
                ephemeralParsedItems = listOf(secretItem),
                rawStorageMode = RawStorageMode.STORE_REDACTED,
                correlationId = "test-correlation"
            )
        )

        assertTrue("Duplicates must plan no actions, got ${batch.actions}", batch.actions.isEmpty())
        coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `ephemeral item content never reaches receipt events or action metadata`() = runTest {
        val writtenEvents = mutableListOf<ReceiptEvent>()
        coEvery { receiptEventDao.insert(capture(writtenEvents)) } returns 1L

        // Restricted mode WITH ephemeral items (run path) and without (skip path),
        // plus METADATA_ONLY/DO_NOT_STORE with items (skip even when carried).
        val runBatch = plan(RawStorageMode.STORE_REDACTED, listOf(secretItem))
        run(runBatch.actions.first { it.name == "receipt_item_categorization" })
        val skipBatch = plan(RawStorageMode.DO_NOT_STORE, ephemeralItems = null)
        run(skipBatch.actions.first { it.name == "receipt_item_categorization" })
        val skipWithItemsBatch = plan(RawStorageMode.STORE_METADATA_ONLY, listOf(secretItem))
        run(skipWithItemsBatch.actions.first { it.name == "receipt_item_categorization" })

        // Every event carries only controlled constants — no item description, no prices.
        assertTrue("Expected lifecycle events to be written", writtenEvents.isNotEmpty())
        for (event in writtenEvents) {
            assertTrue("Event type must be a controlled constant: ${event.eventType}",
                event.eventType == "ITEMS_USED_EPHEMERALLY" || event.eventType == "SIDE_EFFECT_SKIPPED_PRIVACY")
            assertNull("Event metadata must stay empty for these events", event.metadata)
            assertTrue(event.message.orEmpty().contains("SECRET-ITEM-milk-42.50").not())
            assertTrue(event.errorDetails.orEmpty().contains("SECRET-ITEM-milk-42.50").not())
        }
        // The action metadata (fed to diagnostics via the runner) carries no item content.
        val categorizationAction = runBatch.actions.first { it.name == "receipt_item_categorization" }
        val metadataJson = categorizationAction.metadata.toJson()
        assertTrue(metadataJson.contains("SECRET-ITEM-milk-42.50").not())
        assertTrue(metadataJson.contains("21.25").not() && metadataJson.contains("42.5").not())
    }

    private companion object {
        /** The allowed persisted representation under STORE_REDACTED (prices only). */
        val REDACTED_ITEMS_JSON = """
            {"schema":"REDACTED_V1","items":[{"totalPrice":42.5,"currency":"EUR"}]}
        """.trimIndent()
    }
}
