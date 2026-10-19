package com.yourname.expensetracker.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.OcrRecognitionFailedException
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAssetStore
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/**
 * Receipt-repository OCR/parse fallback contract (draft-first lifecycle).
 *
 * Replaces the permanently ignored ReceiptRepositoryStressTest cases that still
 * map to live behaviour. In production [ReceiptRepository.processReceipt] and
 * [ReceiptRepository.saveManualReceiptRecord] return DRAFT receipts (id = 0) and
 * never write to [ScannedReceiptDao]; the lifecycle coordinator owns the insert.
 * The tests therefore assert (a) the draft contents and (b) that the DAO is
 * never touched on any of these paths. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptRepositoryOcrFallbackTest {

    private val scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val ocrService = mockk<ReceiptOcrService>(relaxed = true)
    private val receiptParser = mockk<ReceiptParser>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val assetStore = mockk<ReceiptAssetStore>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
    private val receiptEventDao = mockk<ReceiptEventDao>(relaxed = true)
    private val receiptInsertResolver = mockk<ReceiptInsertResolver>(relaxed = true)
    private val receiptLifecycleCoordinatorInner =mockk<ReceiptLifecycleCoordinator>()
    private val receiptLifecycleCoordinator = mockk<Lazy<ReceiptLifecycleCoordinator>>(relaxed = true)

    private lateinit var repository: ReceiptRepository

    private val fixedNow = 1_700_000_000_000L
    private val uri: Uri = Uri.parse("content://synthetic/receipt.jpg")

    @Before
    fun setup() {
        // processReceipt success path calls Room's inline withTransaction extension;
        // run its block inline so the mocked database cannot hang the coroutine.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        every { timeProvider.now() } returns fixedNow
        every { receiptLifecycleCoordinator.get() } returns receiptLifecycleCoordinatorInner
        // Relaxed value-class Result is unsafe: force the pre-OCR hash lookup to fail
        // so processReceipt proceeds to OCR deterministically.
        coEvery { assetStore.computeUriHash(any()) } returns
            Result.failure(IllegalStateException("hash unavailable"))
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_RAW)

        repository = ReceiptRepository(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = ocrService,
            receiptParser = receiptParser,
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk(relaxed = true),
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = mockk(relaxed = true),
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = timeProvider,
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
            coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            assetStore = assetStore,
            currencySettingsRepository = currencySettingsRepository,
            receiptLifecycleCoordinator = receiptLifecycleCoordinator,
            writeBarrier = mockk(relaxed = true),
            privacySettingsRepository = privacySettingsRepository,
            receiptEventDao = receiptEventDao,
            receiptInsertResolver = receiptInsertResolver,
            pendingReviewSourceLinkService = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun ocrResult(text: String, savedPath: String = "/path/to/saved.jpg") = OcrResult(
        fullText = text,
        blocks = emptyList(),
        savedImagePath = savedPath,
    )

    private fun assertDaoUntouched() {
        coVerify(exactly = 0) { scannedReceiptDao.insert(any()) }
        coVerify(exactly = 0) { scannedReceiptDao.update(any()) }
    }

    /** Draft-first: neither the insert resolver nor the receipt event log is touched. */
    private fun assertNothingPersisted() {
        assertDaoUntouched()
        coVerify(exactly = 0) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { receiptEventDao.insert(any()) }
    }

    // ── OCR failure → manual-record draft (ReceiptRepository.kt:217-238) ─────────

    @Test
    fun `OCR failure falls back to OCR_FAILED draft with persisted image copy and no DAO writes`() = runTest {
        coEvery { ocrService.processUri(uri) } throws RuntimeException("OCR failed")
        every { ocrService.persistImageCopy(uri) } returns "/fallback/path.jpg"

        val result = repository.processReceipt(uri)

        val receipt = result.receipt
        assertEquals("[OCR Failed or Skipped]", receipt.rawOcrText)
        assertEquals(ReceiptProcessingStatus.OCR_FAILED.name, receipt.processingStatus)
        assertEquals("/fallback/path.jpg", receipt.imagePath)
        assertEquals("", receipt.currency)
        assertEquals(0f, receipt.confidence, 0f)
        assertNull(receipt.parsedTotal)
        assertNull(receipt.parsedMerchant)
        assertEquals(fixedNow, receipt.parsedDate)
        assertEquals(fixedNow, receipt.createdAt)
        assertEquals(0L, receipt.id)
        assertNull(result.parsed.total)
        assertNull(result.parsed.merchantName)
        assertEquals("", result.parsed.currency)
        assertEquals(0f, result.parsed.confidence, 0f)
        assertNull(result.ephemeralRawOcrText)
        verify(exactly = 1) { ocrService.persistImageCopy(uri) }
        verify(exactly = 0) { receiptParser.parse(any(), any()) }
        assertNothingPersisted()
    }

    @Test
    fun `OCR failure with resolved mime uses the mime-aware OCR entry point and still falls back`() = runTest {
        coEvery { ocrService.processUriWithMime(uri, "image/jpeg") } throws RuntimeException("OCR failed")
        every { ocrService.persistImageCopy(uri) } returns "/fallback/path.jpg"

        val result = repository.processReceipt(uri, resolvedMimeType = "image/jpeg")

        assertEquals(ReceiptProcessingStatus.OCR_FAILED.name, result.receipt.processingStatus)
        assertEquals("/fallback/path.jpg", result.receipt.imagePath)
        coVerify(exactly = 1) { ocrService.processUriWithMime(uri, "image/jpeg") }
        coVerify(exactly = 0) { ocrService.processUri(any<Uri>()) }
        assertDaoUntouched()
    }

    @Test
    fun `typed OCR recognition failure reuses its saved image path and does not copy the image again`() = runTest {
        coEvery { ocrService.processUri(uri) } throws
            OcrRecognitionFailedException("/already/saved.jpg", RuntimeException("engine error"))

        val result = repository.processReceipt(uri)

        assertEquals("/already/saved.jpg", result.receipt.imagePath)
        assertEquals(ReceiptProcessingStatus.OCR_FAILED.name, result.receipt.processingStatus)
        assertEquals("[OCR Failed or Skipped]", result.receipt.rawOcrText)
        verify(exactly = 0) { ocrService.persistImageCopy(any()) }
        assertDaoUntouched()
    }

    @Test
    fun `typed OCR recognition failure with null saved path falls back to persistImageCopy`() = runTest {
        coEvery { ocrService.processUri(uri) } throws OcrRecognitionFailedException(null, null)
        every { ocrService.persistImageCopy(uri) } returns "/fallback/path.jpg"

        val result = repository.processReceipt(uri)

        assertEquals("/fallback/path.jpg", result.receipt.imagePath)
        verify(exactly = 1) { ocrService.persistImageCopy(uri) }
    }

    @Test
    fun `OCR failure with failing image copy yields null imagePath never the raw uri`() = runTest {
        coEvery { ocrService.processUri(uri) } throws RuntimeException("OCR failed")
        every { ocrService.persistImageCopy(uri) } throws java.io.IOException("disk full")

        val result = repository.processReceipt(uri)

        assertNull(result.receipt.imagePath)
        assertEquals(ReceiptProcessingStatus.OCR_FAILED.name, result.receipt.processingStatus)
        assertDaoUntouched()
    }

    @Test
    fun `OCR cancellation propagates and is not converted to an OCR_FAILED draft`() = runTest {
        coEvery { ocrService.processUri(uri) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { repository.processReceipt(uri) }

        verify(exactly = 0) { ocrService.persistImageCopy(any()) }
        verify(exactly = 0) { receiptParser.parse(any(), any()) }
        assertDaoUntouched()
    }

    @Test
    fun `parse cancellation propagates and is not converted to a PARSE_FAILED draft`() = runTest {
        coEvery { ocrService.processUri(uri) } returns ocrResult("Synthetic receipt text")
        every { receiptParser.parse(any(), any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { repository.processReceipt(uri) }

        assertDaoUntouched()
    }

    // ── saveManualReceiptRecord (ReceiptRepository.kt:323-338) ───────────────────

    @Test
    fun `saveManualReceiptRecord returns OCR_FAILED draft with persisted copy and no DAO writes`() = runTest {
        every { ocrService.persistImageCopy(uri) } returns "/manual/path.jpg"

        val result = repository.saveManualReceiptRecord(uri)

        val receipt = result.receipt
        assertEquals("[OCR Failed or Skipped]", receipt.rawOcrText)
        assertEquals(ReceiptProcessingStatus.OCR_FAILED.name, receipt.processingStatus)
        assertEquals("/manual/path.jpg", receipt.imagePath)
        assertEquals("", receipt.currency)
        assertEquals(0f, receipt.confidence, 0f)
        assertEquals(fixedNow, receipt.parsedDate)
        assertEquals(0L, receipt.id)
        assertNull(result.parsed.merchantName)
        assertNull(result.parsed.total)
        verify(exactly = 1) { ocrService.persistImageCopy(uri) }
        coVerify(exactly = 0) { ocrService.processUri(any<Uri>()) }
        assertDaoUntouched()
    }

    @Test
    fun `saveManualReceiptRecord with failing image copy yields null imagePath never the raw uri`() = runTest {
        every { ocrService.persistImageCopy(uri) } throws java.io.IOException("disk full")

        val result = repository.saveManualReceiptRecord(uri)

        assertNull(result.receipt.imagePath)
        assertEquals("[OCR Failed or Skipped]", result.receipt.rawOcrText)
        assertDaoUntouched()
    }

    // ── Parse failure → PARSE_FAILED draft (ReceiptRepository.kt:247-277) ────────

    @Test
    fun `parse failure returns PARSE_FAILED draft preserving OCR text under STORE_RAW`() = runTest {
        val text = "Synthetic text that cannot be parsed"
        coEvery { ocrService.processUri(uri) } returns ocrResult(text, "/path/saved.jpg")
        every { receiptParser.parse(any(), any()) } throws RuntimeException("Parse failed")

        val result = repository.processReceipt(uri)

        val receipt = result.receipt
        assertEquals(text, receipt.rawOcrText)
        assertEquals(ReceiptProcessingStatus.PARSE_FAILED.name, receipt.processingStatus)
        assertEquals("RuntimeException: Parse failed", receipt.parseFailureReason)
        assertEquals("/path/saved.jpg", receipt.imagePath)
        assertNull(receipt.parsedTotal)
        assertNull(receipt.parsedMerchant)
        assertNull(receipt.parsedDate)
        assertEquals("", receipt.currency)
        assertEquals(0f, receipt.confidence, 0f)
        assertEquals(ReceiptSourceType.CAMERA.name, receipt.sourceType)
        assertEquals(ReceiptDocumentType.RETAIL_RECEIPT.name, receipt.documentType)
        assertEquals(0L, receipt.id)
        assertNull(result.parsed.total)
        assertEquals(text, result.ephemeralRawOcrText)
        verify(exactly = 1) { receiptParser.parse(text, "EUR") }
        assertDaoUntouched()
    }

    @Test
    fun `large OCR text is preserved verbatim on parse failure`() = runTest {
        val longText = "A".repeat(5000) + "\nTotal: 99.99 EUR\n" + "B".repeat(3000)
        coEvery { ocrService.processUri(uri) } returns ocrResult(longText, "/path/large.jpg")
        every { receiptParser.parse(any(), any()) } throws RuntimeException("Parse failed")

        val result = repository.processReceipt(uri)

        assertEquals(longText, result.receipt.rawOcrText)
        assertEquals(longText, result.ephemeralRawOcrText)
        assertNull(result.receipt.parsedTotal)
        assertEquals(0f, result.receipt.confidence, 0f)
        assertEquals(ReceiptProcessingStatus.PARSE_FAILED.name, result.receipt.processingStatus)
        assertDaoUntouched()
    }

    @Test
    fun `parse failure under DO_NOT_STORE persists no raw OCR text but keeps ephemeral text in memory only`() = runTest {
        val text = "Synthetic private line"
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawOcrStorageMode = RawStorageMode.DO_NOT_STORE)
        coEvery { ocrService.processUri(uri) } returns ocrResult(text)
        every { receiptParser.parse(any(), any()) } throws RuntimeException("Parse failed")

        val result = repository.processReceipt(uri)

        assertEquals("", result.receipt.rawOcrText)
        assertEquals(ReceiptProcessingStatus.PARSE_FAILED.name, result.receipt.processingStatus)
        assertEquals(text, result.ephemeralRawOcrText)
        assertDaoUntouched()
    }

    @Test
    fun `parse failure under STORE_REDACTED persists only the redaction marker`() = runTest {
        val text = "Synthetic private line"
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_REDACTED)
        coEvery { ocrService.processUri(uri) } returns ocrResult(text)
        every { receiptParser.parse(any(), any()) } throws RuntimeException("Parse failed")

        val result = repository.processReceipt(uri)

        assertEquals("[REDACTED]", result.receipt.rawOcrText)
        assertEquals(text, result.ephemeralRawOcrText)
    }

    @Test
    fun `parse failure reason redacts content uris from the exception message`() = runTest {
        coEvery { ocrService.processUri(uri) } returns ocrResult("Synthetic text")
        every { receiptParser.parse(any(), any()) } throws
            RuntimeException("failed on content://media/42 now")

        val result = repository.processReceipt(uri)

        assertEquals("RuntimeException: failed on [REDACTED_URI] now", result.receipt.parseFailureReason)
    }

    // ── Success draft (ReceiptRepository.kt:280-314) ─────────────────────────────

    private fun stubSuccessfulParse(text: String): ReceiptParser.ParsedReceipt {
        val item = ReceiptParser.LineItem(
            description = "Latte", quantity = 1.0, unitPrice = 10.0, totalPrice = 10.0
        )
        val parsed = ReceiptParser.ParsedReceipt(
            merchantName = "Coffee Shop",
            total = ReceiptParser.sourceMoneyOrNull(12.50, "EUR"),
            subtotal = ReceiptParser.sourceMoneyOrNull(10.0, "EUR"),
            tax = ReceiptParser.sourceMoneyOrNull(2.50, "EUR"),
            date = 1_699_000_000_000L,
            currency = "EUR",
            lineItems = listOf(item),
            confidence = 0.9f,
        )
        coEvery { ocrService.processUri(uri) } returns ocrResult(text)
        every { receiptParser.parse(text, "EUR") } returns parsed
        every { receiptParser.lineItemsToJson(listOf(item)) } returns "[{\"d\":\"Latte\"}]"
        coEvery { merchantNormalizer.normalize("Coffee Shop", true, any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1, "Coffee Shop Normalized", "coffee shop"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.RULE_MATCH
        )
        return parsed
    }

    @Test
    fun `OCR success returns a parsed draft with normalized merchant and no DAO writes`() = runTest {
        val text = "Coffee Shop\nTotal: 12.50 EUR"
        val parsed = stubSuccessfulParse(text)

        val result = repository.processReceipt(uri)

        val receipt = result.receipt
        assertSame(parsed, result.parsed)
        assertEquals(0L, receipt.id)
        assertEquals(text, receipt.rawOcrText)
        assertEquals("/path/to/saved.jpg", receipt.imagePath)
        assertEquals("Coffee Shop Normalized", receipt.parsedMerchant)
        assertEquals(12.50, receipt.parsedTotal!!, 0.0)
        assertEquals(2.50, receipt.parsedTaxAmount!!, 0.0)
        assertEquals(1_699_000_000_000L, receipt.parsedDate)
        assertEquals("[{\"d\":\"Latte\"}]", receipt.parsedItems)
        assertEquals("EUR", receipt.currency)
        assertEquals(0.9f, receipt.confidence, 0f)
        assertEquals(fixedNow, receipt.createdAt)
        assertEquals(text, result.ephemeralRawOcrText)
        verify(exactly = 0) { ocrService.persistImageCopy(any()) }
        assertNothingPersisted()
    }

    @Test
    fun `OCR success under DO_NOT_STORE persists no raw OCR text but keeps parsed fields and ephemeral text`() = runTest {
        val text = "Coffee Shop\nTotal: 12.50 EUR"
        stubSuccessfulParse(text)
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawOcrStorageMode = RawStorageMode.DO_NOT_STORE)

        val result = repository.processReceipt(uri)

        assertEquals("", result.receipt.rawOcrText)
        assertEquals(12.50, result.receipt.parsedTotal!!, 0.0)
        assertEquals("EUR", result.receipt.currency)
        assertEquals(text, result.ephemeralRawOcrText)
        assertDaoUntouched()
    }
}
