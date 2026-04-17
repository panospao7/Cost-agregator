package com.yourname.expensetracker.domain.categorization

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GreeklishNormalizer @Inject constructor() {

    companion object {
        val DIPHTHONG_REPLACEMENTS_STATIC = listOf(
            "ου" to "ou",
            "ευ" to "ev",
            "αυ" to "av",
            "μπ" to "b",
            "γκ" to "g",
            "ντ" to "d",
            "τζ" to "j",
            "γγ" to "ng",
            "γχ" to "nch",
            "γξ" to "nx"
        )

        val GREEK_TO_LATIN_STATIC = mapOf(
            'α' to "a", 'ά' to "a", 'Α' to "A", 'Ά' to "A",
            'β' to "v", 'Β' to "V",
            'γ' to "g", 'Γ' to "G",
            'δ' to "d", 'Δ' to "D",
            'ε' to "e", 'έ' to "e", 'Ε' to "E", 'Έ' to "E",
            'ζ' to "z", 'Ζ' to "Z",
            'η' to "i", 'ή' to "i", 'Η' to "I", 'Ή' to "I",
            'ι' to "i", 'ί' to "i", 'ϊ' to "i", 'ΐ' to "i", 'Ι' to "I", 'Ί' to "I",
            'κ' to "k", 'Κ' to "K",
            'λ' to "l", 'Λ' to "L",
            'μ' to "m", 'Μ' to "M",
            'ν' to "n", 'Ν' to "N",
            'ξ' to "x", 'Ξ' to "X",
            'ο' to "o", 'ό' to "o", 'Ο' to "O", 'Ό' to "O",
            'π' to "p", 'Π' to "P",
            'ρ' to "r", 'Ρ' to "R",
            'σ' to "s", 'ς' to "s", 'Σ' to "S",
            'τ' to "t", 'Τ' to "T",
            'υ' to "y", 'ύ' to "y", 'ϋ' to "y", 'ΰ' to "y", 'Υ' to "Y", 'Ύ' to "Y",
            'φ' to "f", 'Φ' to "F",
            'χ' to "ch", 'Χ' to "Ch",
            'ψ' to "ps", 'Ψ' to "Ps",
            'ω' to "o", 'ώ' to "o", 'Ω' to "O", 'Ώ' to "O"
        )

        /**
         * Static (no-instance) transliteration of [text] from Greek to Latin.
         * Applies the diphthong-aware pipeline shared with the instance [toLatin].
         * Called by [com.yourname.expensetracker.domain.util.MerchantKeyGenerator].
         */
        fun toLatinStatic(text: String): String {
            var result = text
            for ((gr, lat) in DIPHTHONG_REPLACEMENTS_STATIC) {
                result = result.replace(gr, lat, ignoreCase = true)
            }
            val mapped = result.map { char ->
                GREEK_TO_LATIN_STATIC[char] ?: char.toString()
            }.joinToString("")
            // Strip Latin diacritics (e.g. é → e) via NFD decomposition
            val normalized = java.text.Normalizer.normalize(mapped, java.text.Normalizer.Form.NFD)
            return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }
    }

    private val DIPHTHONG_REPLACEMENTS = DIPHTHONG_REPLACEMENTS_STATIC

    private val GREEK_TO_LATIN = GREEK_TO_LATIN_STATIC

    private val LATIN_TO_GREEK = GREEK_TO_LATIN_STATIC.entries
        .groupBy { it.value }
        .mapValues { it.value.first().key }
    
    private val KNOWN_VARIATIONS = mapOf(
        "sklavenitis" to listOf("σκλαβενίτης", "σκλαβενιτης", "sklabenitis", "sklavvenitis", "skla", "ΣΚΛΑΒΕΝΙΤΗΣ"),
        "ab" to listOf("αβ", "α.β.", "αλφα βήτα", "alfa beta", "ALFA BETA", "AB ΒΑΣΙΛΟΠΟΥΛΟΣ"),
        "vassilopoulos" to listOf("βασιλόπουλος", "βασιλοπουλος", "vasilopoylos", "basil"),
        "lidl" to listOf("λιντλ", "λίντλ", "ΛΙΝΤΛ"),
        "masoutis" to listOf("μασούτης", "μασουτης", "masoyths", "ΜΑΣΟΥΤΗΣ"),
        "mymarket" to listOf("μυ μαρκετ", "my market", "μυμαρκετ", "MY ΜΑΡΚΕΤ"),
        "everest" to listOf("έβερεστ", "εβερεστ", "everist", "ΕΒΕΡΕΣΤ"),
        "gregorys" to listOf("γρηγόρης", "γρηγορης", "grigoris", "ΓΡΗΓΟΡΗΣ"),
        "mikel" to listOf("μικελ", "μιχαλ", "MIKEL"),
        "galaxias" to listOf("γαλαξίας", "γαλαξιας", "galaxias", "ΓΑΛΑΞΙΑΣ"),
        "kritikos" to listOf("κρητικός", "κρητικος", "kritikos", "ΚΡΗΤΙΚΟΣ"),
        "spar" to listOf("σπαρ", "ΣΠΑΡ"),
        "carrefour" to listOf("καρφουρ", " Carrefour "),
        "kaufland" to listOf("καουφλαντ", "ΚΑΟΥΦΛΑΝΤ"),
        "shopflix" to listOf("σοπφλιξ", "shop flix", "ΣΟΠΦΛΙΞ"),
        "skroutz" to listOf("σκρουτζ", "skroutz", "ΣΚΡΟΥΤΖ"),
        "public" to listOf("πάμπλικ", "παμπλικ", "ΠΑΜΠΛΙΚ"),
        "kotsovolos" to listOf("κοτσοβολος", "κοτσοβόλος", "kotsobolos", "ΚΟΤΣΟΒΟΛΟΣ"),
        "plaisio" to listOf("πλαίσιο", "πλαισιο", "plaistio", "ΠΛΑΙΣΙΟ"),
        "jumbo" to listOf("τζάμπο", "τζαμπο", "jumbo", "ΤΖΑΜΠΟ"),
        "ikea" to listOf("ικεα", "ΪΚΕΑ"),
        "cosmote" to listOf("κοσμοτέ", "κοσμοτε", "cosmote", "ΚΟΣΜΟΤΕ"),
        "vodafone" to listOf("βοδαφονη", "βοδαφονέ", "vodafone", "ΒΟΔΑΦΟΝΗ"),
        "nova" to listOf("νόβα", "νοβα", "nova", "ΝΟΒΑ"),
        "dei" to listOf("δεη", "ΔΕΗ", "δημοσια επιχειρηση ηλεκτρισμου"),
        "efood" to listOf("ε φουντ", "e-food", "EFOOD", "ΗΛΕΚΤΡΟΝΙΚΟ ΦΑΓΗΤΟ"),
        "wolt" to listOf("βολτ", "WOLT")
    )

    fun toLatin(greek: String): String = toLatinStatic(greek)
    
    fun toGreek(latin: String): String {
        return latin.map { char ->
            LATIN_TO_GREEK[char.toString().lowercase()] ?: char.toString()
        }.joinToString("")
    }

    fun normalize(merchant: String): String {
        val lower = merchant.lowercase()
        
        val hasGreek = lower.any { it in GREEK_TO_LATIN.keys }
        
        return if (hasGreek) {
            processGreekText(lower)
        } else {
            lower
        }
    }
    
    private fun processGreekText(text: String): String {
        var result = stripAccents(text)
        
        result = processDiphthongs(result)
        
        result = result.map { char ->
            GREEK_TO_LATIN[char] ?: char.toString()
        }.joinToString("")
        
        return result
    }
    
    private fun stripAccents(text: String): String {
        return try {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        } catch (e: Exception) {
            text
        }
    }
    
    private fun processDiphthongs(text: String): String {
        var result = text
        for ((greek, latin) in DIPHTHONG_REPLACEMENTS) {
            result = result.replace(greek, latin, ignoreCase = false)
        }
        return result
    }
    
    fun getVariations(merchant: String): List<String> {
        val normalized = normalize(merchant)
        val variations = mutableSetOf(normalized)
        
        KNOWN_VARIATIONS.forEach { (canonical, alts) ->
            val normalizedCanonical = normalize(canonical)
            val normalizedAlternatives = alts.map { normalize(it) }
            if (normalized == normalizedCanonical || normalized in normalizedAlternatives) {
                variations.add(normalizedCanonical)
                variations.addAll(normalizedAlternatives)
            }
        }
        
        variations.add(normalized)
        
        return variations.toList()
    }

    fun isGreekText(text: String): Boolean {
        return text.any { it in GREEK_TO_LATIN.keys }
    }
    
    fun isGreeklish(text: String): Boolean {
        val greekChars = text.count { it in GREEK_TO_LATIN.keys }
        val latinChars = text.count { it.isLetter() && it !in GREEK_TO_LATIN.keys }
        return greekChars > 0 && latinChars > 0
    }
    
    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        
        if (m == 0) return n
        if (n == 0) return m
        
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        return dp[m][n]
    }
    
    fun findClosestMatch(input: String, maxDistance: Int = 2): String? {
        val normalized = normalize(input).lowercase()
        
        var bestMatch: String? = null
        var bestDistance = maxDistance + 1
        
        KNOWN_VARIATIONS.forEach { (canonical, variations) ->
            variations.forEach { variation ->
                val normVariation = normalize(variation).lowercase()
                val distance = levenshteinDistance(normalized, normVariation)
                if (distance <= maxDistance && distance < bestDistance) {
                    bestDistance = distance
                    bestMatch = canonical
                }
            }
        }
        
        return bestMatch
    }
}
