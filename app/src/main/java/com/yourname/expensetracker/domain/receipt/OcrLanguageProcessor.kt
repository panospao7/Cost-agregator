package com.yourname.expensetracker.domain.receipt

import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-language support for OCR text processing.
 * Detects and normalizes text from different languages.
 */
@Singleton
class OcrLanguageProcessor @Inject constructor() {
    companion object {
        // Common character sets for language detection
        private val GREEK_CHARS = Regex("[Α-Ωα-ωάέήίόύώϊϋΐΰϙϛϝϟϡ]")
        private val CYRILLIC_CHARS = Regex("[А-Яа-я]")
        private val ARABIC_CHARS = Regex("[\\u0600-\\u06FF]")
        private val CJK_CHARS = Regex("[\\u4E00-\\u9FFF]")
        
        // Known patterns for different languages
        private val GREEK_MERCHANT_PATTERNS = listOf(
            "ΣΥΝΟΛΟ", "ΤΕΛΙΚΟ", "ΠΛΗΡΩΤΕΟ", "ΑΞΙΑ", "ΠΟΣΟ",
            "ΚΑΤΑΣΤΗΜΑ", "ΕΠΙΧΕΙΡΗΣΗ", "ΕΤΑΙΡΙΑ"
        )
    }

    /**
     * Detect the primary language of OCR text.
     */
    fun detectLanguage(text: String): DetectedLanguage {
        val greekCount = GREEK_CHARS.findAll(text).count()
        val cyrillicCount = CYRILLIC_CHARS.findAll(text).count()
        val arabicCount = ARABIC_CHARS.findAll(text).count()
        val cjkCount = CJK_CHARS.findAll(text).count()
        val totalChars = text.filter { it.isLetter() }.length
        
        if (totalChars == 0) return DetectedLanguage.UNKNOWN
        
        return when {
            greekCount > totalChars * 0.3 -> DetectedLanguage.GREEK
            cyrillicCount > totalChars * 0.3 -> DetectedLanguage.CYRILLIC
            arabicCount > totalChars * 0.3 -> DetectedLanguage.ARABIC
            cjkCount > totalChars * 0.3 -> DetectedLanguage.CJK
            else -> DetectedLanguage.LATIN
        }
    }

    /**
     * Normalize text based on detected language.
     */
    fun normalizeForLanguage(text: String, language: DetectedLanguage): String {
        return when (language) {
            DetectedLanguage.GREEK -> normalizeGreekText(text)
            DetectedLanguage.LATIN -> normalizeLatinText(text)
            else -> normalizeLatinText(text) // Default to Latin normalization
        }
    }

    /**
     * Normalize Greek text (handle accents, convert to uppercase, etc).
     */
    fun normalizeGreekText(text: String): String {
        return text
            .uppercase(Locale.forLanguageTag("el"))
            .replace(Regex("[Ά]"), "Α")
            .replace(Regex("[Έ]"), "Ε")
            .replace(Regex("[Ή]"), "Η")
            .replace(Regex("[Ί]"), "Ι")
            .replace(Regex("[Ό]"), "Ο")
            .replace(Regex("[Ύ]"), "Υ")
            .replace(Regex("[Ώ]"), "Ω")
            .replace(Regex("[ς]"), "Σ") // Final sigma to normal sigma
            .trim()
    }

    /**
     * Normalize Latin text.
     */
    fun normalizeLatinText(text: String): String {
        return text
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9\\s]"), " ") // Keep only alphanumeric and spaces
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Auto-detect and normalize text.
     */
    fun autoNormalize(text: String): LanguageProcessingResult {
        val language = detectLanguage(text)
        val normalized = normalizeForLanguage(text, language)
        
        return LanguageProcessingResult(
            originalText = text,
            detectedLanguage = language,
            normalizedText = normalized,
            confidence = calculateLanguageConfidence(text, language)
        )
    }

    /**
     * Calculate confidence in language detection.
     */
    private fun calculateLanguageConfidence(text: String, language: DetectedLanguage): Double {
        val totalChars = text.filter { it.isLetter() }.length
        if (totalChars == 0) return 0.0
        
        val targetChars = when (language) {
            DetectedLanguage.GREEK -> GREEK_CHARS.findAll(text).count()
            DetectedLanguage.CYRILLIC -> CYRILLIC_CHARS.findAll(text).count()
            DetectedLanguage.ARABIC -> ARABIC_CHARS.findAll(text).count()
            DetectedLanguage.CJK -> CJK_CHARS.findAll(text).count()
            DetectedLanguage.LATIN -> text.filter { it in 'A'..'Z' || it in 'a'..'z' }.length
            DetectedLanguage.UNKNOWN -> 0
        }
        
        return (targetChars.toDouble() / totalChars).coerceIn(0.0, 1.0)
    }

    /**
     * Extract amount with language-specific patterns.
     */
    fun extractAmount(text: String, language: DetectedLanguage): Double? {
        return when (language) {
            DetectedLanguage.GREEK -> extractGreekAmount(text)
            DetectedLanguage.LATIN -> extractLatinAmount(text)
            else -> extractLatinAmount(text)
        }
    }

    /**
     * Extract amount from Greek text.
     */
    private fun extractGreekAmount(text: String): Double? {
        // Greek receipts often use comma as decimal separator
        // Look for patterns like "ΣΥΝΟΛΟ 25,50" or "25.50 €"
        val patterns = listOf(
            Regex("ΣΥΝΟΛΟ[\\s:]+([0-9]+[.,][0-9]{2})"),
            Regex("ΤΕΛΙΚΟ[\\s:]+([0-9]+[.,][0-9]{2})"),
            Regex("ΠΛΗΡΩΤΕΟ[\\s:]+([0-9]+[.,][0-9]{2})"),
            Regex("([0-9]+[.,][0-9]{2})[\\s]*€")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val amountStr = match.groupValues[1]
                    .replace(",", ".") // Convert comma to dot
                return amountStr.toDoubleOrNull()
            }
        }
        
        return null
    }

    /**
     * Extract amount from Latin text.
     */
    private fun extractLatinAmount(text: String): Double? {
        val patterns = listOf(
            Regex("TOTAL[\\s:]+\\$?([0-9]+[.,][0-9]{2})"),
            Regex("AMOUNT[\\s:]+\\$?([0-9]+[.,][0-9]{2})"),
            Regex("\\$([0-9]+[.,][0-9]{2})"),
            Regex("([0-9]+[.,][0-9]{2})[\\s]*EUR"),
            Regex("([0-9]+[.,][0-9]{2})[\\s]*USD")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text.uppercase())
            if (match != null) {
                val amountStr = match.groupValues[1]
                    .replace(",", "") // Remove thousand separators
                return amountStr.toDoubleOrNull()
            }
        }
        
        return null
    }
}

enum class DetectedLanguage {
    GREEK,
    LATIN,
    CYRILLIC,
    ARABIC,
    CJK,
    UNKNOWN
}

data class LanguageProcessingResult(
    val originalText: String,
    val detectedLanguage: DetectedLanguage,
    val normalizedText: String,
    val confidence: Double
)
