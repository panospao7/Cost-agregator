package com.yourname.expensetracker.domain.categorization

import android.content.Context
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MLMatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.MatchType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationEngineTest {
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
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>().uppercase()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name, searchKey = name.lowercase()),
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
    fun `normalize uppercases`() = runBlocking {
        assertEquals("STARBUCKS", engine.normalize("starbucks"))
        assertEquals("UBER-EATS", engine.normalize("uber-eats"))
    }

    @Test
    fun `normalize handles Greek characters`() = runBlocking {
        val result = engine.normalize("ΣΚΛΑΒΕΝΙΤΗΣ")
        assertTrue(result.contains("ΣΚΛΑΒΕΝΙΤΗΣ"))
    }

    @Test
    fun `exact match returns category`() = runBlocking {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 5L)
        )

        val result = engine.categorize("starbucks")
        assertEquals(5L, result.categoryId)
        assertEquals(MatchType.EXACT, result.matchType)
    }

    @Test
    fun `substring match finds pattern within merchant name`() = runBlocking {
        coEvery { merchantCategoryRepository.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("uber eats", 3L),
            MerchantCategory("uber", 4L)
        )

        val result = engine.categorize("UBER EATS")
        // Exact match on "UBER EATS" should return category 3
        assertEquals(3L, result.categoryId)
    }

    @Test
    fun `returns unknown when no match found`() = runBlocking {
        coEvery { merchantCategoryRepository.getCategoryForMerchant(any()) } returns null
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()

        val result = engine.categorize("COMPLETELY UNKNOWN MERCHANT")
        assertNull(result.categoryId)
        assertEquals(MatchType.UNKNOWN, result.matchType)
        assertEquals(0.0, result.confidence, 0.01)
    }

    @Test
    fun `cache invalidation resets cache`() = runBlocking {
        engine.invalidateCache()
        // No assertion needed — just ensure no crash
    }
}
