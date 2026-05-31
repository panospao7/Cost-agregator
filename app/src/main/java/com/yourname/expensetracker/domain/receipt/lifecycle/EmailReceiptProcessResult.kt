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

    /**
     * The email receipt row was saved, but NO approved expense was created —
     * the outcome requires human/follow-up attention. This is returned instead
     * of a misleading [Success] with empty expense ids (P11-CURRENT-011), and
     * for low-confidence parses that must not silently auto-approve an expense
     * (P11-CURRENT-009).
     *
     * @property receiptId The saved scanned-receipt id (the receipt IS persisted).
     * @property reason    Stable machine reason token (e.g. "validation_failed",
     *                     "insert_conflict", "create_error", "low_confidence").
     * @property confidence Parser confidence that led here, when relevant.
     */
    data class NeedsReview(
        val receiptId: Long,
        val reason: String,
        val confidence: Double? = null
    ) : EmailReceiptProcessResult()
}