package com.yourname.expensetracker.domain.model

data class SavingsGoal(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: Long?,
    val protectionLevel: GoalProtectionLevel,
    val createdAt: Long = 0L
) {
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
        require(targetAmount.isFinite() && targetAmount > 0.0) { "targetAmount must be a positive finite number" }
        require(currentAmount.isFinite() && currentAmount >= 0.0) { "currentAmount must be a non-negative finite number" }
        require(targetDate == null || targetDate >= 0L) { "targetDate cannot be negative" }
        require(createdAt >= 0L) { "createdAt cannot be negative" }
    }
}

enum class GoalProtectionLevel {
    STRICT,
    WARNING,
    TRACKING
}
