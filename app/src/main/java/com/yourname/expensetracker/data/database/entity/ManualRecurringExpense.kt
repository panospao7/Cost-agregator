package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.RecurrenceFrequency

@Entity(tableName = "manual_recurring_expenses")
data class ManualRecurringExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    val currency: String = "EUR",
    val frequency: RecurrenceFrequency,
    val nextDate: Long,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
