package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * Validates that [CreateExpenseRequest] carries concrete source provenance
 * for source-specific flows, preventing weak [SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY]
 * fallback in production runtime creates.
 */
object CreateExpenseSourceLinkRequirements {

    fun missingRequirements(request: CreateExpenseRequest): List<String> {
        if (request.sourceLinks.isNotEmpty()) return emptyList()

        return when (request.source) {
            ExpenseSource.REVIEW_APPROVAL ->
                if (request.pendingReviewId == null) listOf("pendingReviewId") else emptyList()

            ExpenseSource.RECEIPT_SCAN, ExpenseSource.RECEIPT_BATCH_REVIEW ->
                if (request.scannedReceiptId == null) listOf("scannedReceiptId") else emptyList()

            ExpenseSource.GROUP_EXPENSE ->
                if (request.groupId == null) listOf("groupId") else emptyList()

            ExpenseSource.BANK_SYNC, ExpenseSource.BANK_API_SYNC ->
                if (request.bankSyncRunId == null) listOf("bankSyncRunId") else emptyList()

            ExpenseSource.CSV_IMPORT ->
                if (request.csvImportBatchId == null || request.csvRowNumber == null)
                    listOf("csvImportBatchId", "csvRowNumber")
                else emptyList()

            ExpenseSource.EMAIL_RECEIPT ->
                if (request.emailReceiptSourceId == null) listOf("emailReceiptSourceId") else emptyList()

            ExpenseSource.NOTIFICATION_AUTO_ACCEPT, ExpenseSource.SMS_NOTIFICATION ->
                if (request.rawNotificationId == null) listOf("rawNotificationId") else emptyList()

            else -> emptyList()
        }
    }
}
