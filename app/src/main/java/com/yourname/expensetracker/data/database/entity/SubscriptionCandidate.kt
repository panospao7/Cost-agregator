package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Entity representing a subscription candidate detected from notification transaction parsing.
 * Stores detection confidence and metadata to allow user confirmation/activation.
 *
 * ## E3: Missing unique constraints
 * There is no DB-level unique constraint on this table. Key gaps:
 *   - `(canonicalMerchant, userAction)` is not unique, allowing duplicate candidates
 *     for the same merchant (e.g. one "pending" + one "accepted").
 *   - `convertedSubscriptionId` is not unique, so two candidates could claim the same
 *     converted subscription.
 *
 * A future migration should consider:
 * ```
 * CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_merchant_action
 *     ON subscription_candidates (canonicalMerchant, userAction)
 *     WHERE userAction = 'pending';
 * CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_converted_id
 *     ON subscription_candidates (convertedSubscriptionId)
 *     WHERE convertedSubscriptionId IS NOT NULL;
 * ```
 * Until then, deduplication is handled in the repository layer.
 */
@Entity(
    tableName = "subscription_candidates",
    indices = [
        Index(value = ["canonicalMerchant"], unique = false),
        Index(value = ["isConverted"]),
        Index(value = ["confidence"])
    ]
)
data class SubscriptionCandidate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Original merchant name from transactions */
    val merchant: String,
    
    /** Normalized/canonical merchant name */
    val canonicalMerchant: String,
    
    /** Average transaction amount */
    val averageAmount: Double,
    
    /** Currency code (e.g., EUR, USD) */
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    
    /** Detected recurrence interval: "weekly", "monthly", "yearly", etc. */
    val detectedInterval: String,
    
    /** Detection confidence score (0.0 to 1.0) */
    val confidence: Double,
    
    /** Number of transactions used for detection */
    val transactionCount: Int,
    
    /** Timestamp of first detected transaction */
    val firstSeen: Long,
    
    /** Timestamp of most recent detected transaction */
    val lastSeen: Long,
    
    /** Estimated annual cost based on average amount and interval */
    val estimatedAnnualCost: Double,
    
    /** Whether this candidate has been converted to an active subscription */
    @ColumnInfo(defaultValue = "0") val isConverted: Boolean = false,
    
    /** ID of the ManualRecurringExpense if converted */
    val convertedSubscriptionId: Long? = null,
    
    /** User action: "accepted", "rejected", "pending" */
    @ColumnInfo(defaultValue = "pending") val userAction: String = "pending",
    
    /** When the candidate was first detected. Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    
    /** When the candidate was last updated. Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
) {
    @get:Ignore
    val averageMoneyAmount: MoneyAmount get() = MoneyAmount(averageAmount, CurrencyCode(currency))

    @get:Ignore
    val estimatedAnnualMoneyAmount: MoneyAmount get() = MoneyAmount(estimatedAnnualCost, CurrencyCode(currency))
}
