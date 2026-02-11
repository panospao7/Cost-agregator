package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedMappingsMap: Map<String, MerchantCategory>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes
    
    private val cleanupRegex1 by lazy { Regex("[^A-ZΑ-Ω0-9 &]") }
    private val cleanupRegex2 by lazy { Regex("\\s+") }

    suspend fun categorize(merchant: String): Long? {
        val normalized = normalize(merchant)

        // 1. Exact match
        val exactMatch = merchantCategoryDao.getCategoryForMerchant(normalized)
        if (exactMatch != null) return exactMatch.categoryId

        // 2. Substring match
        val (sortedMappings, mappingsMap) = getCache()
        
        val paddedNormalized = " $normalized "
        
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 5) {
                if (paddedNormalized.contains(mapping.merchantPattern)) {
                    return mapping.categoryId
                }
            }
        }

        // 3. Word-level match
        val words = normalized.split(" ").filter { it.length >= 4 }
        if (words.isNotEmpty()) {
            for (word in words) {
                val match = mappingsMap[word]
                if (match != null) return match.categoryId
            }
        }

        return null
    }

    fun normalize(merchant: String): String {
        return merchant.uppercase()
            .replace(cleanupRegex1, "")
            .trim()
            .replace(cleanupRegex2, " ")
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
