package com.yourname.expensetracker.domain.model

data class SavingsGoal(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: Long?,
    val protectionLevel: GoalProtectionLevel,
    val createdAt: Long = 0L
)

enum class GoalProtectionLevel {
    STRICT,
    WARNING,
    TRACKING
}
