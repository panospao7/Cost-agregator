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
        Index(value = ["date"]),
        Index(value = ["categoryId"]),
        Index(value = ["transactionType"]), // New: for type filtering
        Index(value = ["categoryId", "date"]), // New: for category breakdown
        Index(value = ["amount", "merchant", "date"])
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    val currency: String = "EUR", // ISO 4217 Code
    
    val merchant: String, // Extracted merchant name
    
    val transactionType: TransactionType, // PURCHASE, WITHDRAWAL, etc.
    
    val date: Long, // Transaction date (best guess)
    
    val rawNotificationId: Long? = null, // Link to source
    
    val categoryId: Long? = null, // Link to category
    
    val createdAt: Long = System.currentTimeMillis(),

    // New fields
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null
)

enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}
