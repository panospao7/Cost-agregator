package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantNormalizer @Inject constructor(
    private val userCorrectionDao: UserCorrectionDao
) {
    private val correctionCache = mutableMapOf<String, String>()
    private var lastCacheClear = 0L
    private val CACHE_DURATION = 300_000 // 5 min
    private val cacheMutex = Mutex()
    // Suffixes/noise to strip
    private val noisePatterns by lazy {
        listOf(
            Regex("""\s*#?\d{3,}.*$"""),
            Regex("""\s*\*+\d+.*$"""),
            Regex("""\s+(?:GR|ATH|THES|ATHENS|THESSALONIKI|THESSALONIK).*$"""),
            Regex("""\s+(?:BRANCH|STORE|SHOP|KATAST|ΚΑΤΑΣΤ)\s*\d*$"""),
            Regex("""\s+\d{1,2}/\d{1,2}/?\d{0,4}$"""),
            Regex("""\s+(?:SA|AE|ΑΕ|EPE|ΕΠΕ|IKE|ΙΚΕ|LTD|GMBH|SRL|OE|ΟΕ|EE|ΕΕ)\s*$"""),
            Regex("""\s+(?:CARD|VISA|MASTER|MC|AMEX)\s*\**\d*$"""),
            Regex("""\s*-\s*\d+$"""),  // trailing dash + numbers
            Regex("""\s+\d{4,}$"""),   // trailing long number
        )
    }

    private val cleanupRegex1 by lazy { Regex("[^A-ZΑ-Ω0-9 &]") }
    private val cleanupRegex2 by lazy { Regex("\\s+") }

    // Known merchant aliases (common variations → canonical name)
    private val KNOWN_ALIASES = mapOf(
        "SKLAVENITIS" to "Sklavenitis",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Sklavenitis",
        "AB VASILOPOULOS" to "AB Vassilopoulos",
        "AB ΒΑΣΙΛΟΠΟΥΛΟΣ" to "AB Vassilopoulos",
        "LIDL" to "Lidl",
        "STARBUCKS" to "Starbucks",
        "SHELL" to "Shell",
        "BP" to "BP",
        "EFOOD" to "e-food",
        "WOLT" to "Wolt",
        "NETFLIX" to "Netflix",
        "SPOTIFY" to "Spotify",
        "AMAZON" to "Amazon",
        "UBER" to "Uber",
        "BOLT" to "Bolt",
        "COSMOTE" to "Cosmote",
        "VODAFONE" to "Vodafone",
        "WIND" to "Wind",
        "DEH" to "DEH",
        "ΔΕΗ" to "DEH",
        "EYDAP" to "EYDAP",
        "ΕΥΔΑΠ" to "EYDAP",
    )

    fun normalize(merchant: String): String {
        var result = merchant.uppercase().trim()

        // Apply noise removal patterns
        for (pattern in noisePatterns) {
            result = result.replace(pattern, "")
        }

        result = result
            .replace(cleanupRegex1, "")
            .replace(cleanupRegex2, " ")
            .trim()

        return result
    }

    /**
     * Full normalization: strip noise, apply known aliases, apply user corrections
     */
    suspend fun normalizeAndCorrect(merchant: String): String {
        return applyUserCorrections(merchant)
    }

    /**
     * Apply user corrections only (for pipeline use)
     */
    suspend fun applyUserCorrections(merchant: String): String {
        val normalized = normalize(merchant)

        // Check known aliases first
        for ((key, canonical) in KNOWN_ALIASES) {
            if (normalized.contains(key)) {
                return canonical
            }
        }

        val now = System.currentTimeMillis()
        return cacheMutex.withLock {
            if (now - lastCacheClear > CACHE_DURATION) {
                correctionCache.clear()
                lastCacheClear = now
            }
            
            val cached = correctionCache[normalized]
            if (cached != null) {
                cached
            } else {
                val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
                val result = corrected ?: toTitleCase(normalized)
                correctionCache[normalized] = result
                result
            }
        }
    }

    /**
     * Jaccard similarity for matching merchant names
     */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na.contains(nb) || nb.contains(na)) return 0.9f

        // Word overlap (Jaccard)
        val wordsA = na.split(" ").toSet()
        val wordsB = nb.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        return if (union.isNotEmpty()) intersection.size.toFloat() / union.size else 0f
    }

    /**
     * Levenshtein distance for close matches
     */
    fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }

    /**
     * Normalized Levenshtein similarity (0.0 to 1.0)
     */
    fun levenshteinSimilarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        val maxLen = maxOf(na.length, nb.length)
        if (maxLen == 0) return 1.0f
        return 1.0f - levenshteinDistance(na, nb).toFloat() / maxLen
    }

    /**
     * Find best matching merchant name from a list
     */
    fun findBestMatch(merchant: String, candidates: List<String>, threshold: Float = 0.85f): String? {
        var bestMatch: String? = null
        var bestScore = 0f

        for (candidate in candidates) {
            val jaccardScore = similarity(merchant, candidate)
            val levenScore = levenshteinSimilarity(merchant, candidate)
            // Weighted combination
            val score = jaccardScore * 0.4f + levenScore * 0.6f

            if (score > bestScore && score >= threshold) {
                bestScore = score
                bestMatch = candidate
            }
        }

        return bestMatch
    }

    private fun toTitleCase(text: String): String {
        return text.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}
