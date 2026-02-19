package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao,
    private val merchantNormalizer: MerchantNormalizer
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedPatternsSet: Set<String>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes

    companion object {
        private val STOP_WORDS = setOf("the", "and", "for", "inc", "ltd", "com")
    }

    suspend fun categorize(merchant: String): Long? {
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = false)
        val normalized = lookupResult.canonical.normalizedName.lowercase()

        val sortedMappings = getCache()
        val patternsSet = getPatternsSet()

        // 1. Exact match - check against normalized patterns set
        if (normalized in patternsSet) {
            return sortedMappings.find { it.merchantPattern.equals(normalized, ignoreCase = true) }?.categoryId
        }

        // 2. Substring match - padded search
        val paddedNormalized = " $normalized "
        
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 5) {
                val paddedPattern = " ${mapping.merchantPattern} "
                if (paddedNormalized.contains(paddedPattern)) {
                    return mapping.categoryId
                }
            }
        }

        // 3. Word-level match - check each word
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            
        for (word in words) {
            if (word in patternsSet) {
                return sortedMappings.find { it.merchantPattern.equals(word, ignoreCase = true) }?.categoryId
            }
        }

        return null
    }

    suspend fun normalize(merchant: String): String {
        return merchantNormalizer.normalize(merchant, autoCreate = false).canonical.normalizedName
    }

    private suspend fun getCache(): List<MerchantCategory> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                val all = merchantCategoryDao.getAll()
                cachedMappings = all.sortedByDescending { it.merchantPattern.length }
                lastCacheTime = now
            }
        }
        return cachedMappings!!
    }

    private suspend fun getPatternsSet(): Set<String> {
        cacheMutex.withLock {
            if (cachedPatternsSet == null || lastCacheTime == 0L) {
                val all = merchantCategoryDao.getAll()
                cachedPatternsSet = all.map { it.merchantPattern.lowercase() }.toSet()
            }
        }
        return cachedPatternsSet!!
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) {
        val normalized = merchantNormalizer.normalize(merchantName, autoCreate = false).canonical.normalizedName
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
        invalidateCache()
    }

    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            cachedPatternsSet = null
            lastCacheTime = 0
        }
    }
}
