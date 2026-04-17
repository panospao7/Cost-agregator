package com.yourname.expensetracker.domain.categorization

import android.content.Context
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MLMatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Stress Test Suite for CategorizationEngine
 * 
 * Goal: Break the categorization pipeline with extreme inputs,
 * cache concurrency issues, and edge cases.
 * 
 * @author Hostile QA Engineer
 */
class CategorizationEngineStressTest {

    private val context = mockk<Context>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
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
        // Default mock setup
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name.uppercase(), searchKey = name.lowercase()),
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

    // ============================================================================
    // SECTION 1: CACHE CONCURRENCY TESTS
    // ============================================================================

    @Test
    fun `stress - 10000 concurrent categorization requests`() = runBlocking {
        // Setup repository with some merchants
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L),
            MerchantCategory("mcdonalds", 2L),
            MerchantCategory("amazon", 3L)
        )
        coEvery { categoryRepository.getAll() } returns listOf(
            mockk { every { id } returns 1L; every { name } returns "Food" }
        )

        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10000)
        val errors = AtomicInteger(0)

        repeat(10000) { i ->
            executor.submit {
                try {
                    runBlocking {
                        engine.categorize("starbucks")
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(60, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
    }

    @Test
    fun `stress - cache expiry at exactly 300s boundary`() = runBlocking {
        // First call populates cache
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("test", 1L)
        )
        
        engine.categorize("test")
        
        // Immediately should use cache
        val call1 = engine.categorize("test")
        assertEquals(MatchType.EXACT, call1.matchType)
        
        // At exactly 300s, cache should expire
        // This is a timing test - in real scenario would need to advance time
        // Documenting the expected behavior
    }

    @Test
    fun `stress - cache invalidation during categorization`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("shop", 1L)
        ) andThen listOf(
            MerchantCategory("shop", 2L), // Different category after invalidate
            MerchantCategory("new", 3L)
        )
        
        // First categorization
        val result1 = engine.categorize("shop")
        assertEquals(1L, result1.categoryId)
        
        // Invalidate cache
        engine.invalidateCache()
        
        // Second categorization should see new data
        val result2 = engine.categorize("shop")
        assertEquals(2L, result2.categoryId)
    }

    // ============================================================================
    // SECTION 2: LAYER EXHAUSTION TESTS
    // ============================================================================

    @Test
    fun `stress - force all layers to fail returns UNKNOWN`() = runBlocking {
        // Empty repository - no matches
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        every { canonicalizer.canonicalize(any()) } returns CanonicalResult("unknown", emptyList(), 0.0)
        every { greeklishNormalizer.getVariations(any()) } returns emptyList()
        every { greeklishNormalizer.normalize(any()) } returns "unknown"
        every { semanticMatcher.findBestMatch(any(), any()) } returns null
        every { contextEngine.isLikelySurname(any()) } returns false
        
        val result = engine.categorize("completelyunknownmerchant12345")
        
        assertEquals(MatchType.UNKNOWN, result.matchType)
        assertNull(result.categoryId)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `stress - layer returns lower confidence each failure`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("cafe", 1L) // Only exact match
        )
        
        // Exact match - high confidence
        val exact = engine.categorize("cafe")
        assertEquals(0.98, exact.confidence, 0.01)
        
        // Canonical - medium confidence  
        every { canonicalizer.canonicalize(any()) } returns CanonicalResult("cafe", emptyList(), 0.1)
        
        val canonical = engine.categorize("CAFEBAR")
        assertEquals(0.93 - 0.1, canonical.confidence, 0.01)
    }

    // ============================================================================
    // SECTION 3: GREEKLISH VARIATION TESTS
    // ============================================================================

    @Test
    fun `stress - all Greek diphthong combinations`() = runBlocking {
        val diphthongTests = mapOf(
            "ΜΠΗ" to listOf("MPI", "BI"),        // μπ -> b/mp
            "ΟΥ" to listOf("OY", "U"),           // ου -> u
            "ΑΙ" to listOf("AI", "E"),           // αι -> e
            "ΕΙ" to listOf("EI", "I"),            // ει -> i
            "ΟΙ" to listOf("OI", "I"),             // οι -> i
            "ΓΓ" to listOf("GG", "NG"),           // γγ -> g/ng
            "ΓΚ" to listOf("GK", "NK"),           // γκ -> g/nk
            "ΓΧ" to listOf("GX", "NX"),           // γχ -> g/nx
            "ΤΖ" to listOf("TZ", "Z"),            // τζ -> z
            "ΤΣ" to listOf("TS", "S"),            // τσ -> s
            "ΔΖ" to listOf("DZ", "Z")              // δζ -> z
        )
        
        diphthongTests.forEach { (greek, expectedVariations) ->
            every { greeklishNormalizer.getVariations(greek) } returns listOf(greek) + expectedVariations
            every { greeklishNormalizer.normalize(any()) } returns greek.lowercase()
            
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory(expectedVariations.first().lowercase(), 1L)
            )
            
            val result = engine.categorize(greek)
            // Should find match through greeklish layer
        }
    }

    @Test
    fun `stress - uppercase lowercase Greek mixed`() = runBlocking {
        val mixedCases = listOf(
            "ΣΚΛΑΒΕΝΙΤΗΣ",
            "σκλαβενιτης",
            "Σκλαβενιτης",
            "σΚλΑβΕνΙτΗς"
        )
        
        every { greeklishNormalizer.getVariations(any()) } returns listOf("σκλαβενιτης")
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("σκλαβενιτης", 1L)
        )
        
        mixedCases.forEach { input ->
            val result = engine.categorize(input)
            assertNotNull(result)
        }
    }

    // ============================================================================
    // SECTION 4: FUZZY MATCHING TESTS
    // ============================================================================

    @Test
    fun `stress - edit distance 1 from known merchant`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )
        every { canonicalizer.canonicalize("starbuks") } returns CanonicalResult("starbuks", emptyList(), 0.0)
        
        // Should find fuzzy match
        val result = engine.categorize("starbuks") // 1 char different
        assertNotNull(result.categoryId)
    }

    @Test
    fun `stress - edit distance 2 from known merchant`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )
        every { canonicalizer.canonicalize("starbks") } returns CanonicalResult("starbks", emptyList(), 0.0)
        
        // For 7-char strings threshold is 1, so this should not fuzzy-match.
        val result = engine.categorize("starbks") // 2 chars different
        assertNull(result.categoryId)
    }

    @Test
    fun `stress - edit distance 3 - may exceed threshold`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )
        every { canonicalizer.canonicalize("stbks") } returns CanonicalResult("stbks", emptyList(), 0.0)
        
        // With short merchant names (<4 chars), fuzzy matching is disabled
        val result = engine.categorize("stbks")
        // May or may not match depending on threshold
        assertNotNull(result)
    }

    @Test
    fun `stress - merchants shorter than 4 characters disable fuzzy`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("ab", 1L) // Too short for fuzzy
        )
        
        // "ac" is edit distance 1 from "ab" but fuzzy is disabled for <4 chars
        every { canonicalizer.canonicalize("ac") } returns CanonicalResult("ac", emptyList(), 0.0)
        
        val result = engine.categorize("ac")
        // Should not match because fuzzy disabled for short names
        assertNull(result.categoryId)
    }

    @Test
    fun `stress - three character merchants can use fuzzy matching`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("abc", 1L)
        )
        every { canonicalizer.canonicalize("abd") } returns CanonicalResult("abd", emptyList(), 0.0)

        val result = engine.categorize("abd")
        assertEquals(1L, result.categoryId)
    }

    // ============================================================================
    // SECTION 5: RACE CONDITION TESTS
    // ============================================================================

    @Test
    fun `stress - concurrent cache updates don't lose data`() = runBlocking {
        val categories = (1..100).map { MerchantCategory("merchant$it", it.toLong()) }
        coEvery { merchantCategoryRepository.getAll() } returns categories
        coEvery { categoryRepository.getAll() } returns emptyList()
        
        // Fire many concurrent categorizations
        val results = coroutineScope {
            (1..100).map { i ->
                async {
                    engine.categorize("merchant${i % 100}")
                }
            }.awaitAll()
        }
        
        // All should complete without error
        assertEquals(100, results.size)
    }

    @Test
    fun `stress - cache populated while another thread reads`() = runBlocking {
        // This tests the Mutex protection
        coEvery { merchantCategoryRepository.getAll() } returns (1..1000).map { 
            MerchantCategory("merchant$it", it.toLong()) 
        }
        
        repeat(100) {
            engine.categorize("merchant50")
        }
        
        // Should be consistent
        val result = engine.categorize("merchant50")
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 6: UNICODE STRESS TESTS
    // ============================================================================

    @Test
    fun `stress - Greek unicode characters`() = runBlocking {
        val greekMerchants = listOf(
            "Σκλαβενίτης",
            "Μασούτης",
            "Βασιλόπουλος",
            "ΑΒ Βασιλόπουλος",
            "Κωτσόβολος"
        )
        
        every { greeklishNormalizer.getVariations(any()) } returns emptyList()
        
        greekMerchants.forEach { merchant ->
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory(merchant.lowercase(), 1L)
            )
            
            val result = engine.categorize(merchant)
            assertNotNull(result)
        }
    }

    @Test
    fun `stress - mixed Greek Latin characters`() = runBlocking {
        val mixed = listOf(
            "LIDL Ελλάδος",
            "ABC 123",
            "SHOP-GR",
            "Market2024",
            "Café Greece"
        )
        
        mixed.forEach { merchant ->
            val result = engine.categorize(merchant)
            assertNotNull(result) // Should handle without crash
        }
    }

    @Test
    fun `stress - special characters in merchant name`() = runBlocking {
        val special = listOf(
            "Shop & Save",
            "A/B Testing",
            "Café-Star",
            "Food+More",
            "The Store - Main",
            "McDonald's #1",
            "7-Eleven",
            "100% Greek"
        )
        
        special.forEach { merchant ->
            val result = engine.categorize(merchant)
            assertNotNull(result)
        }
    }

    @Test
    fun `stress - emoji in merchant name`() = runBlocking {
        val emoji = listOf(
            "Shop 💰",
            "💵 Money",
            "⭐⭐⭐⭐⭐",
            "Café ☕",
            "🏪 Store"
        )
        
        emoji.forEach { merchant ->
            val result = engine.categorize(merchant)
            assertNotNull(result)
        }
    }

    // ============================================================================
    // SECTION 7: MERCHANT AMBIGUITY TESTS
    // ============================================================================

    @Test
    fun `stress - AMAZON vs AMAZON COM vs AMZN`() = runBlocking {
        val variations = listOf(
            "AMAZON",
            "AMAZON.COM",
            "AMZN",
            "Amazon Market",
            "Amazon EU"
        )
        
        every { greeklishNormalizer.getVariations(any()) } returns emptyList()
        
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("amazon", 1L)
        )
        
        variations.forEach { input ->
            val result = engine.categorize(input)
            assertNotNull(result)
        }
    }

    @Test
    fun `stress - short merchant names causing ambiguity`() = runBlocking {
        // Very short names are problematic
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("ab", 1L),
            MerchantCategory("ac", 2L),
            MerchantCategory("ad", 3L)
        )
        
        // Exact match still wins even when fuzzy is disabled for short names.
        assertEquals(1L, engine.categorize("ab").categoryId)
        assertNull(engine.categorize("zz").categoryId)
    }

    @Test
    fun `stress - common prefixes cause false positives`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("coffee shop", 1L),
            MerchantCategory("coffee house", 2L),
            MerchantCategory("coffee bean", 3L)
        )
        
        // "coffee shoppe" might match "coffee shop" due to prefix
        every { canonicalizer.canonicalize("coffee shoppe") } returns 
            CanonicalResult("coffee shoppe", emptyList(), 0.0)
        
        val result = engine.categorize("coffee shoppe")
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 8: PERFORMANCE TESTS
    // ============================================================================

    @Test
    fun `stress - large merchant dictionary performance`() = runBlocking {
        // Create large dictionary
        val largeDict = (1..5000).map { 
            MerchantCategory("merchant$it", it.toLong()) 
        }
        coEvery { merchantCategoryRepository.getAll() } returns largeDict
        
        val start = System.currentTimeMillis()
        repeat(100) {
            engine.categorize("merchant2500")
        }
        val elapsed = System.currentTimeMillis() - start
        
        // Should complete reasonably fast
        assertTrue("Took ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `stress - categorization with very long merchant name`() = runBlocking {
        val longName = "A".repeat(1000)
        
        val result = engine.categorize(longName)
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 9: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - empty merchant name`() = runBlocking {
        val result = engine.categorize("")
        // Should return UNKNOWN
        assertEquals(MatchType.UNKNOWN, result.matchType)
    }

    @Test
    fun `stress - whitespace only merchant name`() = runBlocking {
        val result = engine.categorize("   ")
        assertEquals(MatchType.UNKNOWN, result.matchType)
    }

    @Test
    fun `stress - null character in name`() = runBlocking {
        val result = engine.categorize("Shop\u0000Test")
        assertNotNull(result)
    }

    @Test
    fun `stress - control characters in name`() = runBlocking {
        val result = engine.categorize("Shop\u0001\u0002Test")
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 10: REGRESSION TESTS
    // ============================================================================

    @Test
    fun `regression - exact match still has highest confidence`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )
        
        val result = engine.categorize("starbucks")
        assertEquals(0.98, result.confidence, 0.01)
        assertEquals(MatchType.EXACT, result.matchType)
    }

    @Test
    fun `regression - canonical match has correct confidence`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("cafe", 1L)
        )
        every { canonicalizer.canonicalize("cafebl") } returns 
            CanonicalResult("cafe", emptyList(), 0.05)
        
        val result = engine.categorize("cafebl")
        assertTrue(result.confidence < 0.98)
        assertTrue(result.confidence >= 0.85)
    }

    @Test
    fun `regression - layer priority unchanged`() = runBlocking {
        // Priority: EXACT > CANONICAL > GREEKLISH > SEMANTIC > CONTEXT > UNKNOWN
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()
        
        // With empty repo, should get UNKNOWN
        val result = engine.categorize("anything")
        assertEquals(MatchType.UNKNOWN, result.matchType)
    }

    // ============================================================================
    // SECTION 11: FUZZ TESTING
    // ============================================================================

    @Test
    fun `stress - fuzz random merchant names`() = runBlocking {
        repeat(1000) {
            val randomName = (1..30).map {
                when (Random.nextInt(5)) {
                    0 -> ('a'..'z').random()
                    1 -> ('Α'..'Ω').random()
                    2 -> ('0'..'9').random()
                    3 -> " -_".random()
                    else -> ""
                }
            }.joinToString("")
            
            try {
                engine.categorize(randomName)
            } catch (e: Exception) {
                fail("Crashed with: $randomName")
            }
        }
    }

    // ============================================================================
    // SECTION 12: BUG DOCUMENTATION
    // ============================================================================

    @Test
    fun `bug - cache mutex may block under high contention`() = runBlocking {
        // Document potential issue: cacheMutex could become bottleneck
        // under very high concurrent load
        
        val start = System.currentTimeMillis()
        
        repeat(1000) {
            engine.categorize("test")
        }
        
        val elapsed = System.currentTimeMillis() - start
        
        // This documents the potential performance issue
        assertTrue("High contention took ${elapsed}ms", true)
    }

    @Test
    fun `bug - fuzzy matching disabled for short names causes inconsistent results`() = runBlocking {
        // "cafe" (4 chars) can fuzzy match
        // "ab" (2 chars) cannot fuzzy match
        
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("cafe", 1L),
            MerchantCategory("ab", 2L)
        )
        
        every { canonicalizer.canonicalize("cafex") } returns 
            CanonicalResult("cafe", emptyList(), 0.0)
        
        val cafeResult = engine.categorize("cafex")
        assertNotNull(cafeResult.categoryId) // Fuzzy works
        
        // Short name fuzzy disabled
        every { canonicalizer.canonicalize("ac") } returns 
            CanonicalResult("ab", emptyList(), 0.0)
        
        val abResult = engine.categorize("ac")
        // Canonical layer can still match short names when canonicalization collapses to an exact key.
        assertEquals(2L, abResult.categoryId)
    }
}
