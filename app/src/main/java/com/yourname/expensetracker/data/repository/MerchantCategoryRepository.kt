package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

sealed class MerchantCategoryInsertResult {
    data class Inserted(val rowId: Long) : MerchantCategoryInsertResult()
    data object Conflict : MerchantCategoryInsertResult()
}

@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: MerchantCategoryDao,
    private val categorizationEngineProvider: javax.inject.Provider<CategorizationEngine>
) {

    suspend fun getAll(): List<MerchantCategory> = dao.getAll()

    suspend fun insert(mapping: MerchantCategory): MerchantCategoryInsertResult {
        writeBarrier.checkWritesAllowed("MerchantCategoryRepository.insert")
        val rowId = dao.insert(mapping)
        return if (rowId > 0L) {
            categorizationEngineProvider.get().invalidateAllCaches()
            MerchantCategoryInsertResult.Inserted(rowId)
        } else {
            timber.log.Timber.w("MerchantCategoryRepository: insert conflict ignored for pattern='%s'", mapping.merchantPattern)
            MerchantCategoryInsertResult.Conflict
        }
    }

    suspend fun deleteAll() {
        writeBarrier.checkWritesAllowed("MerchantCategoryRepository.deleteAll")
        dao.deleteAll()
        // E3-005: Invalidate categorization caches after merchant-category mapping change
        categorizationEngineProvider.get().invalidateAllCaches()
    }

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
