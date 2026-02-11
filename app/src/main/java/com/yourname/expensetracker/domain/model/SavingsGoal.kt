package com.yourname.expensetracker.domain.model

data class SavingsGoal(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: Long?,
    val protectionLevel: GoalProtectionLevel
)

enum class GoalProtectionLevel {
    STRICT,
    WARNING,
    TRACKING
}
