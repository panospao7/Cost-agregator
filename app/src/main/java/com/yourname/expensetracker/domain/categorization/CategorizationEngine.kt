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
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes
    
    private val cleanupRegex1 by lazy { Regex("[^A-ZΑ-Ω0-9 &]") }
    private val cleanupRegex2 by lazy { Regex("\\s+") }

    suspend fun categorize(merchant: String): Long? {
        val normalized = normalize(merchant)

        // 1. Exact match
        val exactMatch = merchantCategoryDao.getCategoryForMerchant(normalized)
        if (exactMatch != null) return exactMatch.categoryId

        // 2. Substring match — check if any known merchant pattern is contained in this merchant
        val allMappings = getMappings()
        
        // Sort by pattern length descending to match longest first
        val sortedMappings = allMappings.sortedByDescending { it.merchantPattern.length }
        
        val paddedNormalized = " $normalized "
        
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 3) {
                // Check if the pattern exists as a whole word(s) in the merchant name
                // e.g. "UBER" matches "UBER EATS" but "ONE" does not match "PHONE"
                val paddedPattern = " ${mapping.merchantPattern} "
                if (paddedNormalized.contains(paddedPattern)) {
                    return mapping.categoryId
                }
            }
        }

        // 3. Word-level match — split merchant into words and check each
        val words = normalized.split(" ").filter { it.length >= 4 }
        if (words.isNotEmpty()) {
            val mappingsMap = allMappings.associateBy { it.merchantPattern }
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

    private suspend fun getMappings(): List<MerchantCategory> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                cachedMappings = merchantCategoryDao.getAll()
                lastCacheTime = now
            }
            return cachedMappings!!
        }
    }

    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            lastCacheTime = 0
        }
    }
}
