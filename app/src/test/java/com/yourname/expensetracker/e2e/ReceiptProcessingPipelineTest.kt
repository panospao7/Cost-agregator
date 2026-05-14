package com.yourname.expensetracker.e2e

import android.content.Context
import android.net.Uri
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.ContextualInferenceEngine
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MatchType
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.categorization.SemanticKeywordMatcher
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

class ReceiptProcessingPipelineTest : AnalyticsEngineTestBase() {

    private lateinit var receiptParser: ReceiptParser
    private lateinit var merchantNormalizer: MerchantNormalizer
    private lateinit var categorizationEngine: CategorizationEngine

    private lateinit var merchantNormalizationRepository: MerchantNormalizationRepository
    private lateinit var merchantCategoryRepository: MerchantCategoryRepository
    private lateinit var categoryRepositoryProvider: Provider<CategoryRepository>

    private val ocrService = mockk<ReceiptOcrService>(relaxed = true)

    @Before
    override fun setUp() {
        super.setUp()

        receiptParser = ReceiptParser(MerchantRulesRepository(), timeProvider = timeProvider)

        merchantNormalizationRepository = mockk(relaxed = true)
        coEvery { merchantNormalizationRepository.getAliasByNormalizedKey(any()) } returns null
        coEvery { merchantNormalizationRepository.getCanonicalBySearchKey(any()) } returns null
        coEvery { merchantNormalizationRepository.getTopMerchants(any()) } returns emptyList()

        val greeklishNormalizer = GreeklishNormalizer()
        merchantNormalizer = MerchantNormalizer(
            repository = merchantNormalizationRepository,
            merchantRules = MerchantRulesRepository(),
            greeklishNormalizer = greeklishNormalizer,
            context = mockk<Context>(relaxed = true),
            timeProvider = timeProvider
        )

        merchantCategoryRepository = mockk(relaxed = true)
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()

        categoryRepositoryProvider = mockk()
        io.mockk.every { categoryRepositoryProvider.get() } returns categoryRepository

        categorizationEngine = CategorizationEngine(
            merchantCategoryRepository = merchantCategoryRepository,
            merchantNormalizer = merchantNormalizer,
            categoryRepositoryProvider = categoryRepositoryProvider,
            canonicalizer = MerchantCanonicalizer(),
            greeklishNormalizer = greeklishNormalizer,
            semanticMatcher = SemanticKeywordMatcher(greeklishNormalizer),
            contextEngine = ContextualInferenceEngine(timeProvider = timeProvider),
            timeProvider = timeProvider
        )
    }

    @Test
    fun `ocr text parsed and categorized correctly`() = runTest {
        // Arrange
        val ocrText = "Lidl\nTotal: €45.30\n05.03.2026"
        coEvery { ocrService.processImage(any()) } returns createOcrResult(ocrText)
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory(merchantPattern = "lidl", categoryId = 2L)
        )

        // Act
        val ocrResult = ocrService.processImage(Uri.parse("file:///test.jpg"))
        val parsed = receiptParser.parse(ocrResult.fullText)
        val categorization = categorizationEngine.categorizeWithContext(
            merchant = parsed.merchantName ?: "",
            amount = parsed.total ?: 0.0,
            timestamp = fixedNow
        )

        // Assert
        assertNotNull(parsed.total)
        assertApproxEquals(45.30, parsed.total ?: 0.0, 0.01)
        assertEquals("LIDL", parsed.merchantName)
        assertEquals(2L, categorization.categoryId)
        assertEquals("Groceries", categorization.categoryName)
        assertEquals(MatchType.EXACT, categorization.matchType)
        assertApproxEquals(0.98, categorization.confidence, 0.0001)
    }

    @Test
    fun `ocr failure handled gracefully`() = runTest {
        // Arrange
        coEvery { ocrService.processImage(any()) } throws IllegalStateException("OCR failed")

        // Act
        val parsed = runCatching {
            val ocrResult = ocrService.processImage(Uri.parse("file:///test.jpg"))
            receiptParser.parse(ocrResult.fullText)
        }.getOrNull()

        // Assert
        assertNull(parsed)
    }

    @Test
    fun `greek text normalization parses and categorizes correctly`() = runTest {
        // Arrange
        val ocrText = "Σκλαβενίτης\nΣΥΝΟΛΟ 12,40\n05.03.2026"
        coEvery { ocrService.processImage(any()) } returns createOcrResult(ocrText)
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory(merchantPattern = "sklavenitis", categoryId = 2L)
        )

        // Act
        val ocrResult = ocrService.processImage(Uri.parse("file:///test.jpg"))
        val parsed = receiptParser.parse(ocrResult.fullText)
        val categorization = categorizationEngine.categorize(parsed.merchantName ?: "")

        // Assert
        assertNotNull(parsed.total)
        assertApproxEquals(12.40, parsed.total ?: 0.0, 0.01)
        assertNotNull(parsed.merchantName)
        assertTrue(parsed.merchantName!!.contains("ΣΚΛΑΒΕΝ"))
        assertEquals(2L, categorization.categoryId)
        assertEquals("Groceries", categorization.categoryName)
        assertEquals(MatchType.GREEKLISH, categorization.matchType)
        assertApproxEquals(0.90, categorization.confidence, 0.0001)
    }

    @Test
    fun `unknown merchant categorized as Uncategorized`() = runTest {
        // Arrange
        val ocrText = "QWERTYZX\nTOTAL 19.99\n05.03.2026"
        coEvery { ocrService.processImage(any()) } returns createOcrResult(ocrText)
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()

        // Act
        val ocrResult = ocrService.processImage(Uri.parse("file:///test.jpg"))
        val parsed = receiptParser.parse(ocrResult.fullText)
        val categorization = categorizationEngine.categorize(parsed.merchantName ?: "")
        val finalCategoryName = categorization.categoryName ?: "Uncategorized"

        // Assert
        assertNotNull(parsed.total)
        assertApproxEquals(19.99, parsed.total ?: 0.0, 0.01)
        assertEquals("QWERTYZX", parsed.merchantName)
        assertEquals("Uncategorized", finalCategoryName)
        assertNull(categorization.categoryId)
        assertEquals(MatchType.UNKNOWN, categorization.matchType)
        assertApproxEquals(0.0, categorization.confidence, 0.0001)
    }

    private fun createOcrResult(text: String): OcrResult {
        return OcrResult(
            fullText = text,
            blocks = emptyList(),
            savedImagePath = "/tmp/receipt.jpg"
        )
    }
}