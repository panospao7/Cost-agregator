package com.yourname.expensetracker.domain.receipt

/**
 * Platform-agnostic input descriptor for receipt processing.
 */
sealed interface ReceiptSource {
    data class UriRef(val value: String) : ReceiptSource
    data class ParsedContent(
        val rawText: String,
        val merchant: String? = null,
        val amount: Double? = null,
        val date: Long? = null,
        val imagePath: String = ""
    ) : ReceiptSource
}
