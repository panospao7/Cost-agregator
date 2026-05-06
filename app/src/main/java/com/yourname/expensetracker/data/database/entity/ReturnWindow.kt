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
 *
 * ## WRN-13-FIXED: refundExpenseId FK added
 *
 * The `refundExpenseId` column (added in MIGRATION_113_114) links a completed return
 * to its refund expense transaction. The FK uses ON DELETE SET NULL — deleting the
 * refund expense preserves the return window record.
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
        ),
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["refundExpenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        // WRN-2: Unique index on receiptId ensures at most one return window
        // per receipt. SQLite UNIQUE allows multiple NULL rows, so orphaned
        // windows (receiptId set to NULL on receipt deletion) are preserved.
        Index(value = ["receiptId"], unique = true),
        // WRN-7: The UNIQUE index on expenseId prevents multiple return windows
        // from referencing the same expense (which is correct), but the DAO
        // query [ReturnWindowDao.getReturnWindowByReceiptId] returns a single
        // result (ReturnWindow?) while the schema allows multiple NULL receiptId
        // rows. If the DAO is ever changed to return List<ReturnWindow>, this
        // UNIQUE constraint must be reviewed to avoid conflicts.
        Index(value = ["expenseId"], unique = true),
        Index(value = ["returnDeadline"]),
        Index(value = ["status"]),
        // WRN-13: Index for refund expense lookup
        Index(value = ["refundExpenseId"])
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
    /**
     * WRN-13-FIXED: Links a completed return to its refund expense transaction.
     * Set when the return is marked as RETURNED. FK references Expense(id) ON DELETE SET NULL.
     */
    val refundExpenseId: Long? = null,
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
