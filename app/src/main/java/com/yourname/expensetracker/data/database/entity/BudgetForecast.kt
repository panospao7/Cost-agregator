package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores AI-generated budget forecasts for tracking and accuracy measurement.
 *
 * ## I8: DB invariants
 *
 * ### Unique indexes (existing)
 * | Index | Columns | Type | Purpose |
 * |-------|---------|------|---------|
 * | Existing | `(budgetId, targetPeriodStart)` | UNIQUE | Prevents duplicate forecasts for the same budget+period |
 * | Added now | `(budgetId, forecastDate)` | UNIQUE | Prevents multiple forecasts made at the exact same moment |
 *
 * ### Unique indexes needed (future)
 * - **`(budgetId, forecastDate)`** — Already added via Room @Index below.
 *   Ensures no two forecasts are recorded for the same budget at the same
 *   millisecond timestamp. This is a safety net; in practice the repository
 *   layer and the scheduler prevent concurrent inserts.
 *
 * ### CHECK constraints needed
 * - `predictedSpending >= 0`
 * - `predictedRemaining >= 0`
 * - `confidenceScore BETWEEN 0 AND 1`
 * - `overspendProbability BETWEEN 0 AND 1`
 * - `forecastAccuracy IS NULL OR (forecastAccuracy BETWEEN 0 AND 1)`
 * - `targetPeriodEnd > targetPeriodStart`
 *
 *   Room does not support CHECK constraints via annotations. These must be
 *   added via a manual migration (e.g., `ALTER TABLE budget_forecasts ADD
 *   CONSTRAINT ...` or `CREATE TRIGGER ...`).
 *
 * ### Materialized key pattern
 * The existing `(isActive)` index supports efficient filtering of active
 * forecasts. If callers frequently query "latest active forecast per budget",
 * a materialized column `activeOverallKey` (composite of budgetId + isActive)
 * or `activeCategoryKey` (composite of categoryId + isActive) may improve
 * query performance. This is tracked as a future optimization.
 *
 * ### Migration plan
 * 1. Add `(budgetId, forecastDate)` unique index — **done now** via Room @Index.
 * 2. Add CHECK constraints in next schema migration (raw SQL):
 *    ```sql
 *    ALTER TABLE budget_forecasts ADD CONSTRAINT ck_predicted_spending_positive
 *        CHECK (predictedSpending >= 0);
 *    ALTER TABLE budget_forecasts ADD CONSTRAINT ck_confidence_range
 *        CHECK (confidenceScore >= 0 AND confidenceScore <= 1);
 *    ```
 * 3. Evaluate materialized key pattern after usage analytics.
 *
 * ## G1: Unique constraint enforced via Room @Index
 * A unique composite index on `(budgetId, targetPeriodStart)` prevents duplicate
 * forecasts for the same budget and period at the DB level. This ensures:
 *   - Unambiguous "latest forecast" lookups (callers rely on MAX(forecastDate))
 *   - No duplicate accuracy calculations when actuals arrive
 *
 * Deduplication in the repository layer
 * (BudgetForecastRepository checks existence before inserting)
 * remains as an additional safety net.
 */
@Entity(
    tableName = "budget_forecasts",
    foreignKeys = [
        // DB-8: RESTRICT on BudgetForecast.budgetId → Budget(id)
        // Changed from CASCADE to RESTRICT to preserve historical forecasts,
        // which have standalone analytical value (accuracy tracking, trend analysis).
        ForeignKey(
            entity = Budget::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["budgetId"]),
        Index(value = ["forecastDate"]),
        Index(value = ["isActive"]),
        Index(value = ["budgetId", "targetPeriodStart"], unique = true),
        Index(value = ["budgetId", "forecastDate"], unique = true)
    ]
)
data class BudgetForecast(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val budgetId: Long,
    
    // Forecast period
    val forecastDate: Long,        // When this forecast was made
    val targetPeriodStart: Long,   // Start of period being forecasted
    val targetPeriodEnd: Long,     // End of period being forecasted
    
    // Forecast data
    val predictedSpending: Double,   // AI predicted spending amount
    val predictedRemaining: Double,  // Predicted remaining budget
    /**
     * AI confidence score (0.0 to 1.0).
     * CHECK constraint: confidence >= 0 AND confidence <= 1
     */
    val confidenceScore: Double,
    
    // Risk assessment
    val riskLevel: ForecastRiskLevel,
    val overspendProbability: Double, // 0.0-1.0, probability of exceeding budget
    
    // Recommendations (JSON array)
    val recommendationsJson: String? = null,
    
    // Actual results (filled in after period ends)
    val actualSpending: Double? = null,
    val forecastAccuracy: Double? = null, // 0.0-1.0, how accurate was this forecast
    
    // Currency
    @ColumnInfo(defaultValue = "'EUR'")
    val currency: String = "EUR",

    // Status
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)

enum class ForecastRiskLevel {
    LOW,      // Predicted to stay well under budget
    MEDIUM,   // Might come close to budget limit
    HIGH,     // High risk of exceeding budget
    CRITICAL  // Very likely to exceed budget
}
