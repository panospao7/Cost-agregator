package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BudgetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * Budget entity.
 *
 * Active-budget invariants (enforced transactionally in the DAO/repository
 * layer because Room schema must match generated metadata):
 *  - At most one active overall budget: `UNIQUE(isActive) WHERE isActive = 1 AND categoryId IS NULL`
 *  - At most one active budget per category: `UNIQUE(categoryId) WHERE isActive = 1 AND categoryId IS NOT NULL`
 */
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"])
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,              // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    @ColumnInfo(defaultValue = "'ROLLING'") val periodMode: String = "ROLLING", // ROLLING | CALENDAR
    val startDate: Long,                // anchor date for period calculation
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0.75") val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    @ColumnInfo(defaultValue = "0.9") val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    @ColumnInfo(defaultValue = "0") val rollover: Boolean = false,      // carry unspent to next period
    val createdAt: Long = System.currentTimeMillis(),
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null
)
