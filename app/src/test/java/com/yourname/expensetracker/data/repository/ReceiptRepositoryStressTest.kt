package com.yourname.expensetracker.data.repository

import android.net.Uri
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import dagger.Lazy
import com.yourname.expensetracker.domain.debug.DebugIssueDetector
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Ignore("Stress test: may hang in CI, run manually")
class ReceiptRepositoryStressTest {

    private val scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val ocrService = mockk<ReceiptOcrService>(relaxed = true)
    private val receiptParser = mockk<ReceiptParser>(relaxed = true)
    private val statementParser = mockk<BankStatementParser>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val crossSourceDeduplication = mockk<CrossSourceDeduplication>(relaxed = true)
    private val debugIssueDetector = mockk<DebugIssueDetector>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val warrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true)

    private lateinit var repository: ReceiptRepository

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { scannedReceiptDao.update(any()) } returns Unit
        coEvery { scannedReceiptDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { scannedReceiptDao.getById(any()) } returns null
        coEvery { scannedReceiptDao.getCount() } returns 0
        coEvery { scannedReceiptDao.getAll() } returns emptyList()
        coEvery { scannedReceiptDao.getReceiptsPaged(any(), any()) } returns emptyList()
        coEvery { scannedReceiptDao.delete(any()) } returns Unit
        coEvery { scannedReceiptDao.deleteAll() } returns Unit
        coEvery { scannedReceiptDao.linkToExpense(any(), any()) } returns Unit
        coEvery { expenseDao.insertAtomic(any()) } returns 1L
        coEvery { expenseDao.getAllFlow(any()) } returns flowOf(emptyList())
        coEvery { pendingReviewDao.insert(any()) } returns 1L
        coEvery { pendingReviewDao.getPending(any()) } returns emptyList()
        coEvery { warrantyUseCase.execute(any(), any()) } returns WarrantyCreationResult.Failure("test")

        repository = ReceiptRepository(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = ocrService,
            receiptParser = receiptParser,
            statementParser = statementParser,
            categorizationEngine = categorizationEngine,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            crossSourceDeduplication = crossSourceDeduplication,
            debugIssueDetector = debugIssueDetector,
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = timeProvider,
            warrantyUseCase = object : Lazy<AutoCreateWarrantyFromReceiptUseCase> { override fun get() = warrantyUseCase },
            coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            receiptLifecycleCoordinator = mockk(relaxed = true),
        )
    }

    @Test
    fun `stress - processReceipt OCR success returns receipt and parsed`() = runTest {
        val uri = Uri.parse("content://test/receipt.jpg")
        val ocrResult = OcrResult(
            fullText = "Coffee Shop\nTotal: 12.50 EUR",
            blocks = emptyList(),
            savedImagePath = "/path/to/saved.jpg",
        )
        val parsed = ReceiptParser.ParsedReceipt(
            merchantName = "Coffee Shop",
            total = 12.50,
            subtotal = 10.0,
            tax = 2.50,
            date = System.currentTimeMillis(),
            currency = "EUR",
            lineItems = emptyList(),
            confidence = 0.9f,
        )

        coEvery { ocrService.processUri(uri) } returns ocrResult
        every { receiptParser.parse(ocrResult.fullText) } returns parsed
        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1, "Coffee Shop", "coffee shop"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.RULE_MATCH
        )
        coEvery { hybridClassifier.classify(any(), any()) } returns ClassificationResult(
            categoryId = 1L,
            categoryName = "Food",
            confidence = 0.9f,
            alternatives = emptyList(),
            matchType = MatchType.RULE_MATCH
        )

        val (receipt, resultParsed) = repository.processReceipt(uri)

        assertNotNull(receipt)
        assertEquals(12.50, resultParsed.total!!, 0.001)
        assertEquals("Coffee Shop", resultParsed.merchantName)
        coVerify { scannedReceiptDao.insert(any()) }
    }

    @Test
    fun `stress - processReceipt OCR failure falls back to manual record`() = runTest {
        val uri = Uri.parse("content://test/receipt.jpg")
        coEvery { ocrService.processUri(uri) } throws RuntimeException("OCR failed")
        every { ocrService.persistImageCopy(uri) } returns "/fallback/path.jpg"

        val (receipt, parsed) = repository.processReceipt(uri)

        assertNotNull(receipt)
        assertEquals("Scan Failed: OCR failed", receipt.rawOcrText)
        assertEquals(null, parsed.total)
        coVerify { scannedReceiptDao.insert(any()) }
        coVerify { scannedReceiptDao.update(any()) }
        verify(exactly = 1) { ocrService.persistImageCopy(uri) }
        coVerify(exactly = 0) { ocrService.processImage(uri) }
    }

    @Test
    fun `stress - processReceipt parse failure preserves OCR text`() = runTest {
        val uri = Uri.parse("content://test/receipt.jpg")
        val ocrResult = OcrResult(
            fullText = "Garbage text that cannot be parsed",
            blocks = emptyList(),
            savedImagePath = "/path/saved.jpg"
        )

        coEvery { ocrService.processUri(uri) } returns ocrResult
        every { receiptParser.parse(any()) } throws RuntimeException("Parse failed")

        val (receipt, parsed) = repository.processReceipt(uri)

        assertNotNull(receipt)
        assertEquals("Garbage text that cannot be parsed", receipt.rawOcrText)
        assertEquals(null, receipt.parsedTotal)
        assertEquals(0f, receipt.confidence, 0.001f)
        assertEquals(null, parsed.total)
        coVerify { scannedReceiptDao.insert(any()) }
    }

    @Test
    fun `stress - saveManualReceiptRecord returns receipt when OCR fails`() = runTest {
        val uri = Uri.parse("content://test/image.jpg")
        every { ocrService.persistImageCopy(uri) } returns "/manual/path.jpg"

        val (receipt, parsed) = repository.saveManualReceiptRecord(uri)

        assertNotNull(receipt)
        assertEquals("[OCR Failed or Skipped]", receipt.rawOcrText)
        assertEquals(null, parsed.merchantName)
        assertEquals(null, parsed.total)
        coVerify { scannedReceiptDao.insert(any()) }
        verify(exactly = 1) { ocrService.persistImageCopy(uri) }
        coVerify(exactly = 0) { ocrService.processImage(uri) }
    }

    @Test
    fun `stress - processBatch processes multiple URIs`() = runTest {
        val uris = listOf(
            Uri.parse("content://test/1.jpg"),
            Uri.parse("content://test/2.jpg")
        )
        val ocrResult = OcrResult("Total 10.00", emptyList(), "/path.jpg")
        val parsed = ReceiptParser.ParsedReceipt(
            merchantName = "Store",
            total = 10.0,
            subtotal = null,
            tax = null,
            date = System.currentTimeMillis(),
            currency = "EUR",
            lineItems = emptyList(),
            confidence = 0.8f
        )

        coEvery { ocrService.processUri(any<Uri>()) } returns ocrResult
        every { receiptParser.parse(any()) } returns parsed
        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1, "Store", "store"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.RULE_MATCH
        )
        coEvery { hybridClassifier.classify(any(), any()) } returns ClassificationResult(
            categoryId = 1L,
            categoryName = "Food",
            confidence = 0.9f,
            alternatives = emptyList(),
            matchType = MatchType.RULE_MATCH
        )

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val result = repository.processBatch(uris) { done, total ->
            progressCalls.add(Pair(done, total))
        }

        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(2, progressCalls.size)
    }

    @Test
    fun `stress - processBatch handles OCR failure in one item`() = runTest {
        val uris = listOf(
            Uri.parse("content://test/ok.jpg"),
            Uri.parse("content://test/fail.jpg")
        )
        val ocrResult = OcrResult("Total 5.00", emptyList(), "/path.jpg")
        val parsed = ReceiptParser.ParsedReceipt(
            merchantName = "Store",
            total = 5.0,
            subtotal = null,
            tax = null,
            date = System.currentTimeMillis(),
            currency = "EUR",
            lineItems = emptyList(),
            confidence = 0.8f
        )

        coEvery { ocrService.processUri(uris[0]) } returns ocrResult
        coEvery { ocrService.processUri(uris[1]) } throws RuntimeException("OCR error")
        every { ocrService.persistImageCopy(uris[1]) } returns "/fallback.jpg"
        every { receiptParser.parse(any()) } returns parsed
        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1, "Store", "store"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.RULE_MATCH
        )

        val result = repository.processBatch(uris) { _, _ -> }

        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
        verify(exactly = 1) { ocrService.persistImageCopy(uris[1]) }
        coVerify(exactly = 0) { ocrService.processImage(uris[1]) }
    }

    @Test
    fun `stress - allReceipts returns flow`() = runTest {
        val flow = repository.allReceipts
        assertNotNull(flow)
    }

    @Test
    fun `stress - getReceiptCount returns count`() = runTest {
        coEvery { scannedReceiptDao.getCount() } returns 42
        val count = repository.getReceiptCount()
        assertEquals(42, count)
    }

    @Test
    fun `stress - getReceiptById returns receipt when exists`() = runTest {
        val receipt = ScannedReceipt(
            imagePath = "/path",
            rawOcrText = "text",
            parsedTotal = 10.0,
            parsedMerchant = "Store",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        coEvery { scannedReceiptDao.getById(1L) } returns receipt

        val result = repository.getReceiptById(1L)
        assertEquals(receipt, result)
    }

    @Test
    fun `stress - createExpenseFromReceipt returns success`() = runTest {
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1, "Coffee Shop", "coffee shop"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.RULE_MATCH
        )
        coEvery { hybridClassifier.classify(any(), any()) } returns ClassificationResult(
            categoryId = 1L,
            categoryName = "Food",
            confidence = 0.9f,
            alternatives = emptyList(),
            matchType = MatchType.RULE_MATCH
        )
        coEvery { expenseDao.insertAtomic(any()) } returns 1L

        val result = repository.createExpenseFromReceipt(
            receiptId = 1L,
            merchant = "Coffee Shop",
            amount = 12.50,
            currency = "EUR",
            categoryId = 1L
        )

        assert(result is Result.Success)
        assertEquals(1L, (result as Result.Success).data)
        coVerify { scannedReceiptDao.linkToExpense(1L, 1L) }
    }

    @Test
    fun `stress - large receipt OCR text preserved on parse failure`() = runTest {
        val uri = Uri.parse("content://test/large.jpg")
        val longText = "A".repeat(5000) + "\nTotal: 99.99 EUR\n" + "B".repeat(3000)
        val ocrResult = OcrResult(
            fullText = longText,
            blocks = emptyList(),
            savedImagePath = "/path/large.jpg"
        )

        coEvery { ocrService.processUri(uri) } returns ocrResult
        every { receiptParser.parse(any()) } throws RuntimeException("Parse failed")

        val (receipt, parsed) = repository.processReceipt(uri)

        assertNotNull(receipt)
        assertEquals(longText, receipt.rawOcrText)
        assertEquals(null, receipt.parsedTotal)
        assertEquals(0f, receipt.confidence, 0.001f)
        assertEquals(null, parsed.total)
        coVerify { scannedReceiptDao.insert(any()) }
    }

    @Test
    fun `stress - deleteReceipt calls dao and ocrService`() = runTest {
        val receipt = ScannedReceipt(
            id = 1,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "text",
            parsedTotal = 10.0,
            parsedMerchant = "Store",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        every { ocrService.deleteImage(any()) } just runs

        repository.deleteReceipt(receipt)

        verify { ocrService.deleteImage("/path/to/image.jpg") }
        coVerify { scannedReceiptDao.delete(receipt) }
    }
}