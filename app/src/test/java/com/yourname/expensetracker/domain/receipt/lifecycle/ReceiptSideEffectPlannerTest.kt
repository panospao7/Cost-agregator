package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.SideEffectExecutionContext
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mock-based [ReceiptSideEffectPlanner] behaviour (B6-16 consolidation of the former
 * Claim / EphemeralItems / StructuredData / Warranty test files).
 *
 * The real-Room, Robolectric-backed concurrency test stays separate in
 * [ReceiptSideEffectPlannerSuggestionRaceTest] because it needs a real in-memory
 * database and a different runner.
 *
 * Sections:
 * 1. Auto-match claim (RP-12 12a / P3-009)
 * 2. Ephemeral item pass-through (RP-12 12b / P3-007 conditional remainder)
 * 3. Structured-data / RawStorageMode gating (RP-12 12b / P3-007)
 * 4. Warranty failure mapping
 */
class ReceiptSideEffectPlannerTest {

    private lateinit var autoCreateWarrantyUseCase: AutoCreateWarrantyFromReceiptUseCase
    private lateinit var categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase
    private lateinit var receiptTransactionMatcher: ReceiptTransactionMatcher
    private lateinit var priceProtectionTracker: PriceProtectionTracker
    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var scannedReceiptDao: ScannedReceiptDao
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
        // Strict (non-relaxed): the warranty mapping test stubs it explicitly and no
        // other test may touch it.
        autoCreateWarrantyUseCase = mockk()
        categorizeReceiptItemsUseCase = mockk(relaxed = true)
        receiptTransactionMatcher = mockk(relaxed = true)
        priceProtectionTracker = mockk(relaxed = true)
        receiptLinkService = mockk(relaxed = true)
        scannedReceiptDao = mockk(relaxed = true)
        receiptEventDao = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = autoCreateWarrantyUseCase,
            categorizeReceiptItemsUseCase = categorizeReceiptItemsUseCase,
            receiptTransactionMatcher = receiptTransactionMatcher,
            priceProtectionTracker = priceProtectionTracker,
            receiptLinkService = receiptLinkService,
            scannedReceiptDao = scannedReceiptDao,
            receiptEventDao = receiptEventDao,
            timeProvider = timeProvider,
            writeBarrier = mockk(relaxed = true),
            database = mockk(relaxed = true)
        )
    }

    // ---------------------------------------------------------------------
    // Shared fixtures
    // ---------------------------------------------------------------------

    private fun retailReceipt(
        id: Long = 1L,
        parsedItems: String? = null,
        processingStatus: String = "PARSED",
        rawOcrText: String = "OCR text"
    ) = ScannedReceipt(
        id = id,
        imagePath = null,
        rawOcrText = rawOcrText,
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

    private suspend fun run(action: PostCommitAction): SideEffectOutcome =
        action.execute(mockk<SideEffectExecutionContext>(relaxed = true))

    // ---------------------------------------------------------------------
    // 1. Auto-match claim (RP-12 12a / P3-009): planner-driven auto-match must be idempotent.
    //
    // - the AUTO_MATCH link uses the link service's atomic unmatched-state CAS
    //   (requireUnmatchedClaim = true, same gate as ReceiptMatchingWorker) so an
    //   already linked / suggested / rejected / concurrently claimed receipt is
    //   never overwritten by a stale full-row update;
    // - a receipt claimed by a concurrent run yields a controlled
    //   SideEffectOutcome.Skipped with SideEffectSkipReason.ALREADY_PROCESSED,
    //   never a retryable failure;
    // - autoMatchExistingExpense = false omits ONLY the matching action.
    // ---------------------------------------------------------------------

    private fun planForAutoMatch(receipt: ScannedReceipt, autoMatch: Boolean = true) =
        planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = null,
                rawStorageMode = RawStorageMode.STORE_RAW,
                correlationId = "test-correlation",
                autoMatchExistingExpense = autoMatch
            )
        )

    @Test
    fun `auto match plans with requireUnmatchedClaim and completes when claim succeeds`() = runTest {
        val receipt = retailReceipt()
        val batch = planForAutoMatch(receipt)
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

        val outcome = run(matchAction)

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
        val receipt = retailReceipt()
        val batch = planForAutoMatch(receipt)
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

        val outcome = run(matchAction)

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
        val receipt = retailReceipt()
        val batch = planForAutoMatch(receipt)
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

        val outcome = run(matchAction)

        assertTrue("Expected FailedRetryable, got $outcome", outcome is SideEffectOutcome.FailedRetryable)
        assertEquals("auto_match_link_failed", (outcome as SideEffectOutcome.FailedRetryable).reason)
    }

    @Test
    fun `autoMatchExistingExpense=false omits only the matching action`() = runTest {
        val receipt = retailReceipt()

        val withMatching = planForAutoMatch(receipt, autoMatch = true)
        val withoutMatching = planForAutoMatch(receipt, autoMatch = false)

        assertTrue(withMatching.actions.any { it.name == "receipt_transaction_match" })
        assertFalse("Expected no matching action when autoMatchExistingExpense=false",
            withoutMatching.actions.any { it.name == "receipt_transaction_match" })
        // Unrelated actions are NOT suppressed.
        assertTrue(withoutMatching.actions.any { it.name == "warranty_extraction" })
        assertTrue(withoutMatching.actions.any { it.name == "receipt_item_categorization" })
        assertTrue(withoutMatching.actions.any { it.name == "price_protection_check" })
    }

    // ---------------------------------------------------------------------
    // 2. Ephemeral item pass-through (RP-12 12b / P3-007 conditional remainder):
    //    ephemeral item pass-through INTO categorization for fresh inserts,
    //    mode-gated per the P3-007 matrix.
    //
    // - fresh insert under STORE_REDACTED with the in-memory
    //   ReceiptSideEffectInput.ephemeralParsedItems projection RUNS categorization
    //   using exactly those items, with persistItemTextAllowed = false;
    // - STORE_RAW ignores any carried items (unchanged behavior);
    // - STORE_METADATA_ONLY / DO_NOT_STORE never run categorization, even with the
    //   projection present — controlled STRUCTURED_RECEIPT_DATA_UNAVAILABLE skip;
    // - restricted STORE_REDACTED WITHOUT the projection (process restart) skips;
    // - a duplicate status plans an empty batch;
    // - ephemeral item content never reaches receipt events or action metadata.
    // ---------------------------------------------------------------------

    private fun planWithEphemeralItems(mode: RawStorageMode, ephemeralItems: List<ReceiptParser.LineItem>?) =
        planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = retailReceipt(parsedItems = REDACTED_ITEMS_JSON),
                ephemeralRawOcrText = null,
                ephemeralParsedItems = ephemeralItems,
                rawStorageMode = mode,
                correlationId = "test-correlation"
            )
        )

    @Test
    fun `fresh insert under STORE_REDACTED runs categorization on the ephemeral items with output persistence disallowed`() = runTest {
        val batch = planWithEphemeralItems(RawStorageMode.STORE_REDACTED, listOf(secretItem))
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
            val batch = planWithEphemeralItems(mode, listOf(secretItem))
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
        val batch = planWithEphemeralItems(RawStorageMode.STORE_RAW, listOf(secretItem))
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
        val batch = planWithEphemeralItems(RawStorageMode.STORE_REDACTED, ephemeralItems = null)
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }

        val outcome = run(categorization)

        assertTrue("Expected Skipped, got $outcome", outcome is SideEffectOutcome.Skipped)
        assertEquals(SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE,
            (outcome as SideEffectOutcome.Skipped).reason)
        coVerify(exactly = 0) { categorizeReceiptItemsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `empty ephemeral projection is treated as unavailable and skips`() = runTest {
        val batch = planWithEphemeralItems(RawStorageMode.STORE_METADATA_ONLY, ephemeralItems = emptyList())
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
        val runBatch = planWithEphemeralItems(RawStorageMode.STORE_REDACTED, listOf(secretItem))
        run(runBatch.actions.first { it.name == "receipt_item_categorization" })
        val skipBatch = planWithEphemeralItems(RawStorageMode.DO_NOT_STORE, ephemeralItems = null)
        run(skipBatch.actions.first { it.name == "receipt_item_categorization" })
        val skipWithItemsBatch = planWithEphemeralItems(RawStorageMode.STORE_METADATA_ONLY, listOf(secretItem))
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

    // ---------------------------------------------------------------------
    // 3. Structured-data gating (RP-12 12b / P3-007): item-dependent side effects
    //    are gated by the resolved RawStorageMode. Only STORE_RAW permits
    //    persisted-item consumers (categorization, price protection). Every other
    //    mode yields the CONTROLLED STRUCTURED_RECEIPT_DATA_UNAVAILABLE skip
    //    (never a silent read of disallowed persisted data, never a retryable
    //    failure), which is also the post-restart behaviour.
    // ---------------------------------------------------------------------

    private fun planForMode(mode: RawStorageMode) = planner.planAfterReceiptSaved(
        input = ReceiptSideEffectInput(
            receipt = retailReceipt(),
            ephemeralRawOcrText = null,
            rawStorageMode = mode,
            correlationId = "test-correlation"
        )
    )

    @Test
    fun `restricted modes skip categorization and price protection with the controlled reason`() = runTest {
        for (mode in listOf(
            RawStorageMode.STORE_REDACTED,
            RawStorageMode.STORE_METADATA_ONLY,
            RawStorageMode.DO_NOT_STORE
        )) {
            val batch = planForMode(mode)
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
        val batch = planForMode(RawStorageMode.STORE_RAW)
        val categorization = batch.actions.first { it.name == "receipt_item_categorization" }
        val priceCheck = batch.actions.first { it.name == "price_protection_check" }

        val catOutcome = run(categorization)
        val priceOutcome = run(priceCheck)

        assertTrue("categorization completed, got $catOutcome", catOutcome is SideEffectOutcome.Completed)
        assertTrue("price check completed, got $priceOutcome", priceOutcome is SideEffectOutcome.Completed)
        coVerify(exactly = 1) { categorizeReceiptItemsUseCase(any(), any()) }
        coVerify(exactly = 1) { priceProtectionTracker.findBetterDeals(any()) }
    }

    // ---------------------------------------------------------------------
    // 4. Warranty failure mapping
    // ---------------------------------------------------------------------

    @Test
    fun `warranty failure result becomes controlled retryable side effect outcome`() = runTest {
        coEvery { autoCreateWarrantyUseCase.execute(1L, "RAW OCR") } returns
            WarrantyCreationResult.Failure("private failure detail")

        val receipt = retailReceipt(rawOcrText = "persisted OCR placeholder")
        val batch = planner.planAfterReceiptSaved(
            ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = "RAW OCR",
                rawStorageMode = RawStorageMode.STORE_RAW,
                correlationId = "test-correlation"
            )
        )

        val outcome = run(batch.actions.first { it.name == "warranty_extraction" })

        assertTrue(outcome is SideEffectOutcome.FailedRetryable)
        val failure = outcome as SideEffectOutcome.FailedRetryable
        assertEquals("warranty_creation_failed", failure.reason)
        assertEquals("WarrantyCreationResult.Failure", failure.errorClass)
    }

    @Test
    fun `warranty extraction without ephemeral raw is skipped with a privacy event and never reaches the use case`() = runTest {
        // AE-13 (INV-9): DO_NOT_STORE receipt after a process restart -> no ephemeral raw text.
        // Policy A: no silent fallback to the persisted/sanitized text; the use case (strict mock
        // in this class) must not be invoked.
        val writtenEvents = mutableListOf<ReceiptEvent>()
        coEvery { receiptEventDao.insert(capture(writtenEvents)) } returns 1L

        val receipt = retailReceipt(rawOcrText = "persisted placeholder that must never be used")
        val batch = planner.planAfterReceiptSaved(
            ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = null,
                rawStorageMode = RawStorageMode.DO_NOT_STORE,
                correlationId = "test-correlation"
            )
        )

        val outcome = run(batch.actions.first { it.name == "warranty_extraction" })

        assertTrue("Expected Skipped, got $outcome", outcome is SideEffectOutcome.Skipped)
        assertEquals(SideEffectSkipReason.MISSING_ENTITY, (outcome as SideEffectOutcome.Skipped).reason)
        assertTrue(
            "Expected SIDE_EFFECT_SKIPPED_PRIVACY, got ${writtenEvents.map { it.eventType }}",
            writtenEvents.any { it.eventType == "SIDE_EFFECT_SKIPPED_PRIVACY" }
        )
        assertTrue(
            "Skip event must not carry receipt text",
            writtenEvents.none { it.message.orEmpty().contains("persisted placeholder") }
        )
        coVerify(exactly = 0) { autoCreateWarrantyUseCase.execute(any(), any()) }
    }

    private companion object {
        /** The allowed persisted representation under STORE_REDACTED (prices only). */
        val REDACTED_ITEMS_JSON = """
            {"schema":"REDACTED_V1","items":[{"totalPrice":42.5,"currency":"EUR"}]}
        """.trimIndent()
    }
}
