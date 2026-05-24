package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable outcome row for each individual transaction parsed from a bank
 * statement.  Every parsed transaction MUST have exactly one item row so that
 * the entire import is reconstructable from the ledger.
 *
 * P3-P1-10 / P3-NEW-10: Run/item ledger.
 */
@Entity(
    tableName = "bank_statement_import_items",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["transactionFingerprint"]),
        Index(value = ["runId", "itemIndex"], unique = true)
    ]
)
data class BankStatementImportItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val itemIndex: Int,
    val transactionFingerprint: String?,
    val status: String,                    // CREATED_REVIEW | DUPLICATE_EXPENSE | DUPLICATE_PENDING_REVIEW | SKIPPED | FAILED
    val duplicateReason: String? = null,
    val expenseId: Long? = null,
    val pendingReviewId: Long? = null,
    val merchant: String? = null,
    val amount: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val currency: String? = null,
    val transactionDate: Long? = null,
    val errorReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_CREATED_REVIEW = "CREATED_REVIEW"
        const val STATUS_DUPLICATE_EXPENSE = "DUPLICATE_EXPENSE"
        const val STATUS_DUPLICATE_PENDING_REVIEW = "DUPLICATE_PENDING_REVIEW"
        const val STATUS_SKIPPED = "SKIPPED"
        const val STATUS_FAILED = "FAILED"
    }
}
