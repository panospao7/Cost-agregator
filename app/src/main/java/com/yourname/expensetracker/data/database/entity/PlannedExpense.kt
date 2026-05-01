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
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    @ColumnInfo(defaultValue = "'LEGACY_DEFAULT'") val currencyAssumption: String = "LEGACY_DEFAULT",
    val date: Long, // Planned date
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "0") val isRecurring: Boolean = false,
    val priority: PlannedExpensePriority = PlannedExpensePriority.LIKELY,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Key linking this planned expense to a recurring occurrence (occurrenceKey). */
    val sourceOccurrenceKey: String? = null,
    /** ID of the recurring rule that generated this planned expense. */
    val sourceRecurringRuleId: Long? = null,
    /** Status: PLANNED, FULFILLED, SKIPPED, CANCELLED */
    @ColumnInfo(defaultValue = "'PLANNED'") val status: String = "PLANNED",
    /** ID of the actual expense that fulfilled this planned expense (if any). */
    val linkedActualExpenseId: Long? = null,
    /** Unified merchant key (for matching with actual expenses). */
    val merchantKey: String? = null,
    /** Last update timestamp (must be set on every mutation). */
    val updatedAt: Long = 0L
)

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}
