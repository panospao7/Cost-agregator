package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CategoryRepository @Inject constructor(
    private val database: AppDatabase,
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine,
    private val hybridExpenseClassifier: dagger.Lazy<HybridExpenseClassifier>
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
                        categorizationEngine.createMerchantCategoryMapping(merchant, catId)
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

            merchantCategoryDao.getMappingsMissingCanonicalName().forEach { mapping ->
                merchantCategoryDao.updateNormalizedCanonicalName(
                    merchantPattern = mapping.merchantPattern,
                    normalizedCanonicalName = categorizationEngine.normalizedCanonicalNameForMerchant(mapping.merchantPattern)
                )
            }
            hybridExpenseClassifier.get().invalidateCategorySnapshot()
        } catch (e: Exception) {
            Timber.e(e, "Failed to seed default categories")
        }
    }

    /**
     * Add a new category with name normalization and case-insensitive duplicate detection.
     *
     * The name is trimmed and lowercased before insertion (consistent with
     * [Category.normalizedName]). If a category with the same normalized name
     * already exists, the existing category is returned instead of inserting
     * a duplicate.
     *
     * The check-then-insert is wrapped in a transaction to prevent races.
     *
     * @return the newly created or existing [Category].
     */
    suspend fun addCategory(name: String, icon: String, color: String): Category = withContext(Dispatchers.IO) {
        // Normalize: trim whitespace and lowercase for consistency with Category.normalizedName
        val normalizedName = name.trim().lowercase()

        return@withContext database.withTransaction {
            // Check for existing case-insensitive match
            val existing = categoryDao.getByName(normalizedName)
            if (existing != null) {
                Timber.d("addCategory: returning existing category '%s' (id=%d)", existing.name, existing.id)
                return@withTransaction existing
            }

            val category = Category(name = normalizedName, icon = icon, color = color)
            val id = categoryDao.insert(category)
            hybridExpenseClassifier.get().invalidateCategorySnapshot()
            category.copy(id = id)
        }
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) = withContext(Dispatchers.IO) {
        categorizationEngine.learnMerchantCategory(merchantName, categoryId)
    }
    
    suspend fun getCategoryByName(name: String): Category? = withContext(Dispatchers.IO) {
        categoryDao.getByName(name)
    }
}
