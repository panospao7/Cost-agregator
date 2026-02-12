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

    private val foodCategory = Category(id = 1, name = "Food", icon = "food")
    private val groceriesCategory = Category(id = 2, name = "Groceries", icon = "shop")

    @Before
    fun setup() {
        coEvery { categoryDao.getAll() } returns listOf(foodCategory, groceriesCategory)
        hybridClassifier = HybridExpenseClassifier(context, categoryDao, nbClassifier)
    }

    @Test
    fun `rule-based matching takes priority`() = runBlocking {
        val result = hybridClassifier.classify(
            merchantName = "McDonald's",
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

        assertEquals(foodCategory.id, result.categoryId) // First in list
        assertEquals(MatchType.FALLBACK, result.matchType)
    }
}
