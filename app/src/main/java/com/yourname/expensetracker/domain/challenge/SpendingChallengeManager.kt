package com.yourname.expensetracker.domain.challenge

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.repository.SpendingChallengeRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages spending challenges and no-spend streaks.
 */
@Singleton
class SpendingChallengeManager @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val spendingChallengeRepository: SpendingChallengeRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun getActiveChallengesSnapshot(): ActiveChallengesSnapshot = withContext(ioDispatcher) {
        val now = timeProvider.now()
        val completedChallengeIds = mutableListOf<Long>()

        val activeChallenges = spendingChallengeRepository.getActiveChallenges().mapNotNull { challenge ->
            val progress = getChallengeProgress(challenge)
            if (progress.isCompleted) {
                completedChallengeIds += challenge.id
                null
            } else {
                challenge.copy(progress = progress.progressPercent)
            }
        }

        if (completedChallengeIds.isNotEmpty()) {
            spendingChallengeRepository.deactivateChallenges(completedChallengeIds, now)
        }

        ActiveChallengesSnapshot(
            challenges = activeChallenges,
            unavailableReason = null
        )
    }

    /**
     * Check if user has a no-spend streak today.
     */
    suspend fun checkNoSpendStreak(): NoSpendStatus = withContext(ioDispatcher) {
        val today = timeProvider.now()
        val startOfDay = getStartOfDay(today)
        val endOfDay = startOfDay + DAY_MS
        val oldestExpenseDate = expenseDao.getOldestExpenseDate()
        val rangeStart = oldestExpenseDate?.let(::getStartOfDay) ?: startOfDay

        val spendingDays = expenseDao.getSpendingDailyTotalsBetween(rangeStart, endOfDay)
        val spendingByDay = spendingDays.associateBy { getStartOfDay(it.startDate) }
        val todaySpent = spendingByDay[startOfDay]?.total ?: 0.0
        val hasNoSpend = todaySpent <= 0.0

        val firstTrackedDay = spendingDays.firstOrNull()?.let { getStartOfDay(it.startDate) } ?: rangeStart
        var streakDays = if (hasNoSpend) 1 else 0
        if (hasNoSpend) {
            var checkDay = startOfDay - DAY_MS
            while (checkDay >= firstTrackedDay) {
                val spent = spendingByDay[checkDay]?.total ?: 0.0
                if (spent > 0.0) break
                streakDays++
                checkDay -= DAY_MS
            }
        }

        val lastSpendDate = spendingDays.lastOrNull()?.let { getStartOfDay(it.startDate) } ?: today

        NoSpendStatus(
            hasNoSpendToday = hasNoSpend,
            currentStreakDays = streakDays,
            lastSpendDate = if (hasNoSpend) lastSpendDate else findLastSpendDate(spendingDays) ?: startOfDay,
            savedToday = if (hasNoSpend) calculateAverageDailySpend() else 0.0,
            achievementUnlocked = streakDays >= 7
        )
    }

    suspend fun createChallenge(
        name: String,
        type: ChallengeType,
        durationDays: Int,
        targetAmount: Double? = null,
        categoryId: Long? = null
    ): SpendingChallenge = withContext(ioDispatcher) {
        require(durationDays > 0) { "durationDays must be greater than 0" }
        require(name.isNotBlank()) { "name must not be blank" }
        if (type == ChallengeType.CATEGORY_SPECIFIC) {
            require(categoryId != null) { "CATEGORY_SPECIFIC challenges require a categoryId" }
        }
        if (type == ChallengeType.BUDGET_LIMIT || type == ChallengeType.CATEGORY_SPECIFIC) {
            require(targetAmount != null && targetAmount > 0.0) {
                "$type challenges require a positive targetAmount"
            }
        }

        val now = timeProvider.now()
        val baseline = buildBaseline(type = type, durationDays = durationDays, categoryId = categoryId, now = now)
        spendingChallengeRepository.saveChallenge(
            SpendingChallenge(
                id = 0,
                name = name,
                type = type,
                startDate = now,
                endDate = now + (durationDays.toLong() * DAY_MS),
                targetAmount = targetAmount,
                categoryId = categoryId,
                isActive = true,
                progress = 0.0,
                baselineAmount = baseline?.amount,
                baselineStartDate = baseline?.startDate,
                baselineEndDate = baseline?.endDate
            )
        )
    }

    /**
     * Get challenge progress.
     */
    suspend fun getChallengeProgress(challenge: SpendingChallenge): ChallengeProgress = withContext(ioDispatcher) {
        val now = timeProvider.now()
        val evaluationEnd = minOf(now, challenge.endDate)
        val spent = getSpentForChallengeRange(
            startDate = challenge.startDate,
            endDate = evaluationEnd,
            categoryId = challenge.categoryId
        )

        val elapsedProgress = calculateElapsedProgress(challenge = challenge, now = now)
        val daysRemaining = ((challenge.endDate - now) / DAY_MS).toInt().coerceAtLeast(0)

        val (isCompleted, isSuccessful) = when (challenge.type) {
            ChallengeType.NO_SPEND -> {
                val failed = spent > 0.0
                val completed = now >= challenge.endDate || failed
                completed to (completed && !failed && now >= challenge.endDate)
            }

            ChallengeType.BUDGET_LIMIT,
            ChallengeType.CATEGORY_SPECIFIC -> {
                val target = challenge.targetAmount
                val failed = target != null && spent > target
                val completed = now >= challenge.endDate || failed
                completed to (completed && now >= challenge.endDate && target != null && spent <= target)
            }

            ChallengeType.REDUCE_SPENDING -> {
                val allowedSpend = resolveReduceSpendingCap(challenge)
                val failed = allowedSpend != null && spent > allowedSpend
                val completed = now >= challenge.endDate || failed || allowedSpend == null
                completed to (completed && now >= challenge.endDate && allowedSpend != null && spent <= allowedSpend)
            }
        }

        ChallengeProgress(
            challenge = challenge,
            amountSpent = spent,
            progressPercent = if (isCompleted) 100.0 else elapsedProgress,
            daysRemaining = daysRemaining,
            isCompleted = isCompleted,
            isSuccessful = isSuccessful
        )
    }

    private suspend fun buildBaseline(
        type: ChallengeType,
        durationDays: Int,
        categoryId: Long?,
        now: Long
    ): ChallengeBaseline? {
        if (type != ChallengeType.REDUCE_SPENDING) return null

        val baselineEnd = getStartOfDay(now)
        val baselineStart = baselineEnd - durationDays.toLong() * DAY_MS
        val baselineAmount = getSpentForChallengeRange(
            startDate = baselineStart,
            endDate = baselineEnd,
            categoryId = categoryId
        )

        return ChallengeBaseline(
            amount = baselineAmount,
            startDate = baselineStart,
            endDate = baselineEnd
        )
    }

    private fun resolveReduceSpendingCap(challenge: SpendingChallenge): Double? {
        val baselineAmount = challenge.baselineAmount ?: return null
        val reductionAmount = challenge.targetAmount ?: 0.0
        return (baselineAmount - reductionAmount).coerceAtLeast(0.0)
    }

    private fun calculateElapsedProgress(challenge: SpendingChallenge, now: Long): Double {
        val duration = (challenge.endDate - challenge.startDate).coerceAtLeast(1L)
        val elapsed = (minOf(now, challenge.endDate) - challenge.startDate).coerceAtLeast(0L)
        return ((elapsed.toDouble() / duration.toDouble()) * 100.0).coerceIn(0.0, 100.0)
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

    private suspend fun getSpentForChallengeRange(
        startDate: Long,
        endDate: Long,
        categoryId: Long?
    ): Double {
        return if (categoryId == null) {
            expenseDao.getTotalSpentBetween(startDate, endDate) ?: 0.0
        } else {
            expenseDao.getCategorySpentInPeriod(categoryId, startDate, endDate)
        }
    }

    private suspend fun calculateAverageDailySpend(): Double = withContext(ioDispatcher) {
        val now = timeProvider.now()
        val thirtyDaysAgo = now - (30 * DAY_MS)
        val total = expenseDao.getTotalSpentBetween(thirtyDaysAgo, now) ?: 0.0
        total / 30.0
    }

    private fun findLastSpendDate(spendingDays: List<DailyTotal>): Long? {
        return spendingDays.lastOrNull { it.total > 0.0 }?.let { getStartOfDay(it.startDate) }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

private data class ChallengeBaseline(
    val amount: Double,
    val startDate: Long,
    val endDate: Long
)

data class ActiveChallengesSnapshot(
    val challenges: List<SpendingChallenge>,
    val unavailableReason: String? = null
)

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
    val progress: Double,
    val baselineAmount: Double? = null,
    val baselineStartDate: Long? = null,
    val baselineEndDate: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

enum class ChallengeType {
    NO_SPEND,
    BUDGET_LIMIT,
    REDUCE_SPENDING,
    CATEGORY_SPECIFIC
}

data class ChallengeProgress(
    val challenge: SpendingChallenge,
    val amountSpent: Double,
    val progressPercent: Double,
    val daysRemaining: Int,
    val isCompleted: Boolean,
    val isSuccessful: Boolean
)
