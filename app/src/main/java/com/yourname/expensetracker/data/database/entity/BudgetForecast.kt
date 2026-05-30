package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores AI-generated budget forecasts for tracking and accuracy measurement.
 *
 * ## Schema v142 (P6) changes
 * - P6-CURRENT-010: Added four data-quality columns — `isPartial`,
 *   `excludedExpenseCount`, `qualityWarningsJson`, `rateBasis`. Legacy rows take
 *   the declared defaults (`0`, `0`, NULL, NULL) on upgrade.
 * - P6-CURRENT-005 / P6-P1-15: The `budgetId → budgets(id)` foreign key was
 *   relaxed from `onDelete = RESTRICT` to `onDelete = CASCADE` (Option A:
 *   CASCADE-purge). Deleting a budget now removes its forecasts instead of being
 *   blocked. Both changes are applied together by `MIGRATION_141_142`, which
 *   recreates `budget_forecasts` (SQLite cannot alter an FK in place).
 *
 * ## I8: DB invariants
 *
 * ### Unique indexes (existing)
 * | Index | Columns | Type | Purpose |
 * |-------|---------|------|---------|
 * | Active | `(budgetId, targetPeriodStart, forecastDate)` | UNIQUE | One forecast per budget + target period + creation instant |
 *
 * The unique composite index is `(budgetId, targetPeriodStart, forecastDate)`
 * (see `@Index` below). Because every refresh stamps a fresh
 * `forecastDate = timeProvider.now()`, a normal regeneration writes a NEW row
 * and never collides. The index only rejects a genuine same-millisecond
 * duplicate, which the DAO insert surfaces as a constraint violation
 * (`OnConflictStrategy.ABORT`, P6-CURRENT-008) rather than overwriting history.
 *
 * ### History model (deactivate-then-insert)
 * History is preserved by deactivation, NOT by REPLACE. The DAO
 * `insertWithDeactivation` runs in a `@Transaction`: it first flips
 * `isActive = 0` on the prior active row for the same
 * `(budgetId, targetPeriodStart, targetPeriodEnd)` and then inserts the new row.
 * The result is exactly one active forecast per budget + target period while all
 * prior forecasts remain as inactive historical rows (used for accuracy/trend
 * analysis). Production inserts ONLY through `insertWithDeactivation`.
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
 * 1. Composite unique index `(budgetId, targetPeriodStart, forecastDate)` — **done** via Room @Index.
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
 * The composite unique index on `(budgetId, targetPeriodStart, forecastDate)`
 * prevents duplicate forecasts for the same budget + period + instant at the DB
 * level. Combined with the deactivate-then-insert history model above, this
 * ensures:
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
        // P6-CURRENT-005 / P6-P1-15: CASCADE on BudgetForecast.budgetId → Budget(id).
        // Relaxed from RESTRICT to CASCADE (Option A: CASCADE-purge) so deleting a
        // budget purges its forecasts instead of blocking the delete. Forecasts are
        // tied to their parent budget; once the budget is gone they have no standalone
        // meaning, so they are removed with it. Applied via the budget_forecasts
        // table-recreate in MIGRATION_141_142 (schema v142).
        ForeignKey(
            entity = Budget::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["budgetId"]),
        Index(value = ["forecastDate"]),
        Index(value = ["isActive"]),
        Index(value = ["budgetId", "targetPeriodStart", "forecastDate"], unique = true)
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
    val createdAt: Long = 0L,

    // P6-CURRENT-010: Data-quality columns (added in schema v142 via MIGRATION_141_142).
    /** True when this forecast was computed from a partial/incomplete expense window. */
    @ColumnInfo(defaultValue = "0")
    val isPartial: Boolean = false,
    /** Count of expenses deliberately excluded from the forecast computation. */
    @ColumnInfo(defaultValue = "0")
    val excludedExpenseCount: Int = 0,
    /** JSON array of data-quality warnings raised during generation; null when none. */
    val qualityWarningsJson: String? = null,
    /** Descriptor of the spend-rate basis used for this forecast; null when unspecified. */
    val rateBasis: String? = null
) {
    /** S8-003: Not persisted — set by engine after generation for ViewModel use. */
    @androidx.room.Ignore var spentToDate: Double = 0.0
    @androidx.room.Ignore var normalizedBudgetAmount: Double = 0.0
}

enum class ForecastRiskLevel {
    LOW,      // Predicted to stay well under budget
    MEDIUM,   // Might come close to budget limit
    HIGH,     // High risk of exceeding budget
    CRITICAL, // Very likely to exceed budget
    UNKNOWN   // Cannot determine risk (e.g. home currency unavailable)
}
