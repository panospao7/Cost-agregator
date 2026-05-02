package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores AI-generated budget forecasts for tracking and accuracy measurement.
 *
 * ## E2: Missing unique constraint on (budgetId, targetPeriodStart)
 * There is no DB-level unique constraint preventing duplicate forecasts for the
 * same budget and period. The same `(budgetId, targetPeriodStart)` pair can
 * appear multiple times, which could lead to:
 *   - Ambiguous "latest forecast" lookups (callers rely on MAX(forecastDate))
 *   - Duplicate accuracy calculations when actuals arrive
 *
 * A future migration should add:
 * ```
 * CREATE UNIQUE INDEX IF NOT EXISTS idx_budget_forecasts_budget_period
 *     ON budget_forecasts (budgetId, targetPeriodStart);
 * ```
 * Until then, deduplication is handled in the repository layer
 * (BudgetForecastRepository checks existence before inserting).
 */
@Entity(
    tableName = "budget_forecasts",
    foreignKeys = [
        // DB-8: CASCADE on budgetId — deleting a budget removes its forecast history.
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
        Index(value = ["isActive"])
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
    val confidenceScore: Double,   // 0.0-1.0, AI confidence
    
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
