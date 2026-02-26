package com.yourname.expensetracker.domain.util

/**
 * Centralized regex patterns and extraction utilities for amount parsing.
 * Consolidates patterns that were duplicated across multiple parsers.
 */
object AmountExtractionUtils {
    val AMOUNT_PATTERN = Regex("""(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})""")
    val CURRENCY_SYMBOL_PATTERN = Regex("""[€\$£¥€]""")
    val CURRENCY_CODE_PATTERN = Regex("""EUR|USD|GBP|CHF""", RegexOption.IGNORE_CASE)
    val POSITIVE_AMOUNT_PATTERN = Regex(
        """(?:paid|sent|purchased|charged|spent|received|transferred)[\s:]*[€\$£]?\s*([\d.,]+)""",
        RegexOption.IGNORE_CASE
    )
    val MERCHANT_CLEANUP_PATTERN = Regex("""[^\w\s-]""")
    val DATE_DDMMYYYY_PATTERN = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})""")
    val DATE_MMDDYYYY_PATTERN = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})""")
    val DATE_ISO_PATTERN = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    fun extractAmount(text: String): Pair<Double, String>? {
        val amountMatch = AMOUNT_PATTERN.find(text) ?: return null
        val amountStr = amountMatch.groupValues[1]
        val amount = AmountUtils.parseAmount(amountStr) ?: return null

        val currencyMatch = CURRENCY_CODE_PATTERN.find(text)
        val currency = currencyMatch?.value?.uppercase() ?: "EUR"

        return amount to currency
    }

    fun extractFirstAmount(text: String): Double? {
        val match = AMOUNT_PATTERN.find(text) ?: return null
        return AmountUtils.parseAmount(match.groupValues[1])
    }

    fun cleanMerchantName(merchant: String): String {
        return MERCHANT_CLEANUP_PATTERN.replace(merchant, "").trim()
    }

    fun hasValidAmount(text: String): Boolean {
        return extractFirstAmount(text) != null
    }
}
