package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.data.repository.CategoryCorrectionScope
import com.yourname.expensetracker.data.repository.CategoryCorrectionResult
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CategoryRepositoryTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val merchantCategoryDao = mockk<MerchantCategoryDao>(relaxed = true)
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
            budgetDao = mockk(relaxed = true),
            categorizationEngine = categorizationEngine,
            hybridExpenseClassifier = lazyClassifier
        )
    }

    @Test
    fun `addCategory triggers categorizationEngine invalidateCache`() = runTest {
        // Setup: category does not exist
        coEvery { categoryDao.getOrInsertByNameNoCase(any()) } returns com.yourname.expensetracker.data.database.entity.Category(
            id = 5L,
            name = "Dining",
            icon = "icon",
            color = "#FF0000"
        )

        repository.addCategory("Dining", "icon", "#FF0000")

        // Verify categorizationEngine.invalidateCache() was called
        coVerify { categorizationEngine.invalidateCache() }
    }

    @Test
    fun `deleteCategory triggers categorizationEngine invalidateCache`() = runTest {
        val category = com.yourname.expensetracker.data.database.entity.Category(id = 1L, name = "test", icon = "icon", color = "#FF0000", isDefault = false)
        coEvery { categoryDao.getById(1L) } returns category

        repository.deleteCategory(1L)

        coVerify { categorizationEngine.invalidateCache() }
    }

    @Test
    fun `mergeCategories triggers categorizationEngine invalidateCache`() = runTest {
        coEvery { categoryDao.mergeCategories(any(), any()) } returns 5

        repository.mergeCategories(1L, 2L)

        coVerify { categorizationEngine.invalidateCache() }
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
    fun `addCategory preserves display name case`() = runTest {
        coEvery { categoryDao.getOrInsertByNameNoCase(any()) } returns com.yourname.expensetracker.data.database.entity.Category(
            id = 7L,
            name = "Dining Out",
            icon = "food",
            color = "#FF0000"
        )

        val result = repository.addCategory("Dining Out", "food", "#FF0000")

        // E3-NOW-008: Display name should preserve original case
        assertEquals("Dining Out", result.name)
    }

    @Test
    fun `addCategory detects duplicate case insensitively`() = runTest {
        val existing = com.yourname.expensetracker.data.database.entity.Category(
            id = 3L, name = "Dining Out", icon = "food", color = "#FF0000", isDefault = false
        )
        coEvery { categoryDao.getOrInsertByNameNoCase(any()) } returns existing

        val result = repository.addCategory("DINING OUT", "food", "#FF0000")

        // Should return existing category, not create a new one
        assertEquals(3L, result.id)
        assertEquals("Dining Out", result.name)
    }

    @Test
    fun `updateExpenseCategoryBulk future only learns mapping`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Starbucks", 5L) } returns Unit

        val result = repository.updateExpenseCategoryBulk(
            merchant = "Starbucks",
            newCategoryId = 5L,
            scope = CategoryCorrectionScope.FUTURE_ONLY
        )

        assertTrue(result is CategoryCorrectionResult.Learned)
        coVerify { categorizationEngine.learnMerchantCategory("Starbucks", 5L) }
    }

    @Test
    fun `updateExpenseCategoryBulk backfill all returns not yet implemented`() = runTest {
        val result = repository.updateExpenseCategoryBulk(
            merchant = "Starbucks",
            newCategoryId = 5L,
            scope = CategoryCorrectionScope.BACKFILL_ALL
        )

        assertTrue(result is CategoryCorrectionResult.BackfillNotYetImplemented)
        coVerify(exactly = 0) { categorizationEngine.learnMerchantCategory(any(), any()) }
    }

    @Test
    fun `updateExpenseCategoryBulk default scope is future only`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Starbucks", 5L) } returns Unit

        val result = repository.updateExpenseCategoryBulk("Starbucks", 5L)

        assertTrue(result is CategoryCorrectionResult.Learned)
    }
}
