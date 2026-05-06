package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WRN-5-FIXED: The `receiptId` FK now uses SET_NULL (was CASCADE before migration 108→109).
 * Deleting a receipt preserves warranty records. receiptId is nullable to allow orphaned
 * warranties to exist without a source receipt.
 *
 * WRN-6-FIXED: The UNIQUE constraint on `receiptId` was removed in MIGRATION_113_114.
 * Multiple warranties per receipt is now supported (e.g. different products on the same
 * receipt each have their own warranty). The non-unique index on receiptId remains for
 * efficient lookup queries.
 *
 * NOTE: [WarrantyDao.getWarrantyByReceiptId] still returns `Warranty?` for backward
 * compatibility. A future change should update it to return `List<Warranty>` and update
 * all callers.
 */
@Entity(
    tableName = "warranties",
    foreignKeys = [
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        // WRN-6-FIXED: Non-unique index on receiptId — multiple warranties per receipt allowed.
        Index(value = ["receiptId"]),
        Index(value = ["expenseId"]),
        Index(value = ["warrantyEndDate"]),
        Index(value = ["status"])
    ]
)
data class Warranty(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receiptId: Long?,
    val expenseId: Long? = null,
    val productName: String,
    val merchantName: String,
    val purchaseDate: Long,
    val warrantyDurationMonths: Int,
    val warrantyEndDate: Long,
    @ColumnInfo(defaultValue = "MANUFACTURER") val warrantyType: WarrantyType = WarrantyType.MANUFACTURER,
    val supportPhone: String? = null,
    val supportEmail: String? = null,
    val warrantyDocumentUrl: String? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "ACTIVE") val status: WarrantyStatus = WarrantyStatus.ACTIVE,
    val claimedAt: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L,
    // F1: Receipt → Warranty Pipeline fields
    @ColumnInfo(defaultValue = "0") val autoDetected: Boolean = false,
    @ColumnInfo(defaultValue = "0.0") val extractionConfidence: Double = 0.0,
    @ColumnInfo(defaultValue = "manual") val extractionSource: String = "manual", // "manual", "ocr", "email"
    @ColumnInfo(defaultValue = "0") val needsReview: Boolean = false
)

enum class WarrantyType {
    MANUFACTURER,
    EXTENDED,
    STORE,
    THIRD_PARTY
}

enum class WarrantyStatus {
    ACTIVE,
    PENDING_REVIEW,
    EXPIRED,
    CLAIMED,
    TRANSFERRED
}
