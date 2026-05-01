package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CategorizationStatus {
    PENDING,      // Not yet analyzed
    ANALYZING,    // AI working
    READY,        // Complete, user reviewed
    CORRECTED,    // User made corrections
    SKIPPED       // User opted out
}

enum class MatchStatus {
    UNMATCHED,      // Not yet matched
    AUTO_MATCHED,   // Automatically matched with high confidence
    SUGGESTED,      // Suggestion for manual review
    MANUALLY_MATCHED, // User confirmed match
    REJECTED        // User rejected all suggestions
}

@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["createdAt"]),
        Index(name = "index_scanned_receipts_matchStatus", value = ["matchStatus"]),
        Index(value = ["processingStatus"])
    ]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String?,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    val confidence: Float,
    val expenseId: Long? = null,
    @ColumnInfo(defaultValue = "UNMATCHED") val matchStatus: MatchStatus = MatchStatus.UNMATCHED,
    val matchConfidence: Float? = null,
    val suggestedExpenseId: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "PENDING") val itemCategorizationStatus: CategorizationStatus = CategorizationStatus.PENDING,

    // NEW Phase 4 fields
    @ColumnInfo(defaultValue = "'UNKNOWN'") val sourceType: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val documentType: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'CAPTURED'") val processingStatus: String = "CAPTURED",
    val sourceFingerprint: String? = null,
    val imageHash: String? = null,
    val textFingerprint: String? = null,
    val semanticFingerprint: String? = null,
    val ocrConfidence: Float? = null,
    val parseFailureReason: String? = null,
    /** Must be set to timeProvider.now() on update. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
)
