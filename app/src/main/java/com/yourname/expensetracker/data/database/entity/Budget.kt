package com.yourname.expensetracker.data.database.entity

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
    val startDate: Long,                // anchor date for period calculation
    val isActive: Boolean = true,
    val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    val rollover: Boolean = false,      // carry unspent to next period
    val createdAt: Long = System.currentTimeMillis(),
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null
)
