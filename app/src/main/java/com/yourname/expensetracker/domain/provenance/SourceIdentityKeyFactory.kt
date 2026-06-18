package com.yourname.expensetracker.domain.provenance

/**
 * CURR-SL-01: Canonical source identity key builder.
 *
 * Produces deterministic, privacy-safe identity keys for deduplication.
 * Local Room IDs are stored raw; external IDs must be pre-hashed.
 */
object SourceIdentityKeyFactory {

    fun localRawNotification(notificationId: Long): String =
        "local:raw_notification:$notificationId"

    fun localPendingReview(reviewId: Long): String =
        "local:pending_review:$reviewId"

    fun localScannedReceipt(receiptId: Long): String =
        "local:scanned_receipt:$receiptId"

    fun localEmailReceiptSource(sourceId: Long): String =
        "local:email_receipt_source:$sourceId"

    fun localReceiptExpenseLink(linkId: Long): String =
        "local:receipt_expense_link:$linkId"

    fun csvImportRow(batchId: String, rowNumber: Int): String =
        "import:csv:$batchId:row:$rowNumber"

    fun jsonImportRow(batchId: String, rowNumber: Int): String =
        "import:json:$batchId:row:$rowNumber"

    fun externalBankTransaction(providerId: String, txHash: String): String =
        "external:bank_transaction:$providerId:$txHash"

    fun externalEmailMessage(messageHash: String): String =
        "external:email_message:$messageHash"

    fun legacySource(sourceName: String, expenseId: Long): String =
        "legacy:source:$sourceName:expense:$expenseId"

    fun recurringRule(ruleId: Long): String =
        "local:recurring_rule:$ruleId"

    fun recurringOccurrence(occurrenceId: Long): String =
        "local:recurring_occurrence:$occurrenceId"

    fun plannedExpense(plannedId: Long): String =
        "local:planned_expense:$plannedId"

    fun groupExpense(groupId: Long): String =
        "local:group:$groupId"

    fun manualEntry(userId: String?, expenseId: Long): String =
        "manual:${userId ?: "unknown"}:expense:$expenseId"

    fun bankSyncRun(runId: Long): String =
        "local:bank_sync_run:$runId"

    fun fileImport(importRunId: Long): String =
        "local:file_import:$importRunId"

    fun bankConnection(connectionId: Long): String =
        "local:bank_connection:$connectionId"

    fun bankAccount(accountId: Long): String =
        "local:bank_account:$accountId"

    fun bankStatementImportRun(runId: Long): String =
        "local:bank_statement_import_run:$runId"

    fun bankStatementImportItem(itemId: Long): String =
        "local:bank_statement_import_item:$itemId"

    fun csvImportRun(runId: Long): String =
        "local:csv_import_run:$runId"

    fun jsonImportRun(runId: Long): String =
        "local:json_import_run:$runId"

    fun debugTool(toolId: Long): String =
        "local:debug_tool:$toolId"

    fun migration(migrationVersion: Int): String =
        "migration:v$migrationVersion"

    /**
     * P2: Derives a migration identity key from an operation run ID.
     * This allows each migration/backfill run to have a unique identity
     * rather than collapsing all migrations into a single key.
     */
    fun migrationFromRun(operationRunId: Long): String =
        "migration:run:$operationRunId"
}
