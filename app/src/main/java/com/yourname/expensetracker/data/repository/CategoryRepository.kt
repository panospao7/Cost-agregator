package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()

    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        try {
            if (categoryDao.getCount() == 0) {
                // Seed Categories
                val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
                categoryDao.insertAll(defaults)
                
                // Seed Merchant Dictionary
                // We need to resolve Category IDs first to map names to IDs
                val categories = categoryDao.getAllFlow().first() // Use flow first emission or simple get
                
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
                val categories = categoryDao.getAllFlow().first()
                if (categories.none { it.name.equals("Uncategorized", ignoreCase = true) }) {
                    val uncategorized = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
                        .find { it.name == "Uncategorized" }
                    if (uncategorized != null) {
                        categoryDao.insert(uncategorized)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CategoryRepository", "Failed to seed default categories", e)
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
}
