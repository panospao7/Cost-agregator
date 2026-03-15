package com.yourname.expensetracker.domain.util

/**
 * Utility functions for calculating string distances and similarities.
 */
object StringDistanceUtils {

    /**
     * Calculate Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    minOf(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }

    /**
     * Calculate Levenshtein similarity (0.0 to 1.0).
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        val dist = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - dist.toDouble() / maxLen
    }

    /**
     * Calculate Jaro similarity between two strings.
     */
    fun jaroSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val matchWindow = maxOf(0, maxOf(s1.length, s2.length) / 2 - 1)
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        
        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }

        if (matches == 0) return 0.0

        var transpositions = 0.0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }

        return (matches.toDouble() / s1.length + 
                matches.toDouble() / s2.length + 
                (matches - transpositions / 2.0) / matches) / 3.0
    }

    /**
     * Calculate Jaro-Winkler similarity.
     */
    fun jaroWinklerSimilarity(s1: String, s2: String, prefixWeight: Double = 0.1): Double {
        val jaro = jaroSimilarity(s1, s2)
        if (jaro < 0.7) return jaro

        var prefix = 0
        for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
            if (s1[i] == s2[i]) prefix++
            else break
        }

        return jaro + prefix * prefixWeight * (1.0 - jaro)
    }

    /**
     * Combined similarity measure.
     */
    fun combinedSimilarity(s1: String, s2: String): Double {
        val jaroWinkler = jaroWinklerSimilarity(s1, s2)
        val levenshtein = levenshteinSimilarity(s1, s2)
        
        return 0.7 * jaroWinkler + 0.3 * levenshtein
    }

    /**
     * Checks if two strings are similar within a maximum allowed distance/tolerance.
     * Handles nulls safely. Useful for OCR fallback where 1 or 2 characters might be hallucinated.
     */
    fun isFuzzyMatch(ocrString: String?, targetAnchor: String, maxDistance: Int = 2): Boolean {
        if (ocrString == null) return false
        if (ocrString.contains(targetAnchor, ignoreCase = true)) return true
        
        // Strip out non-alphabetic noise before checking distance to prevent geometric shapes,
        // emojis, and symbols from skewing the distance (e.g. "Starbucks" vs "Starbucks😀").
        val stripNoise = Regex("[^\\p{L}0-9]")
        val stripEmoji = Regex("[\\p{So}]+")
        val cleanedOcr = ocrString.replace(stripEmoji, "").replace(stripNoise, "").trim().uppercase()
        val cleanedTarget = targetAnchor.replace(stripEmoji, "").replace(stripNoise, "").trim().uppercase()
        
        return levenshteinDistance(cleanedOcr, cleanedTarget) <= maxDistance
    }
}
