package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R24a: the review approval error mapping surfaces only the repository's known bounded
 * messages and replaces anything else with the generic prefix (privacy fail-closed).
 * Tested at the extracted mapping function; the VM calls it from handleResult.
 */
class ReviewViewModelApprovalErrorMessageTest {

    private val prefix = "Failed to approve"

    @Test
    fun `known sentence message is shown`() {
        assertEquals(
            ReviewQueueRepository.ERROR_CURRENCY_REQUIRED,
            reviewApprovalErrorText(ReviewQueueRepository.ERROR_CURRENCY_REQUIRED, prefix)
        )
    }

    @Test
    fun `known codes map to bounded text and never leak the raw code`() {
        val mapped = listOf(
            ReviewQueueRepository.ERROR_REVIEW_NOT_FOUND,
            ReviewQueueRepository.ERROR_AMOUNT_EXCEEDS_LIMIT,
            ReviewQueueRepository.ERROR_REVIEW_ALREADY_PROCESSED,
            ReviewQueueRepository.ERROR_VALIDATION_FAILED
        ).map { reviewApprovalErrorText(it, prefix) }
        mapped.forEach { text ->
            assertTrue(text.isNotBlank())
            assertFalse(text == prefix)
            assertFalse(text in ReviewQueueRepository.KNOWN_APPROVAL_ERRORS)
        }
        assertEquals(mapped.size, mapped.toSet().size)
    }

    @Test
    fun `generic approval failed code uses the caller prefix`() {
        assertEquals(prefix, reviewApprovalErrorText(ReviewQueueRepository.ERROR_APPROVAL_FAILED, prefix))
    }

    @Test
    fun `unknown raw or null message is replaced by the generic prefix`() {
        assertEquals(
            prefix,
            reviewApprovalErrorText("Validation failed: amount 42.5 at Secret Merchant", prefix)
        )
        assertEquals(prefix, reviewApprovalErrorText("SQLITE_CONSTRAINT: raw", prefix))
        assertEquals(prefix, reviewApprovalErrorText("", prefix))
        assertEquals(prefix, reviewApprovalErrorText(null, prefix))
    }

    @Test
    fun `receipt batch failure message with duplicates uses counts and generic text only`() {
        assertEquals(
            "Processed 2 saved, 1 duplicates. 3 failed. Please try again.",
            receiptBatchFailureMessage(successCount = 2, duplicateCount = 1, failureCount = 3)
        )
    }

    @Test
    fun `receipt batch failure message without duplicates uses counts and generic text only`() {
        assertEquals(
            "Processed 4 ok. 1 failed. Please try again.",
            receiptBatchFailureMessage(successCount = 4, duplicateCount = 0, failureCount = 1)
        )
    }

    @Test
    fun `receipt batch failure message keeps a known bounded reason`() {
        val known = ReceiptLifecycleCoordinator.ERROR_INPUT_VALIDATION_FAILED
        assertEquals(
            "Processed 2 saved, 3 duplicates. 1 failed: Receipt input validation failed",
            receiptBatchFailureMessage(2, 3, 1, known)
        )
        assertEquals(
            "Processed 1 ok. 2 failed: Receipt input validation failed",
            receiptBatchFailureMessage(1, 0, 2, known)
        )
    }

    @Test
    fun `receipt batch failure message replaces raw or unknown reason with generic text`() {
        val raw = "Failed to process receipt input: SecurityException: content://secret/receipt.jpg"
        assertEquals(
            "Processed 1 ok. 2 failed. Please try again.",
            receiptBatchFailureMessage(1, 0, 2, raw)
        )
        assertEquals(
            "Processed 2 saved, 1 duplicates. 3 failed. Please try again.",
            receiptBatchFailureMessage(2, 1, 3, "SQLITE_CONSTRAINT: raw")
        )
        assertEquals(
            "Processed 1 ok. 1 failed. Please try again.",
            receiptBatchFailureMessage(1, 0, 1, null)
        )
    }

    @Test
    fun `result error message flows through the mapping`() {
        val error = Result.Error(message = "raw exception text")
        assertEquals(prefix, reviewApprovalErrorText(error.message, prefix))
    }
}
