package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.StringBKTree
import com.yourname.expensetracker.domain.util.StringDistanceUtils
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
    private val dao: MerchantNormalizationDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MerchantNormalizer"
        
        private val LOCATION_PATTERN = Regex(
            """\s*#[\dA-Za-z]+|""" +
            """\s*-\s*\d+\s*$|""" +
            """\s*Store\s*#?\s*\d+|""" +
            """\s*Branch\s*#?\s*\d+|""" +
            """\s*Unit\s*#?\s*\d+|""" +
            """\s*At\s+[A-Z][a-z]+|""" + // Matches " At Athens", " At London"
            """\s*\([\d\s]+\)"""
        )
        
        private val CORPORATE_SUFFIXES = listOf(
            "INC", "INC.", "LLC", "LTD", "LTD.", "CORP", "CORP.", "CORPORATION",
            "CO", "CO.", "COMPANY", "GMBH", "S.A.", "S.A.S", "B.V.", "A.G."
        )
        
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
        
        val cleaned = cleanMerchantName(rawName)
        val normalizedKey = createSearchKey(cleaned)
        
        // 1. Alias match
        dao.getAliasByNormalizedKey(normalizedKey)?.let { alias ->
            val canonical = dao.getCanonicalById(alias.canonicalId)
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
        dao.getCanonicalBySearchKey(normalizedKey)?.let { canonical ->
            return@withContext MerchantLookupResult(
                canonical = canonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        
        // 3. Fuzzy matching
        val fuzzyResult = fuzzyMatch(cleaned, normalizedKey)
        if (fuzzyResult != null && fuzzyResult.confidence >= 0.80f) {
            dao.linkAliasToCanonical(rawName, fuzzyResult.canonical.id, isUserDefined = false)
            return@withContext fuzzyResult
        }
        
        // 4. Create new
        if (autoCreate) {
            val newCanonical = createNewMerchant(cleaned, normalizedKey, categoryId)
            dao.linkAliasToCanonical(rawName, newCanonical.id, isUserDefined = false)
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

        // 2. Link the original POS name to this brand ID
        dao.linkAliasToCanonical(rawName, brandId, isUserDefined = true)
        
        Log.i(TAG, "Learned alias: $rawName -> $brandName")
        invalidateTreeCache()
    }

    fun cleanMerchantName(rawName: String): String {
        var cleaned = rawName.trim()
        cleaned = LOCATION_PATTERN.replace(cleaned, "")
        
        val upper = cleaned.uppercase()
        for (suffix in CORPORATE_SUFFIXES) {
            if (upper.endsWith(" $suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            } else if (upper.endsWith(",$suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            }
        }
        
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.trim { !it.isLetterOrDigit() }
        return cleaned.ifEmpty { rawName.trim() }
    }

    private fun createSearchKey(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9α-ωά-ώ]"), "")
            .trim()
    }

    private suspend fun fuzzyMatch(cleaned: String, normalizedKey: String): MerchantLookupResult? {
        val tree = getOrBuildTree()
        val maxDist = if (normalizedKey.length < 6) 1 else 2
        
        val matches = tree.search(normalizedKey, maxDist)
        if (matches.isEmpty()) return null
        
        val best = matches.first()
        val canonical = dao.getCanonicalBySearchKey(best.first) ?: return null
        val similarity = StringDistanceUtils.jaroWinklerSimilarity(normalizedKey, best.first)
        
        return MerchantLookupResult(
            canonical = canonical,
            alias = null,
            confidence = similarity.toFloat(),
            matchType = if (best.second == 0) MatchType.EXACT_MATCH else MatchType.FUZZY_MATCH
        )
    }

    private val creationMutex = Mutex()

    private suspend fun createNewMerchant(cleaned: String, key: String, catId: Long?): MerchantCanonical = creationMutex.withLock {
        // Double-check existence inside the lock to prevent redundant insertion attempts
        dao.getCanonicalBySearchKey(key)?.let { return it }

        val canonical = MerchantCanonical(
            normalizedName = formatDisplayName(cleaned),
            searchKey = key,
            categoryId = catId,
            totalOccurrences = 1,
            isVerified = false
        )
        
        val id = dao.insertCanonical(canonical)
        
        if (id == -1L) {
            // Insertion failed (likely already exists), retrieve the existing ID
            return dao.getCanonicalBySearchKey(key)
                ?: throw IllegalStateException("Failed to create or retrieve merchant: $key")
        }
        
        return canonical.copy(id = id)
    }


    private fun createPlaceholder(cleaned: String, key: String, catId: Long?): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = cleaned, searchKey = key, categoryId = catId),
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
            val now = System.currentTimeMillis()
            if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
                val tree = StringBKTree.create()
                dao.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
                bkTree = tree
                lastTreeRebuild = now
            }
            bkTree!!
        }
    }

    private suspend fun invalidateTreeCache() = treeMutex.withLock { bkTree = null }
}
