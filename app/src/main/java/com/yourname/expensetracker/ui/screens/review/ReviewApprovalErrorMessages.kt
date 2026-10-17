package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator

/**
 * R24a: maps a review-approval `Result.Error.message` to user-visible text.
 *
 * Privacy fail-closed: only messages that are members of
 * [ReviewQueueRepository.KNOWN_APPROVAL_ERRORS] are surfaced (as bounded text).
 * Anything else (raw exception text, validation joins, unknown strings) is replaced
 * by the generic [fallbackPrefix].
 */
internal fun reviewApprovalErrorText(message: String?, fallbackPrefix: String): String {
    if (message == null || message !in ReviewQueueRepository.KNOWN_APPROVAL_ERRORS) {
        return fallbackPrefix
    }
    return when (message) {
        ReviewQueueRepository.ERROR_REVIEW_NOT_FOUND -> "This review no longer exists."
        ReviewQueueRepository.ERROR_AMOUNT_EXCEEDS_LIMIT -> "Amount exceeds the maximum allowed."
        ReviewQueueRepository.ERROR_REVIEW_ALREADY_PROCESSED -> "This review was already processed."
        ReviewQueueRepository.ERROR_VALIDATION_FAILED ->
            "Review details are not valid. Please edit them and try again."
        ReviewQueueRepository.ERROR_APPROVAL_FAILED -> fallbackPrefix
        // Remaining known members are bounded, user-facing English sentences.
        else -> message
    }
}

/**
 * R24a: user-visible summary for a receipt batch that had failures (and no partial OCR).
 *
 * Privacy fail-closed: built from counts plus either a bounded known reason or a fixed generic
 * literal. Per-item error strings from the receipt repository are surfaced ONLY when they are a
 * member of [KNOWN_RECEIPT_BATCH_FAILURE_REASONS]; anything else (e.g. the
 * "Failed to process receipt input: <exception class and text>" form, or barrier/exception
 * messages) is replaced by [RECEIPT_BATCH_GENERIC_REASON].
 */
internal val KNOWN_RECEIPT_BATCH_FAILURE_REASONS: Set<String> = setOf(
    ReceiptLifecycleCoordinator.ERROR_INPUT_VALIDATION_FAILED
)

internal const val RECEIPT_BATCH_GENERIC_REASON = "Please try again."

internal fun receiptBatchFailureMessage(
    successCount: Int,
    duplicateCount: Int,
    failureCount: Int,
    firstError: String? = null
): String {
    val summary = if (duplicateCount > 0) {
        "Processed $successCount saved, $duplicateCount duplicates. $failureCount failed"
    } else {
        "Processed $successCount ok. $failureCount failed"
    }
    return if (firstError != null && firstError in KNOWN_RECEIPT_BATCH_FAILURE_REASONS) {
        "$summary: $firstError"
    } else {
        "$summary. $RECEIPT_BATCH_GENERIC_REASON"
    }
}
