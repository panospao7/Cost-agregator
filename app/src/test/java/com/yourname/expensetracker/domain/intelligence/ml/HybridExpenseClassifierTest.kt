package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.CategorizationResult
import com.yourname.expensetracker.domain.categorization.MatchType as CategorizationMatchType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

class HybridExpenseClassifierTest {
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val nbClassifier = mockk<ExpenseCategoryClassifier>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }
    private lateinit var hybridClassifier: HybridExpenseClassifier

    private val foodCategory = Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")
    private val groceriesCategory = Category(id = 2L, name = "Groceries", icon = "shop", color = "#CCCCCC")
    private val miscCategory = Category(id = 3L, name = "Miscellaneous", icon = "misc", color = "#888888")
    private val uncategorizedCategory = Category(id = 4L, name = "Uncategorized", icon = "misc", color = "#888888")

    @Before
    fun setup() {
        coEvery { categoryRepository.getAll() } returns listOf(foodCategory, groceriesCategory, miscCategory, uncategorizedCategory)
        hybridClassifier = HybridExpenseClassifier(context, categoryRepository, categorizationEngine, nbClassifier, timeProvider)
    }

    @Test
    fun `invalidateCategorySnapshot refreshes renamed categories without restart`() = runBlocking {
        coEvery { categoryRepository.getAll() } returnsMany listOf(
            listOf(foodCategory, uncategorizedCategory),
            listOf(foodCategory.copy(name = "Dining"), uncategorizedCategory)
        )
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = foodCategory.id,
            categoryName = foodCategory.name,
            confidence = 0.95,
            matchType = CategorizationMatchType.EXACT,
            explanation = "Exact match"
        )

        hybridClassifier.initialize()
        hybridClassifier.invalidateCategorySnapshot()

        val result = hybridClassifier.classify(
            merchantName = "Starbucks",
            amount = 10.0
        )

        assertEquals("Dining", result.categoryName)
    }

    @Test
    fun `merchant dictionary matching takes priority`() = runBlocking {
        // Mock CategorizationEngine to return Food for Starbucks
        coEvery { categorizationEngine.categorize("Starbucks") } returns CategorizationResult(
            categoryId = foodCategory.id,
            categoryName = foodCategory.name,
            confidence = 0.98,
            matchType = CategorizationMatchType.EXACT,
            explanation = "Exact match"
        )

        val result = hybridClassifier.classify(
            merchantName = "Starbucks",
            amount = 15.0
        )
        
        assertEquals(foodCategory.id, result.categoryId)
        assertEquals(MatchType.RULE_MATCH, result.matchType)
    }

    @Test
    fun `ml-based matching used when dictionary fails`() = runBlocking {
        // Dictionary returns UNKNOWN result (no category), ML should kick in
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(groceriesCategory.id, "Groceries", 0.9f)
        )

        val result = hybridClassifier.classify(
            merchantName = "StrangeMerchant",
            amount = 50.0
        )

        assertEquals(groceriesCategory.id, result.categoryId)
        assertEquals(MatchType.ML_PREDICTION, result.matchType)
    }

    @Test
    fun `fallback used when ml returns empty results`() = runBlocking {
        // Dictionary returns UNKNOWN result (no category), ML returns empty (not enough data)
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns emptyList()
        
        val result = hybridClassifier.classify(
            merchantName = "UnknownMerchant",
            amount = 0.0
        )

        assertEquals(uncategorizedCategory.id, result.categoryId)
        assertEquals(MatchType.FALLBACK, result.matchType)
    }

    @Test
    fun `ml threshold boundary is inclusive`() = runBlocking {
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(foodCategory.id, "Food", HybridExpenseClassifier.ML_THRESHOLD)
        )

        val result = hybridClassifier.classify(
            merchantName = "BoundaryMerchant",
            amount = 12.0
        )

        assertEquals(foodCategory.id, result.categoryId)
        assertEquals(MatchType.ML_PREDICTION, result.matchType)
    }

    @Test
    fun `gracefully falls back when category list is empty`() = runBlocking {
        coEvery { categoryRepository.getAll() } returns emptyList()
        hybridClassifier = HybridExpenseClassifier(context, categoryRepository, categorizationEngine, nbClassifier, timeProvider)

        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns emptyList()

        val result = hybridClassifier.classify(
            merchantName = "",
            amount = 0.0
        )

        assertEquals(-1L, result.categoryId)
        assertEquals(MatchType.FALLBACK, result.matchType)
    }

    @Test
    fun `dictionary confidence is clamped to valid range`() = runBlocking {
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = foodCategory.id,
            categoryName = foodCategory.name,
            confidence = 1.7,
            matchType = CategorizationMatchType.EXACT,
            explanation = "Overconfident"
        )

        val result = hybridClassifier.classify(
            merchantName = "Starbucks",
            amount = 10.0
        )

        assertEquals(MatchType.RULE_MATCH, result.matchType)
        assertEquals(1.0f, result.confidence, 0.0f)
    }

    @Test
    fun `ml scores are clamped to valid range`() = runBlocking {
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(foodCategory.id, "Food", 1.2f),
            CategoryScore(groceriesCategory.id, "Groceries", -0.1f)
        )

        val result = hybridClassifier.classify(
            merchantName = "NoisyScoreMerchant",
            amount = 10.0
        )

        assertEquals(MatchType.ML_PREDICTION, result.matchType)
        assertEquals(1.0f, result.confidence, 0.0f)
        assertTrue(result.alternatives.all { it.score in 0.0f..1.0f })
    }

    @Test
    fun `blank merchant and empty text immediately falls back`() = runBlocking {
        val result = hybridClassifier.classify(
            merchantName = "   ",
            amount = 0.0,
            notificationTitle = null,
            notificationText = null
        )

        assertEquals(MatchType.FALLBACK, result.matchType)
        coVerify(exactly = 0) { categorizationEngine.categorize(any()) }
        coVerify(exactly = 0) { nbClassifier.classify(any()) }
    }

    @Test
    fun `cold-start persisted model used on dictionary miss`() = runBlocking {
        // Simulate cold-start: dictionary miss, but the NB classifier has a
        // persisted model loaded from disk and returns valid predictions.
        // No isReady() gate should block this path.
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(foodCategory.id, "Food", 0.85f),
            CategoryScore(groceriesCategory.id, "Groceries", 0.10f)
        )

        val result = hybridClassifier.classify(
            merchantName = "ColdStartMerchant",
            amount = 25.0
        )

        assertEquals(foodCategory.id, result.categoryId)
        assertEquals(MatchType.ML_PREDICTION, result.matchType)
        assertTrue(result.confidence >= HybridExpenseClassifier.ML_THRESHOLD)
        // Verify ML classify was called — no readiness gate prevented it
        coVerify(exactly = 1) { nbClassifier.classify(any()) }
    }

    @Test
    fun `ml below threshold falls back gracefully`() = runBlocking {
        // ML returns results but none meet the threshold — should fallback
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(foodCategory.id, "Food", 0.1f)
        )

        val result = hybridClassifier.classify(
            merchantName = "LowConfidenceMerchant",
            amount = 10.0
        )

        assertEquals(uncategorizedCategory.id, result.categoryId)
        assertEquals(MatchType.FALLBACK, result.matchType)
    }

    @Test
    fun `ml exception falls back gracefully`() = runBlocking {
        // ML throws an exception — should fall back, not crash
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.classify(any()) } throws RuntimeException("ML model corrupted")

        val result = hybridClassifier.classify(
            merchantName = "ErrorMerchant",
            amount = 10.0
        )

        assertEquals(uncategorizedCategory.id, result.categoryId)
        assertEquals(MatchType.FALLBACK, result.matchType)
    }
}
