package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

enum class MatchType {
    EXACT,           // Direct dictionary match (98%)
    CANONICAL,       // After stripping suffixes (90-95%)
    GREEKLISH,       // Greek/Greeklish match (90%)
    KEYWORD,         // Semantic keyword match (60-80%)
    CONTEXT,         // Inferred from context (45-70%)
    ML_PREDICTION,   // ML model prediction (40-70%)
    UNKNOWN          // No match found (0%)
}

data class CategorizationResult(
    val categoryId: Long?,
    val categoryName: String?,
    val confidence: Double,
    val matchType: MatchType,
    val explanation: String = ""
)

data class LayerDebugResult(
    val layerName: String,
    val matchFound: Boolean,
    val categoryName: String? = null,
    val categoryId: Long? = null,
    val confidence: Double = 0.0,
    val matchType: MatchType? = null,
    val details: String = ""
)

data class CategorizationDebugTrace(
    val inputMerchant: String,
    val amount: Double,
    val timestamp: Long,
    val normalizedMerchant: String,
    val canonicalMerchant: String,
    val strippedParts: List<String>,
    val layerResults: List<LayerDebugResult>,
    val finalResult: CategorizationResult
)

@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val categoryRepositoryProvider: javax.inject.Provider<CategoryRepository>,
    private val canonicalizer: MerchantCanonicalizer,
    private val greeklishNormalizer: GreeklishNormalizer,
    private val semanticMatcher: SemanticKeywordMatcher,
    private val contextEngine: ContextualInferenceEngine,
    private val timeProvider: TimeProvider
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedPatternsSet: Set<String>? = null
    private var cachedCategoryMap: Map<Long, String>? = null
    private var cachedCategoryNameToId: Map<String, Long>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000

    companion object {
        private val STOP_WORDS = setOf("the", "and", "for", "inc", "ltd", "com")
        
        const val CONFIDENCE_EXACT = 0.98
        const val CONFIDENCE_CANONICAL = 0.93
        const val CONFIDENCE_GREEKLISH = 0.90
        const val CONFIDENCE_KEYWORD_MIN = 0.50
        const val CONFIDENCE_CONTEXT_MIN = 0.45
        const val CONFIDENCE_ML_MIN = 0.40
    }

    suspend fun categorize(merchant: String): CategorizationResult {
        return categorizeWithContext(merchant, 0.0, timeProvider.now())
    }
    
    suspend fun categorizeWithContext(
        merchant: String,
        amount: Double = 0.0,
        timestamp: Long = timeProvider.now()
    ): CategorizationResult {
        
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = false)
        val normalized = lookupResult.canonical.normalizedName.lowercase()
        
        val sortedMappings = getCache()
        val patternsSet = getPatternsSet()
        val categoryMap = getCategoryMap()
        
        // LAYER 1: Exact match
        if (normalized in patternsSet) {
            val match = sortedMappings.find { it.merchantPattern.equals(normalized, ignoreCase = true) }
            if (match != null) {
                return CategorizationResult(
                    categoryId = match.categoryId,
                    categoryName = categoryMap[match.categoryId],
                    confidence = CONFIDENCE_EXACT,
                    matchType = MatchType.EXACT,
                    explanation = "Exact match: $normalized"
                )
            }
        }
        
        // LAYER 2: Canonical + Fuzzy
        val canonicalResult = canonicalizer.canonicalize(normalized)
        if (canonicalResult.canonicalName != normalized) {
            if (canonicalResult.canonicalName in patternsSet) {
                val match = sortedMappings.find { 
                    it.merchantPattern.equals(canonicalResult.canonicalName, ignoreCase = true) 
                }
                if (match != null) {
                    val confidence = CONFIDENCE_CANONICAL - canonicalResult.confidencePenalty
                    return CategorizationResult(
                        categoryId = match.categoryId,
                        categoryName = categoryMap[match.categoryId],
                        confidence = confidence,
                        matchType = MatchType.CANONICAL,
                        explanation = "Canonical match: $normalized -> ${canonicalResult.canonicalName}"
                    )
                }
            }
        }
        
        // LAYER 2b: Greeklish variations
        val variations = greeklishNormalizer.getVariations(normalized)
        for (variant in variations) {
            if (variant != normalized && variant in patternsSet) {
                val match = sortedMappings.find { 
                    it.merchantPattern.equals(variant, ignoreCase = true) 
                }
                if (match != null) {
                    return CategorizationResult(
                        categoryId = match.categoryId,
                        categoryName = categoryMap[match.categoryId],
                        confidence = CONFIDENCE_GREEKLISH,
                        matchType = MatchType.GREEKLISH,
                        explanation = "Greeklish match: $normalized -> $variant"
                    )
                }
            }
        }
        
        // LAYER 2c: Fuzzy match against normalized canonical names
        val fuzzyMatch = findFuzzyMatch(normalized, sortedMappings)
        if (fuzzyMatch != null) {
            return CategorizationResult(
                categoryId = fuzzyMatch.categoryId,
                categoryName = categoryMap[fuzzyMatch.categoryId],
                confidence = CONFIDENCE_CANONICAL - 0.05,
                matchType = MatchType.CANONICAL,
                explanation = "Fuzzy match: $normalized -> ${fuzzyMatch.merchantPattern}"
            )
        }
        
        // LAYER 3: Semantic keyword matching
        val semanticMatch = semanticMatcher.findBestMatch(merchant, CONFIDENCE_KEYWORD_MIN)
        if (semanticMatch != null) {
            val categoryId = getCategoryIdByName(semanticMatch.categoryName)
            if (categoryId != null) {
                return CategorizationResult(
                    categoryId = categoryId,
                    categoryName = semanticMatch.categoryName,
                    confidence = semanticMatch.confidence,
                    matchType = MatchType.KEYWORD,
                    explanation = "Keyword match: '${semanticMatch.matchedKeyword}'"
                )
            }
        }
        
        // LAYER 4: Context inference (for surnames)
        if (contextEngine.isLikelySurname(normalized) && amount > 0) {
            val contextPrediction = contextEngine.inferFromContext(amount, timestamp)
            if (contextPrediction != null) {
                val categoryId = getCategoryIdByName(contextPrediction.categoryName)
                if (categoryId != null) {
                    return CategorizationResult(
                        categoryId = categoryId,
                        categoryName = contextPrediction.categoryName,
                        confidence = contextPrediction.confidence,
                        matchType = MatchType.CONTEXT,
                        explanation = "Context inference: ${contextPrediction.reason}"
                    )
                }
            }
        }
        
        // LAYER 5: ML Prediction fallback
        // Note: ML classification is handled by HybridExpenseClassifier separately
        // This layer could be integrated here if needed for unified pipeline
        
        // LAYER 6: Unknown
        return CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = MatchType.UNKNOWN,
            explanation = "No match found"
        )
    }
    
    suspend fun debugCategorize(
        merchant: String,
        amount: Double = 0.0,
        timestamp: Long = timeProvider.now()
    ): CategorizationDebugTrace {
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = false)
        val normalized = lookupResult.canonical.normalizedName.lowercase()
        
        val sortedMappings = getCache()
        val patternsSet = getPatternsSet()
        val categoryMap = getCategoryMap()
        
        val canonicalResult = canonicalizer.canonicalize(normalized)
        val layerResults = mutableListOf<LayerDebugResult>()
        var finalResult: CategorizationResult? = null

        // LAYER 1: Exact match
        var exactMatchFound = false
        if (normalized in patternsSet) {
            val match = sortedMappings.find { it.merchantPattern.equals(normalized, ignoreCase = true) }
            if (match != null) {
                exactMatchFound = true
                val result = CategorizationResult(
                    categoryId = match.categoryId,
                    categoryName = categoryMap[match.categoryId],
                    confidence = CONFIDENCE_EXACT,
                    matchType = MatchType.EXACT,
                    explanation = "Exact match: $normalized"
                )
                layerResults.add(LayerDebugResult("Layer 1: Exact", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                finalResult = result
            }
        }
        if (!exactMatchFound) {
            layerResults.add(LayerDebugResult("Layer 1: Exact", false, details = "No exact match in dictionary"))
        }

        // LAYER 2: Canonical + Fuzzy
        var canonicalMatchFound = false
        if (finalResult == null && canonicalResult.canonicalName != normalized) {
            if (canonicalResult.canonicalName in patternsSet) {
                val match = sortedMappings.find { 
                    it.merchantPattern.equals(canonicalResult.canonicalName, ignoreCase = true) 
                }
                if (match != null) {
                    canonicalMatchFound = true
                    val confidence = CONFIDENCE_CANONICAL - canonicalResult.confidencePenalty
                    val result = CategorizationResult(
                        categoryId = match.categoryId,
                        categoryName = categoryMap[match.categoryId],
                        confidence = confidence,
                        matchType = MatchType.CANONICAL,
                        explanation = "Canonical match: $normalized -> ${canonicalResult.canonicalName}"
                    )
                    layerResults.add(LayerDebugResult("Layer 2: Canonical", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                    finalResult = result
                }
            }
        }
        if (!canonicalMatchFound) {
             layerResults.add(LayerDebugResult("Layer 2: Canonical", false, details = "No canonical match"))
        }

        // LAYER 2b: Greeklish variations
        var greeklishMatchFound = false
        if (finalResult == null) {
            val variations = greeklishNormalizer.getVariations(normalized)
            for (variant in variations) {
                if (variant != normalized && variant in patternsSet) {
                    val match = sortedMappings.find { 
                        it.merchantPattern.equals(variant, ignoreCase = true) 
                    }
                    if (match != null) {
                        greeklishMatchFound = true
                        val result = CategorizationResult(
                            categoryId = match.categoryId,
                            categoryName = categoryMap[match.categoryId],
                            confidence = CONFIDENCE_GREEKLISH,
                            matchType = MatchType.GREEKLISH,
                            explanation = "Greeklish match: $normalized -> $variant"
                        )
                        layerResults.add(LayerDebugResult("Layer 2b: Greeklish", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                        finalResult = result
                        break
                    }
                }
            }
        }
        if (!greeklishMatchFound) {
             layerResults.add(LayerDebugResult("Layer 2b: Greeklish", false, details = "No Greeklish match"))
        }

        // LAYER 2c: Fuzzy match
        var fuzzyMatchFound = false
        if (finalResult == null) {
            val fuzzyMatch = findFuzzyMatch(normalized, sortedMappings)
            if (fuzzyMatch != null) {
                fuzzyMatchFound = true
                val result = CategorizationResult(
                    categoryId = fuzzyMatch.categoryId,
                    categoryName = categoryMap[fuzzyMatch.categoryId],
                    confidence = CONFIDENCE_CANONICAL - 0.05,
                    matchType = MatchType.CANONICAL,
                    explanation = "Fuzzy match: $normalized -> ${fuzzyMatch.merchantPattern}"
                )
                layerResults.add(LayerDebugResult("Layer 2c: Fuzzy", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                finalResult = result
            }
        }
        if (!fuzzyMatchFound) {
             layerResults.add(LayerDebugResult("Layer 2c: Fuzzy", false, details = "No fuzzy match"))
        }
        
        // LAYER 3: Semantic keyword matching
        var semanticMatchFound = false
        if (finalResult == null) {
            val semanticMatch = semanticMatcher.findBestMatch(merchant, CONFIDENCE_KEYWORD_MIN)
            if (semanticMatch != null) {
                val categoryId = getCategoryIdByName(semanticMatch.categoryName)
                if (categoryId != null) {
                    semanticMatchFound = true
                    val result = CategorizationResult(
                        categoryId = categoryId,
                        categoryName = semanticMatch.categoryName,
                        confidence = semanticMatch.confidence,
                        matchType = MatchType.KEYWORD,
                        explanation = "Keyword match: '${semanticMatch.matchedKeyword}'"
                    )
                    layerResults.add(LayerDebugResult("Layer 3: Semantic", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                    finalResult = result
                }
            }
        }
        if (!semanticMatchFound) {
             layerResults.add(LayerDebugResult("Layer 3: Semantic", false, details = "No semantic match"))
        }

        // LAYER 4: Context inference
        var contextMatchFound = false
        if (finalResult == null) {
            if (contextEngine.isLikelySurname(normalized) && amount > 0) {
                val contextPrediction = contextEngine.inferFromContext(amount, timestamp)
                if (contextPrediction != null) {
                    val categoryId = getCategoryIdByName(contextPrediction.categoryName)
                    if (categoryId != null) {
                        contextMatchFound = true
                        val result = CategorizationResult(
                            categoryId = categoryId,
                            categoryName = contextPrediction.categoryName,
                            confidence = contextPrediction.confidence,
                            matchType = MatchType.CONTEXT,
                            explanation = "Context inference: ${contextPrediction.reason}"
                        )
                        layerResults.add(LayerDebugResult("Layer 4: Context", true, result.categoryName, result.categoryId, result.confidence, result.matchType, result.explanation))
                        finalResult = result
                    }
                }
            }
        }
        if (!contextMatchFound) {
             layerResults.add(LayerDebugResult("Layer 4: Context", false, details = "No context inference match"))
        }

        if (finalResult == null) {
            finalResult = CategorizationResult(
                categoryId = null,
                categoryName = null,
                confidence = 0.0,
                matchType = MatchType.UNKNOWN,
                explanation = "No match found"
            )
        }

        return CategorizationDebugTrace(
            inputMerchant = merchant,
            amount = amount,
            timestamp = timestamp,
            normalizedMerchant = normalized,
            canonicalMerchant = canonicalResult.canonicalName,
            strippedParts = canonicalResult.strippedParts,
            layerResults = layerResults,
            finalResult = finalResult
        )
    }
    
    // Legacy method for backward compatibility
    suspend fun categorizeLegacy(merchant: String): Long? {
        val result = categorize(merchant)
        return result.categoryId
    }

    suspend fun normalize(merchant: String): String {
        return merchantNormalizer.normalize(merchant, autoCreate = false).canonical.normalizedName
    }

    private data class CacheData(
        val mappings: List<MerchantCategory>,
        val patternsSet: Set<String>,
        val categoryMap: Map<Long, String>
    )

    private suspend fun getCacheData(): CacheData {
        return cacheMutex.withLock {
            val now = timeProvider.now()
            if (cachedMappings == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                val all = merchantCategoryRepository.getAll()
                cachedMappings = all.sortedByDescending { it.merchantPattern.length }
                cachedPatternsSet = all.map { it.merchantPattern.lowercase() }.toSet()
                
                // Build category ID -> name map from CategoryRepository
                val categories = getCategoryRepository().getAll()
                cachedCategoryMap = categories.associate { it.id to it.name }
                cachedCategoryNameToId = categories.associate { it.name to it.id }
                lastCacheTime = now
            }
            CacheData(cachedMappings!!, cachedPatternsSet!!, cachedCategoryMap!!)
        }
    }

    private suspend fun getCache(): List<MerchantCategory> {
        return getCacheData().mappings
    }

    private suspend fun getPatternsSet(): Set<String> {
        return getCacheData().patternsSet
    }
    
    private suspend fun getCategoryMap(): Map<Long, String> {
        return getCacheData().categoryMap
    }
    
    private suspend fun getCategoryIdByName(categoryName: String): Long? {
        // Use getCacheData() which already populates cachedCategoryNameToId under cacheMutex,
        // avoiding a second independent lock acquisition that would be redundant.
        val cacheData = getCacheData()
        return cachedCategoryNameToId?.get(categoryName)
    }
    
    private fun getCategoryRepository(): CategoryRepository = categoryRepositoryProvider.get()

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) {
        val mapping = createMerchantCategoryMapping(merchantName, categoryId)
        merchantCategoryRepository.insert(mapping)
        invalidateCache()
        Timber.d(
            "Learned merchant: ${mapping.merchantPattern} -> category $categoryId (canonical: ${mapping.normalizedCanonicalName})"
        )
    }

    suspend fun createMerchantCategoryMapping(merchantName: String, categoryId: Long): MerchantCategory {
        val normalized = merchantNormalizer.normalize(merchantName, autoCreate = false).canonical.normalizedName
        return MerchantCategory(
            merchantPattern = normalized,
            categoryId = categoryId,
            normalizedCanonicalName = normalizedCanonicalNameForMerchant(normalized)
        )
    }

    fun normalizedCanonicalNameForMerchant(merchantName: String): String {
        val canonicalResult = canonicalizer.canonicalize(merchantName.lowercase())
        return greeklishNormalizer.normalize(canonicalResult.canonicalName)
    }

    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            cachedPatternsSet = null
            cachedCategoryMap = null
            cachedCategoryNameToId = null
            lastCacheTime = 0
        }
    }
    
    // Utility methods for testing
    fun testCanonicalize(merchant: String): CanonicalResult {
        return canonicalizer.canonicalize(merchant)
    }
    
    fun testGreeklishNormalize(merchant: String): String {
        return greeklishNormalizer.normalize(merchant)
    }
    
    fun testSemanticMatch(merchant: String): List<SemanticMatch> {
        return semanticMatcher.match(merchant)
    }
    
    fun testContextInference(amount: Double, timestamp: Long): ContextPrediction? {
        return contextEngine.inferFromContext(amount, timestamp)
    }
    
    private suspend fun findFuzzyMatch(
        normalized: String,
        mappings: List<MerchantCategory>
    ): MerchantCategory? {
        // Keep fuzzy disabled only for 1-2 character merchants to avoid noisy matches.
        // 3-character tokens still benefit from edit-distance=1 correction.
        if (normalized.length < 3) return null
        
        val threshold = if (normalized.length > 8) 2 else 1
        
        val prefix = normalized.take(2)
        val candidates = mappings.filter { 
            it.merchantPattern.startsWith(prefix) || 
            (it.normalizedCanonicalName?.startsWith(prefix) == true)
        }
        
        var bestMatch: MerchantCategory? = null
        var bestDistance = threshold + 1
        
        for (candidate in candidates) {
            val candidateName = candidate.normalizedCanonicalName ?: candidate.merchantPattern
            
            val distance = StringDistanceUtils.levenshteinDistance(normalized, candidateName)
            
            if (distance <= threshold && distance < bestDistance) {
                bestDistance = distance
                bestMatch = candidate
            }
        }
        
        return bestMatch
    }
}
