package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["transactionType", "date"]),
        Index(value = ["transactionType", "categoryId", "date"]),
        Index(value = ["categoryId", "date"]),
        Index(value = ["amount", "merchant", "date"]),
        Index(value = ["merchant", "date"]),
        Index(value = ["transactionType", "merchant", "date"]),
        Index(value = ["dedupeKey"], unique = true) // Atomic duplicate prevention
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    val currency: String = "EUR",
    
    val merchant: String,
    
    val transactionType: TransactionType,
    
    val date: Long,
    
    val rawNotificationId: Long? = null,
    
    
    val categoryId: Long? = null,
    
    val createdAt: Long = System.currentTimeMillis(),

    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null,

    val dedupeKey: String? = null,

    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    val isNotMine: Boolean = false,
    val ownerName: String? = null,
    val isSharedExpense: Boolean = false,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null
) {
    companion object {
        private const val DUPLICATE_WINDOW_MS = 300_000L // 5 minutes

        fun generateDedupeKey(amount: Double, merchant: String, date: Long): String {
            val normalizedMerchant = merchant.lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .take(20)
            val roundedAmount = "%.2f".format(amount) // Keep decimal format to match SQL
            val dateBucket = date / DUPLICATE_WINDOW_MS
            return "${roundedAmount}_${normalizedMerchant}_$dateBucket"
        }
    }
}

enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}
