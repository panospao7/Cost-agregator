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
 * Stores anomaly alerts for anomalous transactions detected in near real-time.
 * Used for deduplication, cooldown management, and user feedback tracking.
 */
@Entity(
    tableName = "anomaly_alerts",
    foreignKeys = [
        // DB-8: CASCADE on expenseId — deleting an expense removes its anomaly alerts.
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["merchant", "alertedAt"]),
        Index(value = ["severity", "alertedAt"]),
        Index(value = ["dismissed", "alertedAt"]),
        Index(value = ["category", "alertedAt"])
    ]
)
data class AnomalyAlert(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "expenseId")
    val expenseId: Long,

    @ColumnInfo(name = "merchant")
    val merchant: String,

    @ColumnInfo(name = "category")
    val category: String?,

    @ColumnInfo(name = "amount")
    val amount: Double,

    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    @ColumnInfo(defaultValue = "0.0") val baseAmount: Double? = null,
    @ColumnInfo(defaultValue = "'EUR'") val baseCurrency: String? = null,

    @ColumnInfo(name = "anomalyReason")
    val anomalyReason: String,

    @ColumnInfo(name = "severity")
    val severity: String, // "LOW", "MEDIUM", "HIGH"

    @ColumnInfo(name = "alertedAt")
    val alertedAt: Long,

    @ColumnInfo(name = "dismissed", defaultValue = "0")
    val dismissed: Boolean = false,

    @ColumnInfo(name = "dismissedAt")
    val dismissedAt: Long? = null,

    @ColumnInfo(name = "userFeedback")
    val userFeedback: String? = null // "looks_normal", "was_anomaly"
) {
    @get:Ignore
    val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))

    @get:Ignore
    val baseMoneyAmount: MoneyAmount? get() =
        if (baseAmount != null && baseCurrency != null)
            MoneyAmount(baseAmount, CurrencyCode(baseCurrency))
        else null
}
