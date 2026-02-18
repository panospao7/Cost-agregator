package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

class HybridExpenseClassifierTest {
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val nbClassifier = mockk<ExpenseCategoryClassifier>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var hybridClassifier: HybridExpenseClassifier

    private val foodCategory = Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")
    private val groceriesCategory = Category(id = 2L, name = "Groceries", icon = "shop", color = "#CCCCCC")
    private val miscCategory = Category(id = 3L, name = "Miscellaneous", icon = "misc", color = "#888888")
    private val uncategorizedCategory = Category(id = 4L, name = "Uncategorized", icon = "misc", color = "#888888")

    @Before
    fun setup() {
        coEvery { categoryDao.getAll() } returns listOf(foodCategory, groceriesCategory, miscCategory, uncategorizedCategory)
        hybridClassifier = HybridExpenseClassifier(context, categoryDao, nbClassifier)
    }

    @Test
    fun `rule-based matching takes priority`() = runBlocking {
        val result = hybridClassifier.classify(
            merchantName = "Starbucks",
            amount = 15.0
        )
        
        assertEquals(foodCategory.id, result.categoryId)
        assertEquals(MatchType.RULE_MATCH, result.matchType)
    }

    @Test
    fun `ml-based matching used when rules fail`() = runBlocking {
        // No rule for "StrangeMerchant"
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
        coEvery { nbClassifier.isReady() } returns false
        
        val result = hybridClassifier.classify(
            merchantName = "UnknownMerchant",
            amount = 0.0
        )

        assertEquals(uncategorizedCategory.id, result.categoryId)
        assertEquals(MatchType.FALLBACK, result.matchType)
    }
}
