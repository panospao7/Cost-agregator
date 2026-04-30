package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Savings Sweep Plan Entity.
 *
 * Stores a computed end-of-month savings sweep plan that the user
 * can accept, modify, or dismiss. This allows us to:
 * - Persist sweep recommendations across app restarts
 * - Track user decisions (accepted/dismissed)
 * - Prevent duplicate prompts for the same month
 */
@Entity(
    tableName = "savings_sweep_plan",
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoal::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("goalId"),
        Index("monthEnd", "status"),
        Index("computedAt")
    ]
)
data class SavingsSweepPlan(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Associated savings goal ID */
    val goalId: Long,

    /** Month end timestamp this sweep applies to */
    val monthEnd: Long,

    /** Total underspend detected across budgets */
    val totalUnderspend: Double,

    /** Risk buffer subtracted for uncertainty */
    val riskBuffer: Double,

    /** Safe amount available to sweep */
    val safeSweepAmount: Double,

    /** Amount allocated to this specific goal */
    val allocatedAmount: Double,

    /** Allocation percentage for this goal */
    val allocationPercentage: Double,

    /** Plan status */
    val status: SweepPlanStatus = SweepPlanStatus.PENDING,

    /** User action timestamp (if accepted/dismissed) */
    val actionedAt: Long? = null,

    /** User notes or modification reason */
    val notes: String? = null,

    /** Confidence score when computed */
    val confidence: Double,

    /** Currency of all monetary fields in this plan */
    @ColumnInfo(defaultValue = "'EUR'")
    val currency: String = "EUR",

    /** When this plan was computed */
    val computedAt: Long = System.currentTimeMillis()
)

/**
 * Status of a savings sweep plan.
 */
enum class SweepPlanStatus {
    /** Plan is active and waiting for user decision */
    PENDING,

    /** User accepted and sweep was applied */
    ACCEPTED,

    /** User dismissed the recommendation */
    DISMISSED,

    /** Plan expired (month ended without action) */
    EXPIRED,

    /** User modified the allocation amounts */
    MODIFIED
}
