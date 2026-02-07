package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()

    suspend fun ensureDefaultCategories() {
        if (categoryDao.getCount() == 0) {
            // Seed Categories
            val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
            categoryDao.insertAll(defaults)
            
            // Seed Merchant Dictionary
            // We need to resolve Category IDs first to map names to IDs
            val categories = categoryDao.getAllFlow().first() // Use flow first emission or simple get
            // Actually, let's use a non-flow direct access if possible or collect once
            // Adding a simple getAll helper to DAO would be cleaner, but for now:
            
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
                merchantEntities.forEach { merchantCategoryDao.insert(it) }
            }
        }
    }

    suspend fun addCategory(name: String, icon: String, color: String) {
        val category = Category(name = name, icon = icon, color = color)
        categoryDao.insert(category)
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) {
        val normalized = categorizationEngine.normalize(merchantName)
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
    }
}
