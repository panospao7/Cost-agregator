package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val dao: MerchantCategoryDao,
    private val categorizationEngineProvider: javax.inject.Provider<CategorizationEngine>
) {

    suspend fun getAll(): List<MerchantCategory> = dao.getAll()

    suspend fun insert(mapping: MerchantCategory) = dao.insert(mapping)

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Learns a merchant -> category mapping.
     * Uses Provider to break circular dependency with CategorizationEngine.
     */
    suspend fun learnPattern(merchantName: String, categoryId: Long) {
        categorizationEngineProvider.get().learnMerchantCategory(merchantName, categoryId)
    }
    
    suspend fun getCategoryForMerchant(merchantName: String): MerchantCategory? {
        val pattern = categorizationEngineProvider.get().normalize(merchantName)
        return dao.getCategoryForMerchant(pattern)
    }
    
    suspend fun getCategoryByNormalizedCanonical(normalizedCanonicalName: String): MerchantCategory? {
        return dao.getCategoryByNormalizedCanonical(normalizedCanonicalName)
    }
}
