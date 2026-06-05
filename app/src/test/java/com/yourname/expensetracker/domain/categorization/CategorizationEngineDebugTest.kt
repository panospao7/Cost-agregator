package com.yourname.expensetracker.domain.categorization

import android.content.Context
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MLMatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryInsertResult
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.util.TimeProvider
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
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
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
        every { timeProvider.now() } returns 1_710_000_000_000L
        coEvery { categoryRepository.getAll() } returns emptyList()
        engine = CategorizationEngine(
            merchantCategoryRepository,
            merchantNormalizer,
            categoryRepositoryProvider,
            canonicalizer,
            greeklishNormalizer,
            semanticMatcher,
            contextEngine,
            timeProvider
        )
    }

    @Test
    fun `debugCategorize returns trace with correct layer results for canonical match`() = runBlocking {
        // Setup mock data for Canonical hit
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("sklavenitis", 1L)
        )
        
        // Mock canonicalizer to actually return "sklavenitis" for "sklavenitis lagka"
        every { canonicalizer.canonicalize("sklavenitis lagka") } returns CanonicalResult(
            canonicalName = "sklavenitis",
            strippedParts = listOf("lagka"),
            confidencePenalty = 0.05
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
        coEvery { merchantCategoryRepository.insert(any()) } coAnswers {
            // Real repository calls invalidateAllCaches() on insert success
            engine.invalidateAllCaches()
            MerchantCategoryInsertResult.Inserted(1L)
        }
        
        // This should trigger invalidateCache()
        engine.learnMerchantCategory(merchantName, categoryId)

        // 3. Categorize again - should now work immediately
        result = engine.categorize(merchantName)
        
        assertEquals(MatchType.EXACT, result.matchType)
        assertEquals(categoryId, result.categoryId)
        
        // Verify DAO was called
        coVerify { merchantCategoryRepository.insert(any()) }
    }

    @Test
    fun `learnMerchantCategory handles insert conflict without crashing`() = runBlocking {
        coEvery { merchantCategoryRepository.insert(any()) } returns MerchantCategoryInsertResult.Conflict

        // Should not throw
        engine.learnMerchantCategory("EXISTING_MERCHANT", 5L)

        // Verify insert was called
        coVerify { merchantCategoryRepository.insert(any()) }
    }

    @Test
    fun `invalidateAllCaches clears cache and next categorize reloads from repository`() = runBlocking {
        // First call: cache is populated
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )
        val result1 = engine.categorize("starbucks")
        assertEquals(MatchType.EXACT, result1.matchType)

        // Change the repository data
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("costa", 2L)
        )

        // Without invalidation, the old cached data would still be used
        // After invalidation, the new data should be used
        engine.invalidateAllCaches()

        val result2 = engine.categorize("costa")
        assertEquals(MatchType.EXACT, result2.matchType)
        assertEquals(2L, result2.categoryId)
    }

    @Test
    fun `traceDecision stores hashed merchant key not raw name`() = runBlocking {
        val merchantName = "McDonald's"
        val categoryId = 5L
        
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        coEvery { merchantCategoryRepository.insert(any()) } returns MerchantCategoryInsertResult.Inserted(1L)
        
        engine.learnMerchantCategory(merchantName, categoryId)
        
        val decisions = engine.getRecentDecisions()
        assertTrue("Trace should contain decisions", decisions.isNotEmpty())
        
        val lastDecision = decisions.last()
        // The hashed key for "McDonald's" should be "mcdonalds"
        assertTrue(
            "Trace should contain hashed key 'mcdonalds', not raw name. Got: $lastDecision",
            lastDecision.contains("mcdonalds")
        )
        assertFalse(
            "Trace should NOT contain raw merchant name 'McDonald's'. Got: $lastDecision",
            lastDecision.contains("McDonald")
        )
    }

    @Test
    fun `traceDecision uses timeProvider not wall clock`() = runBlocking {
        val fixedTime = 1_710_000_000_000L
        every { timeProvider.now() } returns fixedTime
        
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        coEvery { merchantCategoryRepository.insert(any()) } returns MerchantCategoryInsertResult.Inserted(1L)
        
        engine.learnMerchantCategory("TEST_MERCHANT", 5L)
        
        val decisions = engine.getRecentDecisions()
        val lastDecision = decisions.last()
        assertTrue(
            "Trace timestamp should be from timeProvider ($fixedTime). Got: $lastDecision",
            lastDecision.startsWith("$fixedTime|")
        )
    }

    @Test
    fun `getRecentDecisions returns immutable snapshot`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        coEvery { merchantCategoryRepository.insert(any()) } returns MerchantCategoryInsertResult.Inserted(1L)
        
        engine.learnMerchantCategory("MERCHANT_A", 1L)
        
        val snapshot1 = engine.getRecentDecisions()
        val size1 = snapshot1.size
        
        engine.learnMerchantCategory("MERCHANT_B", 2L)
        
        val snapshot2 = engine.getRecentDecisions()
        assertEquals("Old snapshot should not grow", size1, snapshot1.size)
        assertTrue("New snapshot should have more entries", snapshot2.size > size1)
    }
}
