package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrCoverage
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CA-P-03-003: real repository -> coordinator -> Room transaction/receipt/event writer.
 * OCR recognition and parsing are controlled inputs, not prebuilt repository outcomes.
 * Dedicated cases inject metadata-read faults or a resolver duplicate outcome.
 * Device PDF rendering remains a separate instrumented gate. No network call occurs here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptPartialOcrPipelineTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ReceiptRepository
    private lateinit var coordinator: ReceiptLifecycleCoordinator
    private lateinit var events: RoomReceiptLifecycleEventWriter
    private lateinit var uri: Uri
    private lateinit var recognition: OcrResult
    private lateinit var assets: ReceiptAssetStore
    private lateinit var duplicates: ReceiptDuplicateDetector
    private lateinit var insertResolver: ReceiptInsertResolver
    private var metadataReadFailure: Exception? = null

    private val ocr = mockk<ReceiptOcrService>()
    private val parser = mockk<ReceiptParser>()
    private val inputValidator = mockk<ReceiptInputValidator>()
    private val planner = mockk<ReceiptSideEffectPlanner>()
    private val postCommit = mockk<PostCommitActionRunner>(relaxed = true)
    private val now = 1_700_000_000_000L
    private val imageHash = "partial-ocr-fixture-image-hash"
    private val assetPath = "/test/receipt-partial.pdf"
    private val rawText = "PRIVATE_OCR_SENTINEL"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabaseTestFactory.create(context)
        uri = Uri.parse("content://test/partial-receipt.pdf")
        recognition = OcrResult(
            fullText = rawText,
            blocks = emptyList(),
            savedImagePath = assetPath,
            pagesProcessed = 2,
            totalPages = 3,
            failedPages = 1
        )
        val clock = mockk<TimeProvider>()
        every { clock.now() } returns now
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val transactions = RoomDomainTransactionRunner(db, clock)
        insertResolver = spyk(ReceiptInsertResolver(db.scannedReceiptDao()))
        events = spyk(RoomReceiptLifecycleEventWriter(db.receiptEventDao(), EventMetadataSanitizer(), clock))

        val currency = mockk<CurrencySettingsRepository>()
        every { currency.homeCurrency() } returns flowOf("EUR")
        coEvery { currency.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val privacy = mockk<PrivacySettingsRepository>()
        val settings = PrivacySettings()
        coEvery { privacy.getSettings() } returns settings
        coEvery { privacy.getLoadState() } returns PrivacySettingsLoadState.Loaded(settings)
        val cloudPolicy = mockk<EffectiveCloudAiPolicyResolver>()
        coEvery { cloudPolicy.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = false,
            receiptImageUploadAllowed = true,
            bankStatementCloudAllowed = false
        )

        assets = mockk()
        coEvery { assets.computeUriHash(uri) } returns Result.success(imageHash)
        coEvery { assets.computeFileHash(assetPath) } returns Result.success(imageHash)
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "application/pdf", fileSizeBytes = 100L
        )
        coEvery { ocr.processUriWithMime(uri, any()) } coAnswers { recognition }
        coEvery { parser.parse(any(), homeCurrency = any()) } returns ReceiptParser.ParsedReceipt(
            merchantName = null, total = 10.0, subtotal = null, tax = null,
            date = now, currency = "EUR", lineItems = emptyList(), confidence = 0.9f
        )
        duplicates = mockk()
        every { duplicates.computeTextFingerprintPublic(any()) } returns "partial-ocr-fixture-text-hash"
        coEvery { duplicates.checkDuplicate(any(), any(), any(), any()) } returns
            ReceiptDuplicateDetector.DuplicateResult(
                isDuplicate = false, confidence = 0f, existingReceiptId = null,
                reason = null, matchType = "NONE"
            )
        every { planner.planAfterReceiptSaved(any(), any(), any()) } returns
            PostCommitActionBatch.empty("partial-ocr-test")

        repository = ReceiptRepository(
            database = db,
            scannedReceiptDao = db.scannedReceiptDao(),
            expenseDao = db.expenseDao(),
            pendingReviewDao = db.pendingReviewDao(),
            ocrService = ocr,
            receiptParser = parser,
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = clock,
            coordinator = mockk(relaxed = true),
            receiptLinkService = mockk(relaxed = true),
            assetStore = assets,
            currencySettingsRepository = currency,
            receiptLifecycleCoordinator = Lazy { coordinator },
            writeBarrier = barrier,
            privacySettingsRepository = privacy,
            receiptEventDao = db.receiptEventDao(),
            receiptInsertResolver = insertResolver,
            pendingReviewSourceLinkService = mockk(relaxed = true)
        )
        coordinator = ReceiptLifecycleCoordinator(
            database = db,
            transactionRunner = transactions,
            receiptLifecycleEventWriter = events,
            receiptRepository = repository,
            receiptLinkService = mockk(relaxed = true),
            assetStore = assets,
            inputValidator = inputValidator,
            scannedReceiptDao = db.scannedReceiptDao(),
            receiptExpenseLinkDao = db.receiptExpenseLinkDao(),
            receiptEventDao = object : ReceiptEventDao by db.receiptEventDao() {
                override suspend fun getLatestSavedOcrMetadata(receiptId: Long): String? {
                    metadataReadFailure?.let { throw it }
                    return db.receiptEventDao().getLatestSavedOcrMetadata(receiptId)
                }
            },
            emailReceiptDao = db.emailReceiptDao(),
            pendingReviewDao = db.pendingReviewDao(),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            timeProvider = clock,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = duplicates,
            currencySettingsRepository = currency,
            writeBarrier = barrier,
            transactionLifecycleCoordinator = mockk(relaxed = true),
            postCommitActionRunner = postCommit,
            assetCleanupCoordinator = AssetCleanupCoordinator(db.scannedReceiptDao(), assets, barrier, transactions),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = RawPersistencePolicyResolver(privacy),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = planner,
            receiptInsertResolver = insertResolver,
            receiptParser = parser,
            effectiveCloudAiPolicyResolver = cloudPolicy
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun process(): ReceiptLifecycleCoordinator.ReceiptProcessOutcome =
        coordinator.processReceiptInput(
            uri, ReceiptLifecycleCoordinator.ReceiptProcessingOptions(autoMatchExistingExpense = false)
        ).getOrThrow()

    private fun forceParseFailure() {
        coEvery { parser.parse(any(), homeCurrency = any()) } throws IllegalArgumentException("PARSE_FAILED")
    }

    private suspend fun assertDuplicateState(
        first: ReceiptLifecycleCoordinator.ReceiptProcessOutcome,
        duplicate: ReceiptLifecycleCoordinator.ReceiptProcessOutcome,
        coverage: ReceiptOcrCoverage,
        unverified: Boolean = false,
        ocrCalls: Int = 1,
        eventCount: Int = 1
    ) {
        assertTrue(first.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(first.savedReceipt.id, duplicate.savedReceipt.id)
        assertEquals(ReceiptProcessingStatus.PARSE_FAILED.name, duplicate.savedReceipt.processingStatus)
        assertEquals(coverage, duplicate.ocrCoverage)
        assertEquals(coverage.isPartial, duplicate.isPartial)
        assertEquals(unverified, duplicate.ocrCoverageUnavailable)
        assertNull(duplicate.postCommitBatch)
        assertEquals(1L, db.scannedReceiptDao().getCount().toLong())
        assertEquals(first.savedReceipt.id, db.scannedReceiptDao().getByImageHash(imageHash)?.id)
        assertEquals(eventCount, db.receiptEventDao().getEventsForReceipt(first.savedReceipt.id).size)
        coVerify(exactly = ocrCalls) { ocr.processUriWithMime(uri, any()) }
        coVerify(exactly = 1) { events.write(any(), any()) }
        verify(exactly = 1) { planner.planAfterReceiptSaved(any(), any(), any()) }
    }

    private suspend fun savedMetadata(receiptId: Long): JSONObject {
        val event = db.receiptEventDao().getEventsForReceipt(receiptId).single()
        assertEquals("RECEIPT_SAVED", event.eventType)
        return JSONObject(requireNotNull(event.metadata))
    }

    @Test
    fun failedPageSurvivesRepositoryCoordinatorAndRoomWrites() = runBlocking {
        val outcome = process()
        assertTrue(outcome.inserted)
        assertTrue(outcome.isPartial)
        assertEquals(ReceiptOcrCoverage(2, 3, 1), outcome.ocrCoverage)
        val stored = requireNotNull(db.scannedReceiptDao().getById(outcome.savedReceipt.id))
        assertEquals(ReceiptProcessingStatus.OCR_PARTIAL.name, stored.processingStatus)
        assertEquals(10.0, requireNotNull(stored.parsedTotal), 0.0)
        val event = db.receiptEventDao().getEventsForReceipt(stored.id).single()
        assertEquals(stored.processingStatus, event.newStatus)
        val metadata = savedMetadata(stored.id)
        assertTrue(metadata.getBoolean("partial"))
        assertEquals("PDF_PARTIAL", metadata.getString("reasonCode"))
        assertEquals(2, metadata.getInt("pagesProcessed"))
        assertEquals(3, metadata.getInt("totalPages"))
        assertEquals(1, metadata.getInt("failedPages"))
        assertFalse(metadata.toString().contains(rawText))
        assertFalse(metadata.toString().contains(assetPath))
        coVerify(exactly = 1) { ocr.processUriWithMime(uri, "application/pdf") }
        verify(exactly = 1) {
            planner.planAfterReceiptSaved(
                match { it.receipt.processingStatus == ReceiptProcessingStatus.OCR_PARTIAL.name }, any(), any()
            )
        }
    }

    @Test
    fun failedPageIsPartialEvenWhenAnOlderProducerCountsAttemptedPages() = runBlocking {
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 1)
        val outcome = process()
        assertTrue(outcome.isPartial)
        assertEquals(ReceiptProcessingStatus.OCR_PARTIAL.name, outcome.savedReceipt.processingStatus)
        assertEquals(1, savedMetadata(outcome.savedReceipt.id).getInt("failedPages"))
    }

    @Test
    fun cappedPdfIsPartialWithoutInventingFailedPages() = runBlocking {
        recognition = recognition.copy(pagesProcessed = 5, totalPages = 8, failedPages = null)
        val outcome = process()
        assertTrue(outcome.isPartial)
        assertEquals(ReceiptOcrCoverage(5, 8, null), outcome.ocrCoverage)
        val metadata = savedMetadata(outcome.savedReceipt.id)
        assertEquals(5, metadata.getInt("pagesProcessed"))
        assertEquals(8, metadata.getInt("totalPages"))
        assertFalse(metadata.has("failedPages"))
    }

    @Test
    fun completePdfRetainsOrdinarySuccessStatus() = runBlocking {
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 0)
        val outcome = process()
        assertFalse(outcome.isPartial)
        // The controlled parser has no merchant; the existing complete status is OCR_COMPLETED.
        assertEquals(ReceiptProcessingStatus.OCR_COMPLETED.name, outcome.savedReceipt.processingStatus)
        assertFalse(savedMetadata(outcome.savedReceipt.id).has("partial"))
    }

    @Test
    fun imageWithoutPageMetadataIsNotPartial() = runBlocking {
        recognition = recognition.copy(pagesProcessed = null, totalPages = null, failedPages = null)
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 100L
        )
        val outcome = process()
        assertFalse(outcome.isPartial)
        assertEquals(ReceiptOcrCoverage(), outcome.ocrCoverage)
        assertEquals(ReceiptProcessingStatus.OCR_COMPLETED.name, outcome.savedReceipt.processingStatus)
        coVerify(exactly = 1) { ocr.processUriWithMime(uri, "image/jpeg") }
    }

    @Test
    fun parseFailureRetainsItsStatusAndThePartialMetadata() = runBlocking {
        coEvery { parser.parse(any(), homeCurrency = any()) } throws IllegalArgumentException("PARSE_FAILED")
        val outcome = process()
        val stored = requireNotNull(db.scannedReceiptDao().getById(outcome.savedReceipt.id))
        assertEquals(ReceiptProcessingStatus.PARSE_FAILED.name, stored.processingStatus)
        assertNull(stored.parsedTotal)
        assertTrue(outcome.isPartial)
        assertTrue(savedMetadata(stored.id).getBoolean("partial"))
        assertEquals(1, savedMetadata(stored.id).getInt("failedPages"))
    }

    @Test
    fun cancellationLeavesNoReceiptOrSavedEvent() = runBlocking {
        coEvery { ocr.processUriWithMime(uri, any()) } throws CancellationException("CANCELLED")
        try {
            process()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Identity is deliberately not asserted across coroutine/transaction boundaries.
        }
        assertEquals(0L, db.scannedReceiptDao().getCount().toLong())
        coVerify(exactly = 0) { events.write(any(), any()) }
    }

    @Test
    fun duplicateRetainsPersistedPartialityWithoutAnotherOcrOrSaveEvent() = runBlocking {
        val first = process()
        val duplicate = process()
        assertFalse(duplicate.inserted)
        assertTrue(duplicate.isPartial)
        assertEquals(first.savedReceipt.id, duplicate.savedReceipt.id)
        assertNull(duplicate.postCommitBatch)
        assertEquals(1L, db.scannedReceiptDao().getCount().toLong())
        assertEquals(1, db.receiptEventDao().getEventsForReceipt(first.savedReceipt.id).size)
        coVerify(exactly = 1) { ocr.processUriWithMime(uri, "application/pdf") }
        verify(exactly = 1) { planner.planAfterReceiptSaved(any(), any(), any()) }
    }

    @Test
    fun parseFailedDuplicateRestoresPartialCountsWithoutAnotherOcrOrEvent() = runBlocking {
        forceParseFailure()
        val first = process()
        val duplicate = process()
        assertDuplicateState(first, duplicate, ReceiptOcrCoverage(2, 3, 1))
        assertEquals(1, savedMetadata(first.savedReceipt.id).getInt("pageCoverageVersion"))
    }

    @Test
    fun cappedParseFailureRestoresCountsWithoutInventingFailedPages() = runBlocking {
        forceParseFailure()
        recognition = recognition.copy(pagesProcessed = 5, totalPages = 8, failedPages = null)
        val first = process()
        assertDuplicateState(first, process(), ReceiptOcrCoverage(5, 8, null))
    }

    @Test
    fun completePdfParseFailureHasKnownNonPartialCoverageOnRetry() = runBlocking {
        forceParseFailure()
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 0)
        val first = process()
        assertDuplicateState(first, process(), ReceiptOcrCoverage(3, 3, 0))
        assertFalse(savedMetadata(first.savedReceipt.id).has("partial"))
    }

    @Test
    fun imageParseFailureHasKnownCoverageWithoutInventedPageCountsOnRetry() = runBlocking {
        forceParseFailure()
        recognition = recognition.copy(pagesProcessed = null, totalPages = null, failedPages = null)
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 100L
        )
        val first = process()
        assertDuplicateState(first, process(), ReceiptOcrCoverage())
        assertFalse(savedMetadata(first.savedReceipt.id).has("pagesProcessed"))
    }

    @Test
    fun legacyPositivePartialEnvelopeSurvivesAnUnversionedSavedEvent() = runBlocking {
        forceParseFailure()
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 1)
        val first = process()
        val savedEvent = db.receiptEventDao().getEventsForReceipt(first.savedReceipt.id).single()
        val legacy = JSONObject(requireNotNull(savedEvent.metadata)).apply { remove("pageCoverageVersion") }
        // Seed historical metadata; the retry itself must not append an event.
        db.receiptEventDao().insert(savedEvent.copy(id = 0, occurredAt = now + 1, metadata = legacy.toString()))
        assertDuplicateState(first, process(), ReceiptOcrCoverage(3, 3, 1), eventCount = 2)
    }

    @Test
    fun expiredCoverageRemainsUnverifiedWithoutRecreatingRetainedEvents() = runBlocking {
        forceParseFailure()
        val first = process()
        assertEquals(1, db.receiptEventDao().deleteOlderThan(now + 1))
        assertDuplicateState(first, process(), ReceiptOcrCoverage(), unverified = true, eventCount = 0)
    }

    @Test
    fun metadataReadFailureKeepsTheDuplicateUsableButUnverified() = runBlocking {
        forceParseFailure()
        val first = process()
        metadataReadFailure = IllegalStateException("UNTRUSTED_METADATA_READ_PATH /private/receipt")
        val duplicate = process()
        assertDuplicateState(first, duplicate, ReceiptOcrCoverage(), unverified = true)
        assertFalse(duplicate.savedReceipt.parseFailureReason.orEmpty().contains("UNTRUSTED_METADATA_READ_PATH"))
        assertFalse(savedMetadata(first.savedReceipt.id).toString().contains("UNTRUSTED_METADATA_READ_PATH"))
    }

    @Test
    fun metadataReadCancellationPropagatesDirectlyWithoutAnotherSave() = runBlocking {
        forceParseFailure()
        val first = process()
        metadataReadFailure = CancellationException("CANCELLED")
        try {
            coordinator.processReceiptInput(
                uri, ReceiptLifecycleCoordinator.ReceiptProcessingOptions(autoMatchExistingExpense = false)
            )
            fail("Cancellation must propagate directly instead of returning a Result")
        } catch (_: CancellationException) {
            // No exception-instance identity assumption across coroutine boundaries.
        }
        assertEquals(1L, db.scannedReceiptDao().getCount().toLong())
        assertEquals(1, db.receiptEventDao().getEventsForReceipt(first.savedReceipt.id).size)
        coVerify(exactly = 1) { ocr.processUriWithMime(uri, any()) }
        coVerify(exactly = 1) { events.write(any(), any()) }
        verify(exactly = 1) { planner.planAfterReceiptSaved(any(), any(), any()) }
    }

    @Test
    fun malformedLatestEvidenceDoesNotFallBackToAnOlderPositiveEnvelope() = runBlocking {
        forceParseFailure()
        val first = process()
        val savedEvent = db.receiptEventDao().getEventsForReceipt(first.savedReceipt.id).single()
        db.receiptEventDao().insert(savedEvent.copy(id = 0, occurredAt = now + 1, metadata = "INVALID_COVERAGE"))
        assertDuplicateState(first, process(), ReceiptOcrCoverage(), unverified = true, eventCount = 2)
    }

    @Test
    fun postOcrExactHashDuplicateUsesStoredCoverageRatherThanCurrentAttempt() = runBlocking {
        forceParseFailure()
        val first = process()
        coEvery { duplicates.checkDuplicate(any(), any(), any(), any()) } returns
            ReceiptDuplicateDetector.DuplicateResult(
                isDuplicate = true, confidence = 1f, existingReceiptId = first.savedReceipt.id,
                reason = "EXACT_HASH", matchType = "EXACT_HASH"
            )
        coEvery { assets.computeUriHash(uri) } returns Result.success("different-uri-bytes")
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 0)
        assertDuplicateState(first, process(), ReceiptOcrCoverage(2, 3, 1), ocrCalls = 2)
        coVerify(exactly = 1) { insertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { assets.deleteAsset(any()) }
    }

    @Test
    fun postOcrTextDuplicateUsesStoredCoverageRatherThanCurrentAttempt() = runBlocking {
        forceParseFailure()
        val first = process()
        coEvery { assets.computeUriHash(uri) } returns Result.success("different-uri-bytes")
        coEvery { assets.computeFileHash(assetPath) } returns Result.success("different-file-bytes")
        coEvery { duplicates.checkDuplicate(any(), any(), any(), any()) } returns
            ReceiptDuplicateDetector.DuplicateResult(
                isDuplicate = true, confidence = 1f, existingReceiptId = first.savedReceipt.id,
                reason = "TEXT_FINGERPRINT", matchType = "TEXT_FINGERPRINT"
            )
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 0)
        assertDuplicateState(first, process(), ReceiptOcrCoverage(2, 3, 1), ocrCalls = 2)
        coVerify(exactly = 1) { insertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { assets.deleteAsset(any()) }
    }

    @Test
    fun insertResolverDuplicateOutcomeRestoresStoredCoverageAfterTransactionExit() = runBlocking {
        forceParseFailure()
        val first = process()
        coEvery { assets.computeUriHash(uri) } returns Result.success("different-uri-bytes")
        coEvery { assets.computeFileHash(assetPath) } returns Result.success("different-file-bytes")
        // Inject the typed resolver outcome; this is not a concurrent-race execution test.
        coEvery { insertResolver.insertOrResolve(any()) } returns
            ReceiptInsertResult.Duplicate(first.savedReceipt, "insert_ignored")
        recognition = recognition.copy(pagesProcessed = 3, totalPages = 3, failedPages = 0)
        assertDuplicateState(first, process(), ReceiptOcrCoverage(2, 3, 1), ocrCalls = 2)
        coVerify(exactly = 2) { insertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { assets.deleteAsset(any()) }
    }

    @Test
    fun eventFailureRollsBackThePartialReceipt() = runBlocking {
        coEvery { events.write(any(), any()) } throws IllegalStateException("EVENT_WRITE_FAILED")
        val result = coordinator.processReceiptInput(uri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0L, db.scannedReceiptDao().getCount().toLong())
        verify(exactly = 0) { planner.planAfterReceiptSaved(any(), any(), any()) }
        coVerify(exactly = 0) { postCommit.run(any()) }
    }

    @Test
    fun batchUsesTheRealPipelineAndReportsPartialSavedRows() = runBlocking {
        val result = repository.processBatch(listOf(uri)) { _, _ -> }
        assertEquals(1, result.successCount)
        assertEquals(1, result.partialCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.duplicateCount)
        val stored = requireNotNull(db.scannedReceiptDao().getByImageHash(imageHash))
        assertEquals(ReceiptProcessingStatus.OCR_PARTIAL.name, stored.processingStatus)
        assertTrue(savedMetadata(stored.id).getBoolean("partial"))
    }
}
