package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val dao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine,
    private val canonicalizer: MerchantCanonicalizer = MerchantCanonicalizer(),
    private val greeklishNormalizer: GreeklishNormalizer = GreeklishNormalizer()
) {

    suspend fun getAll(): List<MerchantCategory> = dao.getAll()

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Learns a merchant -> category mapping.
     * Normalizes the merchant name before saving.
     * Checks for existing entry to avoid duplicates.
     */
    suspend fun learnPattern(merchantName: String, categoryId: Long) {
        val pattern = categorizationEngine.normalize(merchantName)
        if (pattern.isNotEmpty()) {
            val existing = dao.getCategoryForMerchant(pattern)
            if (existing == null) {
                val canonicalResult = canonicalizer.canonicalize(pattern)
                val normalizedCanonical = greeklishNormalizer.normalize(canonicalResult.canonicalName)
                
                dao.insert(
                    MerchantCategory(
                        merchantPattern = pattern,
                        categoryId = categoryId,
                        confidence = 1.0f,
                        normalizedCanonicalName = normalizedCanonical
                    )
                )
            }
        }
    }
    
    suspend fun getCategoryForMerchant(merchantName: String): MerchantCategory? {
        val pattern = categorizationEngine.normalize(merchantName)
        return dao.getCategoryForMerchant(pattern)
    }
    
    suspend fun getCategoryByNormalizedCanonical(normalizedCanonicalName: String): MerchantCategory? {
        return dao.getCategoryByNormalizedCanonical(normalizedCanonicalName)
    }
}
