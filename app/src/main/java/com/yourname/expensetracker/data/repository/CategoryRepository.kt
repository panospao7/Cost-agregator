package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()

    suspend fun getAll(): List<Category> = categoryDao.getAll()

    /**
     * Seed default categories and merchant dictionary if the categories table is empty.
     *
     * B4 fixes:
     * - Uses [CategoryDao.seedDefaultsIfEmpty] which is @Transaction-annotated,
     *   so the count-check + insertAll is atomic and race-free.
     * - Uses one-shot [CategoryDao.getAll] instead of flow-based `.first()` to
     *   avoid fragile flow semantics inside a seeding path.
     * - Ensures "Uncategorized" category exists for existing users via one-shot read.
     */
    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        try {
            val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
            val seeded = categoryDao.seedDefaultsIfEmpty(defaults)

            if (seeded) {
                // Seed Merchant Dictionary
                // We need to resolve Category IDs first to map names to IDs
                val categories = categoryDao.getAll()

                // Map: "Groceries" -> 1, "Transport" -> 2
                val categoryIdMap = categories.associate { it.name to it.id }

                val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
                val merchantEntities = merchantMap.mapNotNull { (merchant, categoryName) ->
                   val catId = categoryIdMap[categoryName]
                   if (catId != null) {
                       MerchantCategory(merchantPattern = merchant, categoryId = catId)
                   } else {
                       null
                   }
                }
                if (merchantEntities.isNotEmpty()) {
                    // We need a bulk insert for speed
                    merchantCategoryDao.insertAll(merchantEntities)
                }
            } else {
                // BUG-012 Fix: Ensure "Uncategorized" exists even for existing users
                val categories = categoryDao.getAll()
                if (categories.none { it.name.equals("Uncategorized", ignoreCase = true) }) {
                    val uncategorized = defaults.find { it.name == "Uncategorized" }
                    if (uncategorized != null) {
                        categoryDao.insert(uncategorized)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to seed default categories")
        }
    }

    suspend fun addCategory(name: String, icon: String, color: String) = withContext(Dispatchers.IO) {
        val category = Category(name = name, icon = icon, color = color)
        categoryDao.insert(category)
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) = withContext(Dispatchers.IO) {
        val normalized = categorizationEngine.normalize(merchantName)
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
    }
    
    suspend fun getCategoryByName(name: String): Category? = withContext(Dispatchers.IO) {
        categoryDao.getAll().find { it.name.equals(name, ignoreCase = true) }
    }
}
