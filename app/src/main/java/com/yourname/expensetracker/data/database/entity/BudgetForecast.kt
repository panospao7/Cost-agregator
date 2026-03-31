package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores AI-generated budget forecasts for tracking and accuracy measurement.
 */
@Entity(
    tableName = "budget_forecasts",
    foreignKeys = [
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
    
    // Status
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ForecastRiskLevel {
    LOW,      // Predicted to stay well under budget
    MEDIUM,   // Might come close to budget limit
    HIGH,     // High risk of exceeding budget
    CRITICAL  // Very likely to exceed budget
}
