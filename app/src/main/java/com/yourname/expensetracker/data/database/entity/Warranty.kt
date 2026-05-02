package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WRN-5: The `receiptId` FK uses CASCADE — deleting a receipt deletes the warranty.
 * A future migration should change this to SET_NULL and make receiptId nullable,
 * preserving warranty records when the source receipt is removed.
 * See also ReturnWindow — same pattern.
 */
@Entity(
    tableName = "warranties",
    foreignKeys = [
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["receiptId"], unique = true),
        Index(value = ["expenseId"]),
        Index(value = ["warrantyEndDate"]),
        Index(value = ["status"])
    ]
)
data class Warranty(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receiptId: Long,
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
