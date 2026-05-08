package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.StringBKTree
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a merchant lookup operation
 */
data class MerchantLookupResult(
    val canonical: MerchantCanonical,
    val alias: MerchantAlias?,
    val confidence: Float,
    val matchType: MatchType
)

/**
 * Advanced Merchant Name Normalization System.
 */
@Singleton
class MerchantNormalizer @Inject constructor(
    private val repository: MerchantNormalizationRepository,
    private val merchantRules: MerchantRulesRepository,
    private val greeklishNormalizer: GreeklishNormalizer,
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "MerchantNormalizer"
        private val COMMON_IGNORE_WORDS = listOf("THE", "A", "AN", "OF", "AND", "OR", "&")
    }

    private var bkTree: StringBKTree? = null
    private val treeMutex = Mutex()
    private var lastTreeRebuild = 0L
    private val TREE_REBUILD_INTERVAL = 300_000L // 5 minutes

    suspend fun normalize(
        rawName: String,
        autoCreate: Boolean = true,
        categoryId: Long? = null
    ): MerchantLookupResult = withContext(Dispatchers.Default) {
        if (rawName.isBlank()) {
            return@withContext createPlaceholder("Unknown", "unknown", categoryId)
        }
        
        // Input validation - prevent memory issues with extremely long names
        val sanitized = if (rawName.length > 200) {
            rawName.take(200)
        } else {
            rawName
        }
        
        val cleaned = cleanMerchantName(sanitized)
        val normalizedKey = createSearchKey(cleaned)
        
        // 1. Alias match
        repository.getAliasByNormalizedKey(normalizedKey)?.let { alias ->
            val canonical = repository.getCanonicalById(alias.canonicalId)
            if (canonical != null) {
                return@withContext MerchantLookupResult(
                    canonical = canonical,
                    alias = alias,
                    confidence = if (alias.isUserDefined) 1.0f else 0.95f,
                    matchType = if (alias.isUserDefined) MatchType.USER_DEFINED else MatchType.ALIAS_MATCH
                )
            }
        }
        
        // 2. Exact canonical match
        repository.getCanonicalBySearchKey(normalizedKey)?.let { canonical ->
            return@withContext MerchantLookupResult(
                canonical = canonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        
        // 3. Fuzzy matching - use result but don't auto-learn to avoid incorrect aliases
        val fuzzyResult = fuzzyMatch(cleaned, normalizedKey)
        if (fuzzyResult != null && fuzzyResult.confidence >= 0.95f) {
            // Only auto-learn very high confidence fuzzy matches (95%+)
            // TODO (C01): Check both rawName AND normalizedKey before insert.
            // Return AliasLinkResult.Conflict(existing) on conflict instead of silently ignoring.
            repository.linkAliasToCanonical(rawName, normalizedKey, fuzzyResult.canonical.id, isUserDefined = false, timestamp = timeProvider.now())
            return@withContext fuzzyResult
        } else if (fuzzyResult != null) {
            // Lower confidence fuzzy matches - use result but don't auto-learn
            return@withContext fuzzyResult
        }
        
        // 4. Create new
        if (autoCreate) {
            val newCanonical = createNewMerchant(cleaned, normalizedKey, categoryId)
            repository.linkAliasToCanonical(rawName, normalizedKey, newCanonical.id, isUserDefined = false, timestamp = timeProvider.now())
            invalidateTreeCache()
            
            return@withContext MerchantLookupResult(
                canonical = newCanonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.NEW_MERCHANT
            )
        } else {
            return@withContext createPlaceholder(cleaned, normalizedKey, categoryId)
        }
    }

    /**
     * Learns a manual mapping from a cryptic POS name to a user-defined brand name.
     */
    suspend fun learnMerchantAlias(rawName: String, brandName: String) = withContext(Dispatchers.IO) {
        if (rawName.isBlank() || brandName.isBlank()) return@withContext
        if (rawName.equals(brandName, ignoreCase = true)) return@withContext

        // 1. Ensure the brand name exists as a canonical merchant
        val brandLookup = normalize(brandName, autoCreate = true)
        val brandId = brandLookup.canonical.id

        // 2. Repoint all aliases belonging to the old canonical merchant to the new one
        val oldSearchKey = createSearchKey(cleanMerchantName(rawName))
        val oldCanonical = repository.getCanonicalBySearchKey(oldSearchKey)
        
        if (oldCanonical != null && oldCanonical.id != brandId) {
            val aliases = repository.getAliasesForCanonical(oldCanonical.id)
            aliases.forEach { alias ->
                repository.updateAlias(alias.copy(
                    canonicalId = brandId,
                    isUserDefined = true,
                    lastUsedAt = timeProvider.now()
                ))
            }
        }

        // 3. Link the original POS name to this brand ID (just in case it wasn't a canonical)
        val rawNameKey = createSearchKey(cleanMerchantName(rawName))
        repository.linkAliasToCanonical(rawName, rawNameKey, brandId, isUserDefined = true, timestamp = timeProvider.now())
        
        Timber.i("Learned alias: $rawName -> $brandName")
        invalidateTreeCache()
    }

    fun cleanMerchantName(rawName: String): String {
        return merchantRules.cleanMerchantName(rawName)
    }
    private fun createSearchKey(name: String): String = MerchantKeyGenerator.generate(name)

    private suspend fun fuzzyMatch(cleaned: String, normalizedKey: String): MerchantLookupResult? {
        val tree = getOrBuildTree()
        val maxDist = if (normalizedKey.length < 6) 1 else 2
        
        val matches = tree.search(normalizedKey, maxDist)
        if (matches.isEmpty()) return null

        data class RankedCandidate(
            val canonical: MerchantCanonical,
            val distance: Int,
            val similarity: Float
        )

        val ranked = matches.mapNotNull { (searchKey, distance) ->
            val canonical = repository.getCanonicalBySearchKey(searchKey) ?: return@mapNotNull null
            RankedCandidate(
                canonical = canonical,
                distance = distance,
                similarity = StringDistanceUtils.jaroWinklerSimilarity(normalizedKey, searchKey).toFloat()
            )
        }.sortedWith(
            compareBy<RankedCandidate> { it.distance }
                .thenByDescending { it.similarity }
                .thenByDescending { it.canonical.totalOccurrences }
                .thenByDescending { it.canonical.isVerified }
                .thenBy { it.canonical.normalizedName.lowercase() }
                .thenBy { it.canonical.id }
        )

        val best = ranked.firstOrNull() ?: return null
        
        return MerchantLookupResult(
            canonical = best.canonical,
            alias = null,
            confidence = best.similarity,
            matchType = if (best.distance == 0) MatchType.EXACT_MATCH else MatchType.FUZZY_MATCH
        )
    }

    private val creationMutex = Mutex()

    private suspend fun createNewMerchant(cleaned: String, key: String, catId: Long?): MerchantCanonical = creationMutex.withLock {
        // Double-check existence inside the lock to prevent redundant insertion attempts
        repository.getCanonicalBySearchKey(key)?.let { return it }

        // C02: Set createdAt and updatedAt on new merchant canonical entities
        val now = timeProvider.now()
        val canonical = MerchantCanonical(
            normalizedName = formatDisplayName(cleaned),
            searchKey = key,
            categoryId = catId,
            totalOccurrences = 1,
            isVerified = false,
            createdAt = now,
            updatedAt = now
        )
        
        val id = repository.insertCanonical(canonical)
        
        if (id == -1L) {
            // Insertion failed (likely already exists), retrieve the existing ID
            return repository.getCanonicalBySearchKey(key)
                ?: throw IllegalStateException("Failed to create or retrieve merchant: $key")
        }
        
        return canonical.copy(id = id)
    }


    private fun createPlaceholder(cleaned: String, key: String, catId: Long?): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = key, searchKey = key, categoryId = catId),
            alias = null, confidence = 0.0f, matchType = MatchType.NEW_MERCHANT
        )
    }

    private fun formatDisplayName(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            if (word.uppercase() in COMMON_IGNORE_WORDS) word.lowercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun getOrBuildTree(): StringBKTree {
        return treeMutex.withLock {
            val now = timeProvider.now()
            if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
                val tree = StringBKTree.create()
                repository.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
                bkTree = tree
                lastTreeRebuild = now
            }
            bkTree!!
        }
    }

    private suspend fun invalidateTreeCache() = treeMutex.withLock { bkTree = null }
}
