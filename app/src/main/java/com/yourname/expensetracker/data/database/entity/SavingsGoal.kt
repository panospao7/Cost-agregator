package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    @ColumnInfo(defaultValue = "0.0") val currentAmount: Double = 0.0,
    val targetDate: Long? = null,
    val protectionLevel: GoalProtectionLevel = GoalProtectionLevel.WARNING,
    @ColumnInfo(defaultValue = "'EUR'")
    val currency: String = "EUR",
    @ColumnInfo(defaultValue = "'LEGACY_DEFAULT'")
    val currencyAssumption: String = "LEGACY_DEFAULT",
    val createdAt: Long = System.currentTimeMillis()
)

enum class GoalProtectionLevel {
    STRICT,  // Fully reserved from discretionary
    WARNING, // Noted but not strictly subtracted
    TRACKING // Just for reference
}
