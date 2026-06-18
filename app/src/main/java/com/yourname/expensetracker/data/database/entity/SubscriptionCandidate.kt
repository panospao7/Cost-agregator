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
 * ## I8: DB invariants
 *
 * ### Unique indexes (existing)
 * | Index | Columns | Type | Purpose |
 * |-------|---------|------|---------|
 * | Existing | `(canonicalMerchant, userAction)` | UNIQUE | Prevents duplicate candidates for same merchant+action |
 *
 * ### Unique indexes needed (future)
 * - **`(canonicalMerchant, billingCycleDay)`** — Cannot be added yet because
 *   `billingCycleDay` is not currently a column in this table. To add this index,
 *   a migration would first need to introduce the column:
 *   ```sql
 *   ALTER TABLE subscription_candidates ADD COLUMN billingCycleDay INTEGER;
 *   CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_merchant_cycle
 *       ON subscription_candidates (canonicalMerchant, billingCycleDay);
 *   ```
 *   This would prevent duplicate subscription candidates for the same merchant
 *   on the same billing cycle day.
 *
 * - **Partial unique index on `(canonicalMerchant, userAction) WHERE userAction = 'pending'`**
 *   (see "Known limitation" below).

 * ### CHECK constraints needed
 * - `confidence BETWEEN 0 AND 1`
 * - `transactionCount > 0`
 * - `averageAmount > 0`
 * - `estimatedAnnualCost >= 0`
 * - `userAction IN ('pending', 'accepted', 'rejected')`
 *
 *   Room does not support CHECK constraints via annotations. These must be
 *   added via a manual migration.
 *
 * ### Materialized key pattern
 * If callers frequently query for "pending candidates by merchant" or
 * "converted candidates", consider adding a materialized composite index
 * on `(isConverted, userAction)` for faster filtering.
 *
 * ### Migration plan
 * 1. Add `billingCycleDay` column + unique index (future schema migration).
 * 2. Replace the composite unique index with a partial unique index via raw SQL:
 *    ```sql
 *    CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_pending
 *        ON subscription_candidates (canonicalMerchant, userAction)
 *        WHERE userAction = 'pending';
 *    ```
 *    And then drop the wider index:
 *    ```sql
 *    DROP INDEX IF EXISTS index_subscription_candidates_canonicalMerchant_userAction;
 *    ```
 * 3. Add CHECK constraints in the same or next migration.
 *
 * ## G2: Unique constraints enforced via Room @Index
 * A unique composite index on `(canonicalMerchant, userAction)` prevents duplicate
 * candidates for the same merchant and action (e.g. two "pending" records for the
 * same merchant).
 *
 * ### Known limitation — partial index not expressible in Room
 * The ideal index would be a **partial unique index** that only enforces uniqueness
 * when `userAction = 'pending'`:
 * ```
 * CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_merchant_action
 *     ON subscription_candidates (canonicalMerchant, userAction)
 *     WHERE userAction = 'pending';
 * ```
 * Room's [Index] annotation does not support the `WHERE` clause. The current
 * composite unique index is a slightly wider constraint — it also prevents having
 * two "accepted" or two "rejected" entries for the same merchant, which is
 * acceptable in practice. A future migration can add the partial index via raw SQL.
 *
 * `convertedSubscriptionId` uniqueness is handled at the repository layer.
 */
@Entity(
    tableName = "subscription_candidates",
    indices = [
        Index(value = ["canonicalMerchant"], unique = false),
        Index(value = ["isConverted"]),
        Index(value = ["confidence"]),
        Index(value = ["canonicalMerchant", "userAction"], unique = true)
    ]
)
data class SubscriptionCandidate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Original merchant name from transactions */
    val merchant: String,
    
    /**
     * Normalized/canonical merchant name.
     * Desired future unique index: (canonicalMerchant, billingCycleDay)
     * once billingCycleDay column is added (see class KDoc).
     */
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
