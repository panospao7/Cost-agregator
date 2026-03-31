package com.yourname.expensetracker.domain.challenge

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages spending challenges and no-spend streaks.
 */
@Singleton
class SpendingChallengeManager @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {
    
    /**
     * Check if user has a no-spend streak today.
     */
    suspend fun checkNoSpendStreak(): NoSpendStatus = withContext(Dispatchers.IO) {
        val today = timeProvider.now()
        val startOfDay = getStartOfDay(today)
        val endOfDay = startOfDay + (24 * 60 * 60 * 1000L)
        
        // Check if any expenses today
        val todayExpenses = expenseDao.getExpensesBetween(startOfDay, endOfDay)
        
        // Check discretionary spending only
        var discretionarySpent = 0.0
        for (expense in todayExpenses) {
            if (expense.transactionType.name == "PURCHASE") {
                discretionarySpent += expense.amount
            }
        }
        
        val hasNoSpend = discretionarySpent == 0.0
        
        // Calculate streak
        var streakDays = if (hasNoSpend) 1 else 0
        if (hasNoSpend) {
            // Check previous days
            var checkDate = startOfDay - (24 * 60 * 60 * 1000L)
            while (true) {
                val dayStart = getStartOfDay(checkDate)
                val dayEnd = dayStart + (24 * 60 * 60 * 1000L)
                val dayExpenses = expenseDao.getExpensesBetween(dayStart, dayEnd)
                
                var daySpent = 0.0
                for (expense in dayExpenses) {
                    if (expense.transactionType.name == "PURCHASE") {
                        daySpent += expense.amount
                    }
                }
                
                if (daySpent == 0.0) {
                    streakDays++
                    checkDate -= (24 * 60 * 60 * 1000L)
                } else {
                    break
                }
            }
        }
        
        NoSpendStatus(
            hasNoSpendToday = hasNoSpend,
            currentStreakDays = streakDays,
            lastSpendDate = if (!hasNoSpend) today else getLastSpendDate(startOfDay),
            savedToday = if (hasNoSpend) calculateAverageDailySpend() else 0.0,
            achievementUnlocked = streakDays >= 7
        )
    }
    
    /**
     * Create a spending challenge.
     */
    fun createChallenge(
        name: String,
        type: ChallengeType,
        durationDays: Int,
        targetAmount: Double? = null,
        categoryId: Long? = null
    ): SpendingChallenge {
        val now = timeProvider.now()
        return SpendingChallenge(
            id = System.currentTimeMillis(), // Temporary ID
            name = name,
            type = type,
            startDate = now,
            endDate = now + (durationDays * 24 * 60 * 60 * 1000L),
            targetAmount = targetAmount,
            categoryId = categoryId,
            isActive = true,
            progress = 0.0
        )
    }
    
    /**
     * Get challenge progress.
     */
    suspend fun getChallengeProgress(challenge: SpendingChallenge): ChallengeProgress = withContext(Dispatchers.IO) {
        val expenses = expenseDao.getExpensesBetween(challenge.startDate, minOf(timeProvider.now(), challenge.endDate))
        
        var spent = 0.0
        for (expense in expenses) {
            if (challenge.categoryId == null || expense.categoryId == challenge.categoryId) {
                if (expense.transactionType.name == "PURCHASE") {
                    spent += expense.amount
                }
            }
        }
        
        val progress = when (challenge.type) {
            ChallengeType.NO_SPEND -> if (spent == 0.0) 100.0 else 0.0
            ChallengeType.BUDGET_LIMIT -> {
                val target = challenge.targetAmount ?: 100.0
                maxOf(0.0, (1 - (spent / target)) * 100)
            }
            ChallengeType.REDUCE_SPENDING -> {
                val target = challenge.targetAmount ?: 100.0
                maxOf(0.0, (1 - (spent / target)) * 100)
            }
            ChallengeType.CATEGORY_SPECIFIC -> {
                val target = challenge.targetAmount ?: 100.0
                maxOf(0.0, (1 - (spent / target)) * 100)
            }
        }
        
        val daysRemaining = ((challenge.endDate - timeProvider.now()) / (24 * 60 * 60 * 1000L)).toInt()
        val isCompleted = timeProvider.now() >= challenge.endDate || progress >= 100.0
        
        ChallengeProgress(
            challenge = challenge,
            amountSpent = spent,
            progressPercent = progress,
            daysRemaining = daysRemaining,
            isCompleted = isCompleted,
            isSuccessful = when (challenge.type) {
                ChallengeType.NO_SPEND -> spent == 0.0
                else -> progress >= 100.0
            }
        )
    }
    
    private fun getStartOfDay(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    private fun getLastSpendDate(before: Long): Long {
        // Would search for last day with spending
        return before - (24 * 60 * 60 * 1000L)
    }
    
    private suspend fun calculateAverageDailySpend(): Double = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = timeProvider.now() - (30 * 24 * 60 * 60 * 1000L)
        val expenses = expenseDao.getExpensesBetween(thirtyDaysAgo, timeProvider.now())
        
        var total = 0.0
        for (expense in expenses) {
            if (expense.transactionType.name == "PURCHASE") {
                total += expense.amount
            }
        }
        
        total / 30.0
    }
}

data class NoSpendStatus(
    val hasNoSpendToday: Boolean,
    val currentStreakDays: Int,
    val lastSpendDate: Long,
    val savedToday: Double,
    val achievementUnlocked: Boolean
)

data class SpendingChallenge(
    val id: Long,
    val name: String,
    val type: ChallengeType,
    val startDate: Long,
    val endDate: Long,
    val targetAmount: Double?,
    val categoryId: Long?,
    val isActive: Boolean,
    val progress: Double
)

enum class ChallengeType {
    NO_SPEND,           // No spending at all
    BUDGET_LIMIT,       // Stay under X amount
    REDUCE_SPENDING,    // Spend less than previous period
    CATEGORY_SPECIFIC   // Limit spending in specific category
}

data class ChallengeProgress(
    val challenge: SpendingChallenge,
    val amountSpent: Double,
    val progressPercent: Double,
    val daysRemaining: Int,
    val isCompleted: Boolean,
    val isSuccessful: Boolean
)
