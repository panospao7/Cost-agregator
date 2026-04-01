package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        Index(value = ["receiptId"]),
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class WarrantyType {
    MANUFACTURER,
    EXTENDED,
    STORE,
    THIRD_PARTY
}

enum class WarrantyStatus {
    ACTIVE,
    EXPIRED,
    CLAIMED,
    TRANSFERRED
}
