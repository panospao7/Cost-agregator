package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.util.AmountUtils
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
        private const val AMOUNT_TOKEN_PATTERN = "\\p{Nd}+(?:[.,٬٫\\s\\u00A0\\u202F]\\p{Nd}{3})*(?:[.,٫]\\p{Nd}{1,2})"
        private val NORMALIZED_WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u202F\\u2007\\u3000]+")

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
            DetectedLanguage.CYRILLIC -> normalizeCyrillicText(text)
            DetectedLanguage.ARABIC -> normalizeArabicText(text)
            DetectedLanguage.CJK -> normalizeCjkText(text)
            DetectedLanguage.UNKNOWN -> normalizeScriptPreservingText(text)
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

    fun normalizeCyrillicText(text: String): String {
        return normalizeScriptPreservingText(text.uppercase(Locale.forLanguageTag("ru")))
    }

    fun normalizeArabicText(text: String): String {
        return normalizeScriptPreservingText(text)
    }

    fun normalizeCjkText(text: String): String {
        return normalizeScriptPreservingText(text)
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
            DetectedLanguage.LATIN -> extractLatinAmount(text, language)
            DetectedLanguage.ARABIC,
            DetectedLanguage.UNKNOWN -> extractLocaleAwareAmount(text, language)
                ?: extractLatinAmount(text, language)
            else -> extractLatinAmount(text, language)
        }
    }

    /**
     * Extract amount from Greek text.
     */
    private fun extractGreekAmount(text: String): Double? {
        val patterns = listOf(
            Regex("(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΑΞΙΑ|ΠΟΣΟ)[\\s:]+(?:[€\\$£¥₽]|EUR|USD|GBP|JPY|CNY|RUB)?[\\s]*($AMOUNT_TOKEN_PATTERN)", RegexOption.IGNORE_CASE),
            Regex("[€\\$£¥₽][\\s]*($AMOUNT_TOKEN_PATTERN)"),
            Regex("($AMOUNT_TOKEN_PATTERN)[\\s]*(?:EUR|USD|GBP|JPY|CNY|RUB|€)", RegexOption.IGNORE_CASE)
        )

        return extractAmountWithPatterns(text, DetectedLanguage.GREEK, patterns)
    }

    /**
     * Extract amount from Latin text.
     */
    private fun extractLatinAmount(text: String, selectedLanguage: DetectedLanguage): Double? {
        val patterns = listOf(
            Regex("TOTAL[\\s:]+(?:[€\\$£¥₽]|EUR|USD|GBP|JPY|CNY|RUB)?[\\s]*($AMOUNT_TOKEN_PATTERN)", RegexOption.IGNORE_CASE),
            Regex("AMOUNT[\\s:]+(?:[€\\$£¥₽]|EUR|USD|GBP|JPY|CNY|RUB)?[\\s]*($AMOUNT_TOKEN_PATTERN)", RegexOption.IGNORE_CASE),
            Regex("[€\\$£¥₽][\\s]*($AMOUNT_TOKEN_PATTERN)"),
            Regex("($AMOUNT_TOKEN_PATTERN)[\\s]*(?:EUR|USD|GBP|JPY|CNY|RUB)", RegexOption.IGNORE_CASE)
        )

        return extractAmountWithPatterns(text, selectedLanguage, patterns)
    }

    private fun extractLocaleAwareAmount(text: String, selectedLanguage: DetectedLanguage): Double? {
        val patterns = listOf(
            Regex("(?:[€\$£¥₽]|EUR|USD|GBP|JPY|CNY|RUB)[\\s]*($AMOUNT_TOKEN_PATTERN)"),
            Regex("($AMOUNT_TOKEN_PATTERN)[\\s]*(?:[€\$£¥₽]|EUR|USD|GBP|JPY|CNY|RUB)")
        )

        return extractAmountWithPatterns(text, selectedLanguage, patterns)
    }

    private fun extractAmountWithPatterns(
        text: String,
        selectedLanguage: DetectedLanguage,
        patterns: List<Regex>
    ): Double? {
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return parseLocaleAmount(match.groupValues[1], selectedLanguage)
            }
        }

        return null
    }

    private fun parseLocaleAmount(amountStr: String, language: DetectedLanguage): Double? {
        val cleaned = canonicalizeAmountCandidate(amountStr, language) ?: return null
        return AmountUtils.parseAmount(cleaned)
    }

    private fun canonicalizeAmountCandidate(amountStr: String, language: DetectedLanguage): String? {
        val normalized = buildString {
            amountStr.forEach { char ->
                when {
                    Character.isDigit(char) -> {
                        val numericValue = Character.getNumericValue(char)
                        if (numericValue in 0..9) {
                            append(numericValue)
                        }
                    }
                    isSupportedAmountSeparator(char) -> append(char)
                    char.isWhitespace() || char == '\u00A0' || char == '\u202F' -> append(' ')
                }
            }
        }.trim()

        if (normalized.none { it.isDigit() }) return null

        val decimalSeparatorIndex = detectDecimalSeparatorIndex(normalized, language)
        return buildString {
            normalized.forEachIndexed { index, char ->
                when {
                    char.isDigit() -> append(char)
                    index == decimalSeparatorIndex -> append(normalizeDecimalSeparator(char))
                    isSupportedAmountSeparator(char) || char == ' ' -> Unit
                }
            }
        }.ifBlank { null }
    }

    private fun detectDecimalSeparatorIndex(amountStr: String, language: DetectedLanguage): Int? {
        val separatorIndices = amountStr.mapIndexedNotNull { index, char ->
            index.takeIf { isSupportedAmountSeparator(char) }
        }
        val lastSeparatorIndex = separatorIndices.lastOrNull() ?: return null
        val digitsAfterSeparator = amountStr.substring(lastSeparatorIndex + 1).count { it.isDigit() }

        if (digitsAfterSeparator !in 1..2) return null

        val separator = amountStr[lastSeparatorIndex]
        return when (language) {
            DetectedLanguage.ARABIC,
            DetectedLanguage.UNKNOWN -> lastSeparatorIndex.takeIf { separator == '٫' || separator == ',' || separator == '.' }
            else -> lastSeparatorIndex.takeIf { separator == '.' || separator == ',' || separator == '٫' }
        }
    }

    private fun normalizeDecimalSeparator(separator: Char): Char {
        return when (separator) {
            ',' -> ','
            else -> '.'
        }
    }

    private fun isSupportedAmountSeparator(char: Char): Boolean {
        return char == '.' || char == ',' || char == '٫' || char == '٬'
    }

    private fun normalizeScriptPreservingText(text: String): String {
        return text
            .replace(NORMALIZED_WHITESPACE_REGEX, " ")
            .trim()
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
