package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WRN-5-FIXED: The `receiptId` FK now uses SET_NULL (was CASCADE before migration 108→109).
 * Deleting a receipt preserves return window records. receiptId is nullable to allow
 * orphaned return windows to exist without a source receipt.
 */
@Entity(
    tableName = "return_windows",
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
        Index(value = ["receiptId"]),
        Index(value = ["expenseId"], unique = true),
        Index(value = ["returnDeadline"]),
        Index(value = ["status"])
    ]
)
data class ReturnWindow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receiptId: Long?,
    val expenseId: Long? = null,
    val productName: String,
    val merchantName: String,
    val purchaseDate: Long,
    val returnDays: Int,
    val returnDeadline: Long,
    val returnPolicyUrl: String? = null,
    val returnConditions: String? = null,
    @ColumnInfo(defaultValue = "RETURNABLE") val status: ReturnStatus = ReturnStatus.RETURNABLE,
    val returnedAt: Long? = null,
    /**
     * The amount refunded, if the item was returned.
     */
    val refundAmount: Double? = null,
    /**
     * Currency of the refund amount. Stored explicitly for multi-currency support.
     * When null, the currency should be inferred from the purchase context.
     */
    val refundCurrency: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

enum class ReturnStatus {
    RETURNABLE,
    EXPIRED,
    RETURNED,
    EXCHANGED,
    NON_RETURNABLE
}
