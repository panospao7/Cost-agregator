package com.yourname.expensetracker.domain.receipt.lifecycle

sealed class EmailReceiptProcessResult {
    data class Success(
        val receiptId: Long,
        val createdExpenseIds: List<Long> = emptyList(),
        val linkedExistingExpenseIds: List<Long> = emptyList(),
        /** Backward-compatible union of [createdExpenseIds] and [linkedExistingExpenseIds].
         *  Prefer the explicit fields for new code. */
        @Deprecated("Use createdExpenseIds and linkedExistingExpenseIds instead")
        val expenseIds: List<Long> = createdExpenseIds + linkedExistingExpenseIds
    ) : EmailReceiptProcessResult()
    data class Duplicate(val existingReceiptId: Long) : EmailReceiptProcessResult()
    data class Error(val message: String, val cause: Throwable? = null) : EmailReceiptProcessResult()
}