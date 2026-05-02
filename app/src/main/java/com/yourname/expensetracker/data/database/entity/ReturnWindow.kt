package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WRN-5: The `receiptId` FK uses CASCADE — deleting a receipt deletes the return window.
 * A future migration should change this to SET_NULL and make receiptId nullable,
 * preserving return window records when the source receipt is removed.
 * See also Warranty — same pattern.
 */
@Entity(
    tableName = "return_windows",
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
        Index(value = ["receiptId"]),
        Index(value = ["expenseId"], unique = true),
        Index(value = ["returnDeadline"]),
        Index(value = ["status"])
    ]
)
data class ReturnWindow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receiptId: Long,
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
     * TODO: A `currency` field should be added to fully qualify this amount.
     *       Currently the currency is implicit (from the purchase context) but
     *       should be stored explicitly for multi-currency support.
     */
    val refundAmount: Double? = null,
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
