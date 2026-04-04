package com.yourname.expensetracker.domain.receipt

/**
 * Platform-agnostic input descriptor for receipt processing.
 */
sealed interface ReceiptSource {
    data class UriRef(val value: String) : ReceiptSource
}
