package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SavingsStreak(
    val currentStreakDays: Int,
    val personalBestDays: Int,
    val lastSavingsDate: Long?,
    val monthlyContributions: Int,
    val totalContributedThisMonth: Double
)

data class SavingsAchievement(
    val id: String,
    val title: UiText,
    val description: String,
    val icon: String, // Emoji or icon name
    val isUnlocked: Boolean,
    val unlockedAt: Long?,
    val progress: Double, // 0.0 to 1.0
    val requirement: String
)

@Singleton
class SavingsGamificationEngine @Inject constructor(
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun calculateStreak(userId: String = "default"): SavingsStreak {
        val goals = savingsGoalRepository.getAllGoals().first()
        
        // Find the most recent contribution across all goals
        var lastContributionDate: Long? = null
        var totalContributions = 0
        var totalThisMonth = 0.0
        
        val now = timeProvider.now()
        val monthStart = now - (30L * 24 * 60 * 60 * 1000)
        
        for (goal in goals) {
            // Check if goal was recently updated (contribution made)
            if (goal.currentAmount > 0) {
                // In real implementation, would track contribution history
                // For now, estimate based on goal creation date and current amount
                if (goal.createdAt > monthStart) {
                    totalThisMonth += goal.currentAmount
                    totalContributions++
                }
                
                if (lastContributionDate == null || goal.createdAt > lastContributionDate) {
                    lastContributionDate = goal.createdAt
                }
            }
        }
        
        // Calculate current streak (simplified)
        val currentStreak = if (lastContributionDate != null) {
            val daysSinceLastContribution = (now - lastContributionDate) / (24 * 60 * 60 * 1000)
            if (daysSinceLastContribution <= 1) 5 else 0 // Placeholder logic
        } else 0
        
        return SavingsStreak(
            currentStreakDays = currentStreak,
            personalBestDays = 30, // Would be stored and retrieved
            lastSavingsDate = lastContributionDate,
            monthlyContributions = totalContributions,
            totalContributedThisMonth = totalThisMonth
        )
    }
    
    suspend fun getAchievements(userId: String = "default"): List<SavingsAchievement> {
        val goals = savingsGoalRepository.getAllGoals().first()
        
        var totalSaved = 0.0
        for (goal in goals) {
            totalSaved += goal.currentAmount
        }
        val goalCount = goals.size
        var completedGoals = 0
        for (goal in goals) {
            if (goal.currentAmount >= goal.targetAmount) {
                completedGoals++
            }
        }
        
        return listOf(
            SavingsAchievement(
                id = "first_goal",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GOAL_SETTER),
                description = "Create your first savings goal",
                icon = "🎯",
                isUnlocked = goalCount >= 1,
                unlockedAt = if (goalCount >= 1) timeProvider.now() else null,
                progress = if (goalCount >= 1) 1.0 else 0.0,
                requirement = "Create 1 goal"
            ),
            SavingsAchievement(
                id = "saving_streak_7",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_WEEK_WARRIOR),
                description = "Save for 7 consecutive days",
                icon = "🔥",
                isUnlocked = false, // Would track daily
                unlockedAt = null,
                progress = 0.3,
                requirement = "7 day streak"
            ),
            SavingsAchievement(
                id = "century_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_CENTURY_CLUB),
                description = "Save €100 total",
                icon = "💯",
                isUnlocked = totalSaved >= 100,
                unlockedAt = if (totalSaved >= 100) timeProvider.now() else null,
                progress = (totalSaved / 100.0).coerceIn(0.0, 1.0),
                requirement = "€100 saved"
            ),
            SavingsAchievement(
                id = "goal_crusher",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GOAL_CRUSHER),
                description = "Complete your first savings goal",
                icon = "🏆",
                isUnlocked = completedGoals >= 1,
                unlockedAt = if (completedGoals >= 1) timeProvider.now() else null,
                progress = if (completedGoals >= 1) 1.0 else 
                    (goals.firstOrNull()?.let { it.currentAmount / it.targetAmount } ?: 0.0).coerceIn(0.0, 1.0),
                requirement = "1 goal completed"
            ),
            SavingsAchievement(
                id = "thousand_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GRAND_SAVER),
                description = "Save €1,000 total",
                icon = "💰",
                isUnlocked = totalSaved >= 1000,
                unlockedAt = if (totalSaved >= 1000) timeProvider.now() else null,
                progress = (totalSaved / 1000.0).coerceIn(0.0, 1.0),
                requirement = "€1,000 saved"
            )
        )
    }
    
    fun calculateLevel(totalSaved: Double): Int {
        // Level up every €500 saved
        return (totalSaved / 500.0).toInt() + 1
    }
    
    fun getLevelTitle(level: Int): String {
        return when (level) {
            1 -> "Savings Rookie"
            2 -> "Savings Apprentice"
            3 -> "Savings Journeyman"
            4 -> "Savings Expert"
            5 -> "Savings Master"
            else -> "Savings Legend"
        }
    }
}
