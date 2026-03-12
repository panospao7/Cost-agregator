package com.yourname.expensetracker.domain.categorization

import android.content.Context
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MLMatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationEngineDebugTest {
    private val context = mockk<Context>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<NewMerchantNormalizer>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val categoryRepositoryProvider = mockk<javax.inject.Provider<CategoryRepository>>()
    private val canonicalizer = mockk<MerchantCanonicalizer>(relaxed = true)
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val semanticMatcher = mockk<SemanticKeywordMatcher>(relaxed = true)
    private val contextEngine = mockk<ContextualInferenceEngine>(relaxed = true)
    private lateinit var engine: CategorizationEngine

    @Before
    fun setup() {
        // Let normalizer pass through the raw string lowercase for easier testing of trace
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>().lowercase()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name, searchKey = name),
                alias = null,
                confidence = 1.0f,
                matchType = MLMatchType.EXACT_MATCH
            )
        }
        every { categoryRepositoryProvider.get() } returns categoryRepository
        coEvery { categoryRepository.getAll() } returns emptyList()
        engine = CategorizationEngine(
            merchantCategoryRepository,
            merchantNormalizer,
            categoryRepositoryProvider,
            canonicalizer,
            greeklishNormalizer,
            semanticMatcher,
            contextEngine
        )
    }

    @Test
    fun `debugCategorize returns trace with correct layer results for canonical match`() = runBlocking {
        // Setup mock data for Canonical hit
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("sklavenitis", 1L)
        )
        
        // Sklavenitis Lagka -> normalized: "sklavenitis lagka" -> canonical: "sklavenitis"
        // This should fail Layer 1 (Exact), but pass Layer 2 (Canonical)
        val trace = engine.debugCategorize("Sklavenitis Lagka")
        
        assertEquals("Sklavenitis Lagka", trace.inputMerchant)
        assertEquals("sklavenitis lagka", trace.normalizedMerchant)
        assertEquals("sklavenitis", trace.canonicalMerchant)
        
        val layer1 = trace.layerResults.find { it.layerName.contains("Exact") }
        val layer2 = trace.layerResults.find { it.layerName.contains("Canonical") }
        
        assertNotNull(layer1)
        assertFalse(layer1!!.matchFound)
        
        assertNotNull(layer2)
        assertTrue(layer2!!.matchFound)
        assertEquals(1L, layer2.categoryId)
        
        assertEquals(MatchType.CANONICAL, trace.finalResult.matchType)
        assertEquals(1L, trace.finalResult.categoryId)
    }

    @Test
    fun `learnMerchantCategory invalidates cache and allows immediate re-categorization`() = runBlocking {
        // 1. Initial state: Unknown merchant
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        var result = engine.categorize("NEW_MERCHANT")
        assertEquals(MatchType.UNKNOWN, result.matchType)

        // 2. Learn the merchant
        val merchantName = "NEW_MERCHANT"
        val categoryId = 5L
        
        // Mock the DAO to return the new mapping after insertion
        val newMapping = MerchantCategory(merchantName.lowercase(), categoryId)
        coEvery { merchantCategoryRepository.getAll() } returns listOf(newMapping)
        coEvery { merchantCategoryRepository.insert(any()) } just runs
        
        // This should trigger invalidateCache()
        engine.learnMerchantCategory(merchantName, categoryId)

        // 3. Categorize again - should now work immediately
        result = engine.categorize(merchantName)
        
        assertEquals(MatchType.EXACT, result.matchType)
        assertEquals(categoryId, result.categoryId)
        
        // Verify DAO was called
        coVerify { merchantCategoryRepository.insert(any()) }
    }
}
