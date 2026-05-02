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
 * Budget Trend enum for Room type converter.
 */
enum class BudgetTrend {
    INCREASING,
    DECREASING,
    STABLE
}

/**
 * Budget Adjustment Recommendation Entity.
 * Stores AI-generated budget adjustment recommendations before user approval.
 */
@Entity(
    tableName = "budget_adjustment_recommendations",
    foreignKeys = [
        ForeignKey(
            entity = Budget::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["budgetId"]),
        Index(value = ["categoryId"]),
        Index(value = ["status", "generatedAt"]),
        Index(value = ["generatedAt"])
    ]
)
data class BudgetAdjustmentRecommendation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val budgetId: Long,                      // Reference to the budget being adjusted
    val categoryId: Long?,                   // Category ID (null for overall budget)
    val categoryName: String,                // Display name of category
    val currentBudget: Double,               // Current budget amount
    val recommendedBudget: Double,           // AI-recommended amount
    val delta: Double,                       // Difference (recommended - current)
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    val deltaPercentage: Double,             // Delta as percentage
    val reason: String,                      // Human-readable explanation
    val confidence: Double,                   // Confidence score (0.0 - 1.0)
    val trend: BudgetTrend,                 // Trend direction
    val status: RecommendationStatus = RecommendationStatus.PENDING,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val generatedAt: Long = 0L,
    val expiresAt: Long? = null,             // Expiration time for recommendation
    val appliedAt: Long? = null,             // When user applied the recommendation
    val dismissedAt: Long? = null           // When user dismissed the recommendation
) {
    @get:Ignore
    val currentBudgetMoneyAmount: MoneyAmount get() = MoneyAmount(currentBudget, CurrencyCode(currency))

    @get:Ignore
    val recommendedBudgetMoneyAmount: MoneyAmount get() = MoneyAmount(recommendedBudget, CurrencyCode(currency))

    @get:Ignore
    val deltaMoneyAmount: MoneyAmount get() = MoneyAmount(delta, CurrencyCode(currency))
}

enum class RecommendationStatus {
    PENDING,     // Waiting for user action
    APPLIED,     // User applied the recommendation
    DISMISSED,   // User dismissed the recommendation
    EXPIRED      // Recommendation expired
}

/**
 * Budget Adjustment Event Entity.
 * Tracks all applied budget adjustments for audit/history purposes.
 */
@Entity(
    tableName = "budget_adjustment_events",
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
        Index(value = ["appliedAt"]),
        Index(value = ["budgetId", "appliedAt"])
    ]
)
data class BudgetAdjustmentEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val budgetId: Long,
    val previousAmount: Double,
    val newAmount: Double,
    val delta: Double,
    val reason: String,
    val confidence: Double,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val appliedAt: Long = 0L,
    val appliedBy: String = "autopilot"      // "autopilot", "manual", "system"
)
