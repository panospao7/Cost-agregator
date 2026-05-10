package com.yourname.expensetracker.domain.receipt.lifecycle

sealed class EmailReceiptProcessResult {
    data class Success(val receiptId: Long, val expenseIds: List<Long> = emptyList()) : EmailReceiptProcessResult()
    data class Duplicate(val existingReceiptId: Long) : EmailReceiptProcessResult()
    data class Error(val message: String, val cause: Throwable? = null) : EmailReceiptProcessResult()
}