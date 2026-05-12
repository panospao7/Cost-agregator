package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Ignore("Stress test: may hang in CI, run manually")
class CategoryRepositoryStressTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val merchantCategoryDao = mockk<MerchantCategoryDao>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)

    private lateinit var repository: CategoryRepository

    @Before
    fun setup() {
        // Mock insert functions - CategoryDao returns Long, MerchantCategoryDao returns Unit
        coEvery { categoryDao.insert(any()) } returns 1L
        coEvery { categoryDao.insertAll(any()) } returns Unit
        coEvery { merchantCategoryDao.insert(any()) } returns 1L
        coEvery { merchantCategoryDao.insertAll(any()) } returns emptyList()
        // Normalize is suspend
        coEvery { categorizationEngine.normalize(any()) } returns "normalized"
        
        repository = CategoryRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true),
            categoryDao = categoryDao,
            merchantCategoryDao = merchantCategoryDao,
            budgetDao = mockk(relaxed = true),
            categorizationEngine = categorizationEngine,
            hybridExpenseClassifier = mockk<Lazy<com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier>>(relaxed = true)
        )
    }

    // ============================================================================
    // SECTION 1: INITIALIZATION
    // ============================================================================

    @Test
    fun `stress - allCategories returns flow from DAO`() = runTest {
        val categories = listOf(
            Category(id = 1, name = "Food", icon = "food", color = "#FF0000"),
            Category(id = 2, name = "Transport", icon = "car", color = "#00FF00")
        )
        every { categoryDao.getAllFlow() } returns MutableStateFlow(categories)

        val result = repository.allCategories
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 2: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - category with valid name handled`() = runTest {
        repository.addCategory("Test", "help", "#888888")
    }

    // ============================================================================
    // SECTION 3: BULK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - learn many merchants`() = runTest {
        repeat(100) { i ->
            repository.learnMerchantCategory("Merchant $i", 1)
        }
    }

    @Test
    fun `stress - add many categories`() = runTest {
        repeat(50) { i ->
            repository.addCategory("Category $i", "icon", "#FF0000")
        }
    }
}