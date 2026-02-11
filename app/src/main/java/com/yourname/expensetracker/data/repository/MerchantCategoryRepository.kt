package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val dao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    /**
     * Learns a merchant -> category mapping.
     * Normalizes the merchant name before saving.
     */
    suspend fun learnPattern(merchantName: String, categoryId: Long) {
        val pattern = categorizationEngine.normalize(merchantName)
        if (pattern.isNotEmpty()) {
            dao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = categoryId,
                    confidence = 1.0f
                )
            )
        }
    }
    
    suspend fun getCategoryForMerchant(merchantName: String): MerchantCategory? {
        val pattern = categorizationEngine.normalize(merchantName)
        return dao.getCategoryForMerchant(pattern)
    }
}
