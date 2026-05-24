package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable ledger row for every bank statement import attempt.
 *
 * Written at the start of an import and updated with final counts/status after
 * all transactions are processed.  Together with [BankStatementImportItem],
 * this provides a complete audit trail that survives app restarts and crashes.
 *
 * P3-P1-10 / P3-NEW-10: Run/item ledger.
 */
@Entity(
    tableName = "bank_statement_import_runs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["startedAt"])
    ]
)
data class BankStatementImportRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statementReceiptId: Long?,
    val sourceFingerprint: String?,
    val correlationId: String,
    val status: String,                     // RUNNING | COMPLETED | COMPLETED_WITH_SKIPS | FAILED | STALE_FAILED | CANCELLED
    val startedAt: Long,
    val completedAt: Long? = null,
    val totalItems: Int = 0,
    val processedItems: Int = 0,
    val createdReviewCount: Int = 0,
    val duplicateExpenseCount: Int = 0,
    val duplicatePendingCount: Int = 0,
    val failedItemCount: Int = 0,
    val pdfPartial: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val pagesProcessed: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val totalPages: Int? = null,
    val errorSummary: String? = null
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_COMPLETED_WITH_SKIPS = "COMPLETED_WITH_SKIPS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_STALE_FAILED = "STALE_FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
