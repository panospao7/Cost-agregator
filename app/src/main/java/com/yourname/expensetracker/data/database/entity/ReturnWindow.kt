package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        Index(value = ["expenseId"]),
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
    val status: ReturnStatus = ReturnStatus.RETURNABLE,
    val returnedAt: Long? = null,
    val refundAmount: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ReturnStatus {
    RETURNABLE,
    EXPIRED,
    RETURNED,
    EXCHANGED,
    NON_RETURNABLE
}
