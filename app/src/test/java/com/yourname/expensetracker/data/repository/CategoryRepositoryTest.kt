package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import dagger.Lazy
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CategoryRepositoryTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val merchantCategoryDao = mockk<MerchantCategoryDao>(relaxed = true)
    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)

    private lateinit var repository: CategoryRepository

    @Before
    fun setup() {
        val lazyClassifier = object : Lazy<HybridExpenseClassifier> {
            override fun get(): HybridExpenseClassifier = hybridExpenseClassifier
        }
        repository = CategoryRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true),
            categoryDao = categoryDao,
            merchantCategoryDao = merchantCategoryDao,
            budgetDao = budgetDao,
            categorizationEngine = categorizationEngine,
            hybridExpenseClassifier = lazyClassifier
        )
    }

    @Test
    fun `successful category mutations return outcome and invalidate engine cache and classifier snapshot`() = runTest {
        // Row: addCategory
        coEvery { categoryDao.getOrInsertByNameNoCase(any()) } returns Category(
            id = 5L, name = "Dining", icon = "icon", color = "#FF0000"
        )
        val added = repository.addCategory("Dining", "icon", "#FF0000")
        assertEquals("Row addCategory: returned category", 5L, added.id)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }

        // Row: deleteCategory
        clearMocks(categorizationEngine, hybridExpenseClassifier, answers = false)
        coEvery { categoryDao.getById(1L) } returns
            Category(id = 1L, name = "test", icon = "icon", color = "#FF0000", isDefault = false)
        coEvery { budgetDao.countBudgetsForCategory(1L) } returns 0
        val deleted = repository.deleteCategory(1L)
        assertEquals("Row deleteCategory: outcome", DeleteCategoryResult.Deleted, deleted)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }

        // Row: mergeCategories
        clearMocks(categorizationEngine, hybridExpenseClassifier, answers = false)
        coEvery { categoryDao.mergeCategories(1L, 2L) } returns 5
        val moved = repository.mergeCategories(1L, 2L)
        assertEquals("Row mergeCategories: expenses moved", 5, moved)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
    }

    @Test
    fun `rejected deleteCategory does not delete or invalidate caches`() = runTest {
        val defaultCategory = Category(id = 2L, name = "Default", icon = "icon", color = "#FF0000", isDefault = true)
        val withBudgets = Category(id = 3L, name = "Budgeted", icon = "icon", color = "#FF0000", isDefault = false)
        coEvery { categoryDao.getById(1L) } returns null
        coEvery { categoryDao.getById(2L) } returns defaultCategory
        coEvery { categoryDao.getById(3L) } returns withBudgets
        coEvery { budgetDao.countBudgetsForCategory(3L) } returns 2

        assertEquals("Row missing category", DeleteCategoryResult.NotFound, repository.deleteCategory(1L))
        assertEquals("Row default category", DeleteCategoryResult.CannotDeleteDefault, repository.deleteCategory(2L))
        assertEquals("Row category with budgets", DeleteCategoryResult.HasBudgets(2), repository.deleteCategory(3L))

        coVerify(exactly = 0) { categoryDao.delete(any()) }
        coVerify(exactly = 0) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 0) { hybridExpenseClassifier.invalidateCategorySnapshot() }
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `learnMerchantCategory delegates to engine path for centralized invalidation`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) } returns Unit

        repository.learnMerchantCategory("Coffee Lab", 7L)

        coVerify(exactly = 1) { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) }
        coVerify(exactly = 0) { merchantCategoryDao.insert(any()) }
    }

    @Test
    fun `addCategory trims but preserves display case and returns existing category on duplicate`() = runTest {
        val existing = Category(
            id = 3L, name = "Dining Out", icon = "food", color = "#FF0000", isDefault = false
        )
        val passed = slot<Category>()
        coEvery { categoryDao.getOrInsertByNameNoCase(capture(passed)) } returns existing

        val result = repository.addCategory("  DINING OUT ", "food", "#FF0000")

        // E3-NOW-008: the candidate keeps original case (trimmed); duplicate detection is the DAO's job
        assertEquals("DINING OUT", passed.captured.name)
        // Should return existing category, not create a new one
        assertEquals(3L, result.id)
        assertEquals("Dining Out", result.name)
    }

    @Test
    fun `updateExpenseCategoryBulk future only and default scope learn mapping`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Starbucks", 5L) } returns Unit

        val explicit = repository.updateExpenseCategoryBulk(
            merchant = "Starbucks",
            newCategoryId = 5L,
            scope = CategoryCorrectionScope.FUTURE_ONLY
        )
        val defaulted = repository.updateExpenseCategoryBulk("Starbucks", 5L)

        assertEquals("Row explicit FUTURE_ONLY", CategoryCorrectionResult.Learned("Starbucks", 5L), explicit)
        assertEquals("Row default scope", CategoryCorrectionResult.Learned("Starbucks", 5L), defaulted)
        coVerify(exactly = 2) { categorizationEngine.learnMerchantCategory("Starbucks", 5L) }
    }

    @Test
    fun `updateExpenseCategoryBulk backfill scopes return not yet implemented and learn nothing`() = runTest {
        listOf(CategoryCorrectionScope.BACKFILL_ALL, CategoryCorrectionScope.BACKFILL_SELECTED).forEach { scope ->
            val result = repository.updateExpenseCategoryBulk(
                merchant = "Starbucks",
                newCategoryId = 5L,
                scope = scope
            )

            assertEquals(
                "Row $scope",
                CategoryCorrectionResult.BackfillNotYetImplemented("Starbucks", 5L),
                result
            )
        }
        coVerify(exactly = 0) { categorizationEngine.learnMerchantCategory(any(), any()) }
    }
}
