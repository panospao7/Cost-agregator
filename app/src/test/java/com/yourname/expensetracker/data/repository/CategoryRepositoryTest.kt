package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `learnMerchantCategory delegates to engine path for centralized invalidation`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) } returns Unit

        repository.learnMerchantCategory("Coffee Lab", 7L)

        coVerify(exactly = 1) { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) }
        coVerify(exactly = 0) { merchantCategoryDao.insert(any()) }
    }
}