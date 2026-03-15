package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.CategorizationResult
import com.yourname.expensetracker.domain.categorization.MatchType as CategorizationMatchType
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
    private lateinit var hybridClassifier: HybridExpenseClassifier

    private val foodCategory = Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")
    private val groceriesCategory = Category(id = 2L, name = "Groceries", icon = "shop", color = "#CCCCCC")
    private val miscCategory = Category(id = 3L, name = "Miscellaneous", icon = "misc", color = "#888888")
    private val uncategorizedCategory = Category(id = 4L, name = "Uncategorized", icon = "misc", color = "#888888")

    @Before
    fun setup() {
        coEvery { categoryRepository.getAll() } returns listOf(foodCategory, groceriesCategory, miscCategory, uncategorizedCategory)
        hybridClassifier = HybridExpenseClassifier(context, categoryRepository, categorizationEngine, nbClassifier)
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
        coEvery { nbClassifier.isReady() } returns true
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
    fun `fallback used when everything fails`() = runBlocking {
        // Dictionary returns UNKNOWN result (no category), ML not ready
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.isReady() } returns false
        
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
        coEvery { nbClassifier.isReady() } returns true
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
        hybridClassifier = HybridExpenseClassifier(context, categoryRepository, categorizationEngine, nbClassifier)

        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = CategorizationMatchType.UNKNOWN,
            explanation = "No match found"
        )
        coEvery { nbClassifier.isReady() } returns false

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
        coEvery { nbClassifier.isReady() } returns true
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
        coEvery { nbClassifier.isReady() } returns true

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
}
