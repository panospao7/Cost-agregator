package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.repository.SavingsContributionEvent
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
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
    private val contributionHistoryRepository: SavingsContributionHistoryRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun calculateStreak(userId: String = "default"): SavingsStreak {
        val contributionMetrics = analyzeContributionHistory()

        return SavingsStreak(
            currentStreakDays = contributionMetrics.currentStreakDays,
            personalBestDays = contributionMetrics.personalBestDays,
            lastSavingsDate = contributionMetrics.lastSavingsDate,
            monthlyContributions = contributionMetrics.monthlyContributions,
            totalContributedThisMonth = contributionMetrics.totalContributedThisMonth
        )
    }
    
    suspend fun getAchievements(userId: String = "default"): List<SavingsAchievement> {
        val goals = savingsGoalRepository.observeSavingsGoals().first()
        val contributionMetrics = analyzeContributionHistory()
        val sortedGoals = goals.sortedBy { it.createdAt }
        val firstGoalUnlockedAt = sortedGoals.firstOrNull()?.createdAt
        val totalSavedThresholds = resolveTotalSavedThresholdUnlocks(goals, contributionMetrics.contributions)
        val bestProgressGoal = goals.maxByOrNull {
            it.currentAmount / it.targetAmount.coerceAtLeast(0.01)
        }
        val goalCrusherUnlockedAt = resolveGoalCrusherUnlockedAt(goals, contributionMetrics.contributions)
        
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
                unlockedAt = firstGoalUnlockedAt,
                progress = if (goalCount >= 1) 1.0 else 0.0,
                requirement = "Create 1 goal"
            ),
            SavingsAchievement(
                id = "saving_streak_7",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_WEEK_WARRIOR),
                description = "Save for 7 consecutive days",
                icon = "🔥",
                isUnlocked = contributionMetrics.sevenDayStreakUnlockedAt != null,
                unlockedAt = contributionMetrics.sevenDayStreakUnlockedAt,
                progress = if (contributionMetrics.sevenDayStreakUnlockedAt != null) {
                    1.0
                } else {
                    (contributionMetrics.currentStreakDays / 7.0).coerceIn(0.0, 1.0)
                },
                requirement = "7 day streak"
            ),
            SavingsAchievement(
                id = "century_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_CENTURY_CLUB),
                description = "Save ${CurrencyFormatter.format(100.0, showCents = false)} total",
                icon = "💯",
                isUnlocked = totalSaved >= 100,
                unlockedAt = if (totalSaved >= 100) totalSavedThresholds[100.0] else null,
                progress = (totalSaved / 100.0).coerceIn(0.0, 1.0),
                requirement = "${CurrencyFormatter.format(100.0, showCents = false)} saved"
            ),
            SavingsAchievement(
                id = "goal_crusher",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GOAL_CRUSHER),
                description = "Complete your first savings goal",
                icon = "🏆",
                isUnlocked = completedGoals >= 1,
                unlockedAt = if (completedGoals >= 1) goalCrusherUnlockedAt else null,
                progress = if (completedGoals >= 1) 1.0 else 
                    (bestProgressGoal?.let {
                        it.currentAmount / it.targetAmount.coerceAtLeast(0.01)
                    } ?: 0.0).coerceIn(0.0, 1.0),
                requirement = "1 goal completed"
            ),
            SavingsAchievement(
                id = "thousand_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GRAND_SAVER),
                description = "Save ${CurrencyFormatter.format(1000.0, showCents = false)} total",
                icon = "💰",
                isUnlocked = totalSaved >= 1000,
                unlockedAt = if (totalSaved >= 1000) totalSavedThresholds[1000.0] else null,
                progress = (totalSaved / 1000.0).coerceIn(0.0, 1.0),
                requirement = "${CurrencyFormatter.format(1000.0, showCents = false)} saved"
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

    private suspend fun analyzeContributionHistory(): ContributionMetrics {
        val now = timeProvider.now()
        val contributions = contributionHistoryRepository.getAllContributions()
        if (contributions.isEmpty()) {
            return ContributionMetrics()
        }

        val sortedContributions = contributions.sortedBy { it.timestamp }
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
        val currentMonthContributions = sortedContributions.filter {
            it.timestamp >= monthStart && it.timestamp < monthEnd
        }
        val contributionDays = sortedContributions
            .map { TimePeriodUtils.getStartOfDay(it.timestamp) }
            .distinct()
            .sorted()

        return ContributionMetrics(
            currentStreakDays = calculateCurrentStreakDays(contributionDays, now),
            personalBestDays = calculatePersonalBestDays(contributionDays),
            lastSavingsDate = sortedContributions.lastOrNull()?.timestamp,
            monthlyContributions = currentMonthContributions.size,
            totalContributedThisMonth = currentMonthContributions.sumOf { it.amount },
            sevenDayStreakUnlockedAt = findStreakUnlockedAt(sortedContributions, 7),
            contributions = sortedContributions
        )
    }

    private fun resolveTotalSavedThresholdUnlocks(
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>,
        contributions: List<SavingsContributionEvent>
    ): Map<Double, Long?> {
        val thresholds = listOf(100.0, 1000.0)
        val unlocks = thresholds.associateWith { null as Long? }.toMutableMap()
        var cumulativeSaved = 0.0

        for (contribution in contributions.sortedBy { it.timestamp }) {
            cumulativeSaved += contribution.amount
            for (threshold in thresholds) {
                if (unlocks[threshold] == null && cumulativeSaved >= threshold) {
                    unlocks[threshold] = contribution.timestamp
                }
            }
        }

        val fallbackTimestamp = goals.minOfOrNull { it.createdAt }
        return unlocks.mapValues { (_, unlockedAt) -> unlockedAt ?: fallbackTimestamp }
    }

    private fun resolveGoalCrusherUnlockedAt(
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>,
        contributions: List<SavingsContributionEvent>
    ): Long? {
        val completedGoalIds = goals
            .filter { it.currentAmount >= it.targetAmount }
            .associateBy { it.id }

        if (completedGoalIds.isEmpty()) {
            return null
        }

        val cumulativeByGoal = mutableMapOf<Long, Double>()
        for (contribution in contributions.sortedBy { it.timestamp }) {
            val goal = completedGoalIds[contribution.goalId] ?: continue
            val updatedAmount = (cumulativeByGoal[goal.id] ?: 0.0) + contribution.amount
            cumulativeByGoal[goal.id] = updatedAmount
            if (updatedAmount >= goal.targetAmount.coerceAtLeast(0.01)) {
                return contribution.timestamp
            }
        }

        return completedGoalIds.values.minOfOrNull { it.createdAt }
    }

    private fun calculateCurrentStreakDays(contributionDays: List<Long>, referenceTime: Long): Int {
        if (contributionDays.isEmpty()) return 0

        val today = TimePeriodUtils.getStartOfDay(referenceTime)
        val yesterday = TimePeriodUtils.addDays(today, -1)
        val latestContributionDay = contributionDays.last()
        if (latestContributionDay < yesterday) return 0

        var streak = 1
        var expectedPreviousDay = TimePeriodUtils.addDays(latestContributionDay, -1)

        for (index in contributionDays.lastIndex - 1 downTo 0) {
            val currentDay = contributionDays[index]
            if (currentDay == expectedPreviousDay) {
                streak++
                expectedPreviousDay = TimePeriodUtils.addDays(expectedPreviousDay, -1)
            } else if (currentDay < expectedPreviousDay) {
                break
            }
        }

        return streak
    }

    private fun calculatePersonalBestDays(contributionDays: List<Long>): Int {
        if (contributionDays.isEmpty()) return 0

        var best = 1
        var current = 1

        for (index in 1 until contributionDays.size) {
            current = if (contributionDays[index] == TimePeriodUtils.addDays(contributionDays[index - 1], 1)) {
                current + 1
            } else {
                1
            }
            best = maxOf(best, current)
        }

        return best
    }

    private fun findStreakUnlockedAt(
        contributions: List<SavingsContributionEvent>,
        targetDays: Int
    ): Long? {
        if (targetDays <= 0 || contributions.isEmpty()) return null

        val latestTimestampByDay = linkedMapOf<Long, Long>()
        for (contribution in contributions) {
            val contributionDay = TimePeriodUtils.getStartOfDay(contribution.timestamp)
            val currentLatest = latestTimestampByDay[contributionDay]
            if (currentLatest == null || contribution.timestamp > currentLatest) {
                latestTimestampByDay[contributionDay] = contribution.timestamp
            }
        }

        val contributionDays = latestTimestampByDay.keys.sorted()
        var runLength = 0
        var previousDay: Long? = null

        for (contributionDay in contributionDays) {
            runLength = if (
                previousDay != null &&
                contributionDay == TimePeriodUtils.addDays(previousDay, 1)
            ) {
                runLength + 1
            } else {
                1
            }

            if (runLength >= targetDays) {
                return latestTimestampByDay[contributionDay]
            }

            previousDay = contributionDay
        }

        return null
    }

    private data class ContributionMetrics(
        val currentStreakDays: Int = 0,
        val personalBestDays: Int = 0,
        val lastSavingsDate: Long? = null,
        val monthlyContributions: Int = 0,
        val totalContributedThisMonth: Double = 0.0,
        val sevenDayStreakUnlockedAt: Long? = null,
        val contributions: List<SavingsContributionEvent> = emptyList()
    )
}
