package com.yourname.expensetracker.domain.categorization

import javax.inject.Inject
import javax.inject.Singleton

data class CanonicalResult(
    val canonicalName: String,
    val strippedParts: List<String>,
    val confidencePenalty: Double
)

@Singleton
class MerchantCanonicalizer @Inject constructor() {
    
    private val LOCATION_SUFFIXES = listOf(
        // Longer suffixes first to avoid partial matches
        "lagkada", "lagka", 
        "retzikio", "retziki", "retz",
        "thessaloniki", "thess", "heraklion", "iraklio",
        "stores", "store", "shop", "market", "markets",
        "athens", "piraeus", "patra", 
        "larisa", "larisas", "chania",
        "center", "centre", "mall", "avenue", "street", "str",
        "plaza", "square", "nicosia", "limassol", "larnaca"
    )
    
    private val BUSINESS_TYPE_SUFFIXES = listOf(
        // English
        "ae", "sa", "ltd", "llc", "inc", "corp", "oy", "gmbh",
        "m.i.k.e.", "mike", "a.e.", "aebe", "s.a.",
        // Greek corporate structures (after Greeklish normalization)
        "epe", "ike", "oe", "ee", "monoprosopi", "mp", "koinoperi",
        "etaireia", "koinon", "idiwtik", "eteria"
    )
    
    private val BUSINESS_TYPE_PREFIXES = listOf(
        // Greek prefixes (after Greeklish normalization)
        "afoi", "yioi", "ike", "kde", "s", "aeedi", "epe",
        "αφοι", "υιοι", "ικε", "κδε", "σδι", "αεε", "επε",
        // English
        "north", "south", "east", "west", "central",
        "brothers", "sons"
    )
    
    private val REGION_PREFIXES = listOf(
        "north", "south", "east", "west", "central",
        "βορεια", "νοτια", "ανατολικα", "δυτικα", "κεντρικα"
    )

    fun canonicalize(merchant: String): CanonicalResult {
        var normalized = merchant.lowercase().trim()
            .replace(Regex("""[.,\-_/]+"""), " ")
            .replace(Regex("""\s+"""), " ")
        
        val strippedParts = mutableListOf<String>()
        
        // Strip region prefixes
        REGION_PREFIXES.forEach { prefix ->
            if (normalized.startsWith("$prefix ")) {
                normalized = normalized.removePrefix("$prefix ").trim()
                strippedParts.add(prefix)
            }
        }
        
        // Strip business type prefixes (ΑΦΟΙ, ΥΙΟΙ, ΙΚΕ, etc.)
        var strippedPrefix: Boolean
        do {
            strippedPrefix = false
            for (prefix in BUSINESS_TYPE_PREFIXES) {
                if (normalized.startsWith("$prefix ") || normalized.startsWith("$prefix-")) {
                    val prefixToRemove = if (normalized.startsWith("$prefix ")) "$prefix " else "$prefix-"
                    normalized = normalized.removePrefix(prefixToRemove).trim()
                    strippedParts.add(prefix)
                    strippedPrefix = true
                    break
                }
            }
        } while (strippedPrefix)
        
        // Strip location and business type suffixes iteratively until no more can be stripped
        var strippedAny: Boolean
        do {
            strippedAny = false
            
            // Try to strip location suffix
            for (suffix in LOCATION_SUFFIXES) {
                if (normalized.endsWith(" $suffix")) {
                    normalized = normalized.removeSuffix(" $suffix").trim()
                    strippedParts.add(suffix)
                    strippedAny = true
                    break
                } else if (normalized.endsWith(suffix) && normalized.length > suffix.length) {
                    val prevChar = normalized[normalized.length - suffix.length - 1]
                    if (!prevChar.isLetterOrDigit()) {
                        normalized = normalized.removeSuffix(suffix).dropLast(1).trim()
                        strippedParts.add(suffix)
                        strippedAny = true
                        break
                    }
                }
            }
            
            // Try to strip business suffix
            for (suffix in BUSINESS_TYPE_SUFFIXES) {
                val escapedSuffix = Regex.escape(suffix)
                val pattern = Regex("""\b$escapedSuffix[\s\.]*$""", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(normalized)) {
                    normalized = normalized.replace(pattern, "").trim()
                    strippedParts.add(suffix)
                    strippedAny = true
                    break
                }
            }
        } while (strippedAny)
        
        normalized = normalized.trim()
        
        val penalty = when (strippedParts.size) {
            0 -> 0.0
            1 -> 0.03
            2 -> 0.05
            else -> 0.08
        }
        
        return CanonicalResult(
            canonicalName = normalized,
            strippedParts = strippedParts.distinct(),
            confidencePenalty = penalty
        )
    }

    fun isLikelyStoreName(merchant: String): Boolean {
        val normalized = merchant.lowercase()
        return LOCATION_SUFFIXES.any { normalized.contains(it) } ||
               BUSINESS_TYPE_SUFFIXES.any { normalized.endsWith(it) }
    }
}
