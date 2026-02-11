package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao,
    private val merchantNormalizer: com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedMappingsMap: Map<String, MerchantCategory>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes
    
    // Regex moved to MerchantNormalizer

    suspend fun categorize(merchant: String): Long? {
        val normalized = normalize(merchant)

        // Ensure cache is loaded
        val (sortedMappings, mappingsMap) = getCache()

        // 1. Exact match (from cache)
        mappingsMap[normalized]?.let { return it.categoryId }

        // 2. Substring match
        val paddedNormalized = " $normalized "
        
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 5) {
                if (paddedNormalized.contains(mapping.merchantPattern)) {
                    return mapping.categoryId
                }
            }
        }

        // 3. Word-level match
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .filter { it !in listOf("the", "and", "for", "inc", "ltd", "com") }
            
        if (words.isNotEmpty()) {
            for (word in words) {
                val match = mappingsMap[word]
                if (match != null) return match.categoryId
            }
        }

        return null
    }

    fun normalize(merchant: String): String {
        return merchantNormalizer.normalize(merchant)
    }

    private suspend fun getCache(): Pair<List<MerchantCategory>, Map<String, MerchantCategory>> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || cachedMappingsMap == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                val all = merchantCategoryDao.getAll()
                cachedMappings = all.map { it.copy(merchantPattern = " ${it.merchantPattern} ") }
                    .sortedByDescending { it.merchantPattern.length }
                
                cachedMappingsMap = all.associateBy { it.merchantPattern }
                lastCacheTime = now
            }
            return Pair(cachedMappings!!, cachedMappingsMap!!)
        }
    }

    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            cachedMappingsMap = null
            lastCacheTime = 0
        }
    }
}
