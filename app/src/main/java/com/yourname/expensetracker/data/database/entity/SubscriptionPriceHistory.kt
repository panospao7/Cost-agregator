package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Tracks price history for subscription/recurring expenses.
 * Allows detecting price increases and calculating total price changes over time.
 */
@Entity(
    tableName = "subscription_price_history",
    foreignKeys = [
        // DB-8: CASCADE on subscriptionId — deleting a subscription erases price history.
        ForeignKey(
            entity = ManualRecurringExpense::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["subscriptionId", "recordedAt"])]
)
data class SubscriptionPriceHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subscriptionId: Long, // Foreign key to ManualRecurringExpense
    val amount: Double,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val recordedAt: Long = 0L,
    val changeReason: String? = null // e.g., "Annual increase", "Plan upgrade", "Promotional rate ended"
) {
    @get:Ignore
    val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))
}
