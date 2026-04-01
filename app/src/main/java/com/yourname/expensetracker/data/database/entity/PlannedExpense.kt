package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "planned_expenses",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["date"]),
        Index(value = ["categoryId"])
    ]
)
data class PlannedExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val amount: Double,
    val date: Long, // Planned date
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "0") val isRecurring: Boolean = false,
    val priority: PlannedExpensePriority = PlannedExpensePriority.LIKELY,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}
