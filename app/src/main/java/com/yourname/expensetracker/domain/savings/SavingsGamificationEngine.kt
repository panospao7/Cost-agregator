package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.repository.SavingsContributionEvent
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryUnavailableException
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.BucketDatePolicy
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateMetadata
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyRow
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyQuality
import com.yourname.expensetracker.domain.core.money.MoneyBucketInput
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
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
    val totalContributedThisMonth: Double?,
    val totalContributedThisMonthResult: MoneyAggregateResult? = null,
    val isAvailable: Boolean = true
)

data class SavingsAchievement(
    val id: String,
    val title: UiText,
    val description: UiText,
    val icon: String, // Emoji or icon name
    val isUnlocked: Boolean,
    val unlockedAt: Long?,
    val progress: Double, // 0.0 to 1.0
    val requirement: UiText,
    val isAvailable: Boolean = true
)

@Singleton
class SavingsGamificationEngine @Inject constructor(
    private val savingsGoalRepository: SavingsGoalRepository,
    private val contributionHistoryRepository: SavingsContributionHistoryRepository,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter
) {
    companion object {
        // Achievement thresholds in home currency
        private const val CENTURY_SAVER_THRESHOLD = 100.0
        private const val GRAND_SAVER_THRESHOLD = 1000.0
        private const val LEVEL_UP_EVERY = 500.0
    }
    suspend fun calculateStreak(userId: String = "default"): SavingsStreak {
        val contributionMetrics = analyzeContributionHistory()

        return SavingsStreak(
            currentStreakDays = contributionMetrics.currentStreakDays,
            personalBestDays = contributionMetrics.personalBestDays,
            lastSavingsDate = contributionMetrics.lastSavingsDate,
            monthlyContributions = contributionMetrics.monthlyContributions,
            totalContributedThisMonth = contributionMetrics.totalContributedThisMonth,
            totalContributedThisMonthResult = contributionMetrics.totalContributedThisMonthResult,
            isAvailable = contributionMetrics.historyAvailable
        )
    }
    
    suspend fun getAchievements(userId: String = "default", homeCurrency: String): List<SavingsAchievement> {
        val goals = savingsGoalRepository.observeSavingsGoals().first()
        val contributionMetrics = analyzeContributionHistory(homeCurrency, goals)
        val sortedGoals = goals.sortedBy { it.createdAt }
        val firstGoalUnlockedAt = sortedGoals.firstOrNull()?.createdAt
        val totalSavedAggregate = aggregateGoalBalances(goals, homeCurrency)
        val totalSaved = totalSavedAggregate.displayAmountOrNull()
            .takeIf { contributionMetrics.historyAvailable }
        val totalSavedThresholds = resolveTotalSavedThresholdUnlocks(
            goals = goals,
            normalizedContributions = contributionMetrics.normalizedContributions,
            totalSaved = totalSaved
        )
        val bestProgressGoal = goals.maxByOrNull {
            it.currentAmount / it.targetAmount.coerceAtLeast(0.01)
        }
        val goalCrusherUnlockedAt = resolveGoalCrusherUnlockedAt(
            goals = goals,
            normalizedContributions = contributionMetrics.normalizedContributions
        )

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
                description = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_FIRST_GOAL),
                icon = "🎯",
                isUnlocked = goalCount >= 1,
                unlockedAt = firstGoalUnlockedAt,
                progress = if (goalCount >= 1) 1.0 else 0.0,
                requirement = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_CREATE_GOAL)
            ),
            SavingsAchievement(
                id = "saving_streak_7",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_WEEK_WARRIOR),
                description = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_STREAK),
                icon = "🔥",
                isUnlocked = contributionMetrics.historyAvailable && contributionMetrics.sevenDayStreakUnlockedAt != null,
                unlockedAt = contributionMetrics.sevenDayStreakUnlockedAt,
                progress = if (!contributionMetrics.historyAvailable) {
                    0.0
                } else if (contributionMetrics.sevenDayStreakUnlockedAt != null) {
                    1.0
                } else {
                    (contributionMetrics.currentStreakDays / 7.0).coerceIn(0.0, 1.0)
                },
                isAvailable = contributionMetrics.historyAvailable,
                requirement = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_STREAK)
            ),
            SavingsAchievement(
                id = "century_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_CENTURY_CLUB),
                description = UiText.fromKey(
                    DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_SAVE_TOTAL_FORMAT,
                    UiTextArg.Money(CENTURY_SAVER_THRESHOLD, currency = homeCurrency, currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)
                ),
                icon = "💯",
                isUnlocked = totalSaved != null && totalSaved >= CENTURY_SAVER_THRESHOLD,
                unlockedAt = if (totalSaved != null && totalSaved >= CENTURY_SAVER_THRESHOLD) totalSavedThresholds[CENTURY_SAVER_THRESHOLD] else null,
                progress = totalSaved?.let { (it / CENTURY_SAVER_THRESHOLD).coerceIn(0.0, 1.0) } ?: 0.0,
                isAvailable = totalSaved != null,
                requirement = UiText.fromKey(
                    DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_SAVED_FORMAT,
                    UiTextArg.Money(CENTURY_SAVER_THRESHOLD, currency = homeCurrency, currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)
                )
            ),
            SavingsAchievement(
                id = "goal_crusher",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GOAL_CRUSHER),
                description = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_COMPLETE_FIRST_GOAL),
                icon = "🏆",
                isUnlocked = totalSaved != null && completedGoals >= 1,
                unlockedAt = if (totalSaved != null && completedGoals >= 1) goalCrusherUnlockedAt else null,
                isAvailable = totalSaved != null,
                progress = if (totalSaved == null) 0.0 else if (completedGoals >= 1) 1.0 else
                    (bestProgressGoal?.let {
                        it.currentAmount / it.targetAmount.coerceAtLeast(0.01)
                    } ?: 0.0).coerceIn(0.0, 1.0),
                requirement = UiText.fromKey(DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_COMPLETE_GOAL)
            ),
            SavingsAchievement(
                id = "thousand_saver",
                title = UiText.fromKey(DomainTextKeys.SAVINGS_GRAND_SAVER),
                description = UiText.fromKey(
                    DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_SAVE_TOTAL_FORMAT,
                    UiTextArg.Money(GRAND_SAVER_THRESHOLD, currency = homeCurrency, currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)
                ),
                icon = "💰",
                isUnlocked = totalSaved != null && totalSaved >= GRAND_SAVER_THRESHOLD,
                unlockedAt = if (totalSaved != null && totalSaved >= GRAND_SAVER_THRESHOLD) totalSavedThresholds[GRAND_SAVER_THRESHOLD] else null,
                progress = totalSaved?.let { (it / GRAND_SAVER_THRESHOLD).coerceIn(0.0, 1.0) } ?: 0.0,
                isAvailable = totalSaved != null,
                requirement = UiText.fromKey(
                    DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_SAVED_FORMAT,
                    UiTextArg.Money(GRAND_SAVER_THRESHOLD, currency = homeCurrency, currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)
                )
            )
        )
    }
    
    fun calculateLevel(totalSaved: Double): Int {
        // Level up every LEVEL_UP_EVERY in home currency
        return (totalSaved / LEVEL_UP_EVERY).toInt() + 1
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

    private suspend fun analyzeContributionHistory(
        computationCurrency: String? = null,
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal> = emptyList()
    ): ContributionMetrics {
        val now = timeProvider.now()
        val homeCurrency = computationCurrency ?: currencySettingsRepository.homeCurrency().first()
        val contributions = try {
            contributionHistoryRepository.getAllContributions().sortedBy { it.timestamp }
        } catch (error: SavingsContributionHistoryUnavailableException) {
            return ContributionMetrics(
                totalContributedThisMonth = null,
                totalContributedThisMonthResult = MoneyAggregateResult.Unavailable(
                    reason = "SAVINGS_HISTORY_CORRUPT",
                    requestedRateBasis = RateBasis.TRANSACTION_DATE,
                    metadata = MoneyAggregateMetadata()
                ),
                historyAvailable = false
            )
        }
        val normalizedContributions = normalizeContributions(contributions, homeCurrency, goals)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
        val currentMonthContributions = normalizedContributions.filter {
            it.event.timestamp >= monthStart && it.event.timestamp < monthEnd
        }
        val contributionMoney = aggregateNormalizedContributions(currentMonthContributions, homeCurrency)
        if (contributions.isEmpty()) {
            return ContributionMetrics(
                totalContributedThisMonth = contributionMoney.displayAmountOrNull(),
                totalContributedThisMonthResult = contributionMoney,
                normalizedContributions = normalizedContributions
            )
        }

        val sortedContributions = contributions
        val contributionDays = sortedContributions
            .map { TimePeriodUtils.getStartOfDay(it.timestamp) }
            .distinct()
            .sorted()

        return ContributionMetrics(
            currentStreakDays = calculateCurrentStreakDays(contributionDays, now),
            personalBestDays = calculatePersonalBestDays(contributionDays),
            lastSavingsDate = sortedContributions.lastOrNull()?.timestamp,
            monthlyContributions = currentMonthContributions.size,
            totalContributedThisMonth = contributionMoney.displayAmountOrNull(),
            totalContributedThisMonthResult = contributionMoney,
            sevenDayStreakUnlockedAt = findStreakUnlockedAt(sortedContributions, 7),
            contributions = sortedContributions,
            normalizedContributions = normalizedContributions
        )
    }

    private data class NormalizedContribution(
        val event: SavingsContributionEvent,
        val homeRow: NormalizedMoneyRow?,
        val goalAmount: MoneyAmount? = null,
        val unavailableReason: String? = null
    )

    private suspend fun normalizeContributions(
        contributions: List<SavingsContributionEvent>,
        homeCurrencyCode: String,
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>
    ): List<NormalizedContribution> {
        val homeCurrency = CurrencyCode.parse(homeCurrencyCode)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return contributions.map {
                NormalizedContribution(it, null, unavailableReason = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name)
            }
        val goalCurrencies = goals.filter { it.currentAmount >= it.targetAmount }.associate { goal ->
            goal.id to SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal)
        }
        return contributions.map { contribution ->
            val sourceCurrency = CurrencyCode.parse(contribution.currency)
                ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            val normalized = when {
                sourceCurrency == null -> NormalizedContribution(
                    contribution, null, unavailableReason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name
                )
                sourceCurrency == homeCurrency -> NormalizedContribution(
                    contribution, NormalizedMoneyRow(contribution.amount, homeCurrency, sourceCurrency)
                )
                else -> when (val outcome = currencyConverter.convertOutcome(
                    amount = contribution.amount,
                    fromCurrency = sourceCurrency.code,
                    toCurrency = homeCurrency.code,
                    rateBasis = RateBasis.TRANSACTION_DATE,
                    atMillis = contribution.timestamp,
                    stalePolicy = StaleRatePolicy.forBasis(RateBasis.TRANSACTION_DATE)
                )) {
                    is ConversionOutcome.Converted -> NormalizedContribution(
                        contribution, NormalizedMoneyRow(
                            outcome.convertedAmount, homeCurrency, sourceCurrency, rateValidDate = outcome.rateValidDate
                        )
                    )
                    is ConversionOutcome.Failed -> NormalizedContribution(
                        contribution, null, unavailableReason = outcome.failureType.name
                    )
                }
            }
            // Compare historical contributions with the target in goal units.
            // Reuse identity/home conversions rather than valuing the target at latest FX.
            val goalCurrency = goalCurrencies[contribution.goalId]
            val goalAmount = when {
                sourceCurrency == null || goalCurrency == null -> null
                goalCurrency == sourceCurrency -> MoneyAmount(contribution.amount, goalCurrency)
                goalCurrency == homeCurrency -> normalized.homeRow?.let { MoneyAmount(it.amount, goalCurrency) }
                else -> when (val outcome = currencyConverter.convertOutcome(
                    amount = contribution.amount, fromCurrency = sourceCurrency.code, toCurrency = goalCurrency.code,
                    rateBasis = RateBasis.TRANSACTION_DATE, atMillis = contribution.timestamp,
                    stalePolicy = StaleRatePolicy.forBasis(RateBasis.TRANSACTION_DATE)
                )) {
                    is ConversionOutcome.Converted -> MoneyAmount(outcome.convertedAmount, goalCurrency)
                    is ConversionOutcome.Failed -> null
                }
            }
            normalized.copy(goalAmount = goalAmount)
        }
    }

    private fun aggregateNormalizedContributions(
        contributions: List<NormalizedContribution>,
        homeCurrencyCode: String
    ): MoneyAggregateResult {
        val homeCurrency = CurrencyCode.parse(homeCurrencyCode)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis = RateBasis.TRANSACTION_DATE,
                metadata = MoneyAggregateMetadata(excludedTransactionCount = contributions.size)
            )
        val failed = contributions.count { it.homeRow == null }
        val rows = contributions.mapNotNull { it.homeRow }
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows = rows, targetCurrency = homeCurrency,
            quality = NormalizedMoneyQuality(
                excludedTransactionCount = failed,
                missingRateCount = contributions.count { it.unavailableReason == "MISSING_RATE" || it.unavailableReason == "MISSING_HISTORICAL_RATE" },
                staleRateCount = contributions.count { it.unavailableReason == "STALE_RATE" },
                invalidCurrencyCount = contributions.count { it.unavailableReason == "INVALID_SOURCE_CURRENCY" }
            ),
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = if (rows.all { it.sourceCurrency == homeCurrency }) RateBasis.IDENTITY else RateBasis.TRANSACTION_DATE
        )
        if (failed > 0) {
            return MoneyAggregateResult.Unavailable(
                reason = contributions.firstNotNullOfOrNull { it.unavailableReason }
                    ?: MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                requestedRateBasis = RateBasis.TRANSACTION_DATE,
                metadata = when (result) {
                    is MoneyAggregateResult.Available -> result.aggregate.metadata
                    is MoneyAggregateResult.Unavailable -> result.metadata
                }
            )
        }
        return result
    }

    suspend fun aggregateGoalBalances(
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>,
        homeCurrencyCode: String
    ): MoneyAggregateResult {
        val homeCurrency = CurrencyCode.parse(homeCurrencyCode)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis = RateBasis.LATEST_AVAILABLE,
                metadata = MoneyAggregateMetadata(excludedTransactionCount = goals.size)
            )
        if (goals.isEmpty()) {
            return MoneyAggregateResult.Available(MoneyAggregate.empty(homeCurrency, RateBasis.LATEST_AVAILABLE))
        }

        val buckets = goals.map { goal ->
            val sourceCurrency = SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal)
                ?: return MoneyAggregateResult.Unavailable(
                    reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                    requestedRateBasis = RateBasis.LATEST_AVAILABLE,
                    metadata = MoneyAggregateMetadata(
                        excludedTransactionCount = goals.size,
                        invalidCurrencyCount = 1
                    )
                )
            MoneyBucketInput(
                amount = goal.currentAmount,
                currency = sourceCurrency,
                transactionCount = 1
            )
        }
        val aggregate = MoneyAggregateBuilder.fromBuckets(
            buckets = buckets,
            homeCurrency = homeCurrency,
            converter = currencyConverter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )
        return if (aggregate.conversionQuality == ConversionQuality.UNAVAILABLE) {
            MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                requestedRateBasis = RateBasis.LATEST_AVAILABLE,
                metadata = aggregate.metadata,
                warningMessage = aggregate.warningMessage
                    ?: MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name
            )
        } else {
            MoneyAggregateResult.Available(aggregate)
        }
    }

    private fun MoneyAggregateResult.displayAmountOrNull(): Double? =
        (this as? MoneyAggregateResult.Available)
            ?.aggregate
            ?.takeIf { it.conversionQuality == ConversionQuality.COMPLETE }
            ?.displayAmount

    private suspend fun resolveTotalSavedThresholdUnlocks(
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>,
        normalizedContributions: List<NormalizedContribution>,
        totalSaved: Double?
    ): Map<Double, Long?> {
        val thresholds = listOf(CENTURY_SAVER_THRESHOLD, GRAND_SAVER_THRESHOLD)
        val unlocks = thresholds.associateWith { null as Long? }.toMutableMap()
        var cumulativeSaved = 0.0
        for (normalized in normalizedContributions) {
            val amount = normalized.homeRow?.amount ?: break
            cumulativeSaved += amount
            for (threshold in thresholds) {
                if (unlocks[threshold] == null && cumulativeSaved >= threshold) {
                    unlocks[threshold] = normalized.event.timestamp
                }
            }
            if (unlocks.values.all { it != null }) break
        }

        val fallbackTimestamp = goals.minOfOrNull { it.createdAt }
        for (threshold in thresholds) {
            if (normalizedContributions.isEmpty() && unlocks[threshold] == null && totalSaved != null && totalSaved >= threshold) {
                unlocks[threshold] = fallbackTimestamp
            }
        }
        return unlocks
    }

    private suspend fun resolveGoalCrusherUnlockedAt(
        goals: List<com.yourname.expensetracker.domain.model.SavingsGoal>,
        normalizedContributions: List<NormalizedContribution>
    ): Long? {
        val completedGoalIds = goals
            .filter { it.currentAmount >= it.targetAmount }
            .associateBy { it.id }

        if (completedGoalIds.isEmpty()) {
            return null
        }

        val totalsByGoal = mutableMapOf<Long, Double>()
        val unresolvedGoals = mutableSetOf<Long>()
        for (normalized in normalizedContributions) {
            val goalId = normalized.event.goalId
            val goal = completedGoalIds[goalId] ?: continue
            if (goalId in unresolvedGoals) continue
            val amount = normalized.goalAmount?.takeIf { it.currency == CurrencyCode.parse(goal.currency) }?.amount
            if (amount == null) {
                unresolvedGoals += goalId
                continue
            }
            val updatedAmount = (totalsByGoal[goalId] ?: 0.0) + amount
            totalsByGoal[goalId] = updatedAmount
            // Chronological events make the first valid crossing the earliest
            // across goals, independently of their creation/list order.
            if (updatedAmount >= goal.targetAmount) return normalized.event.timestamp
        }
        return null
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
        val totalContributedThisMonth: Double? = 0.0,
        val totalContributedThisMonthResult: MoneyAggregateResult? = null,
        val sevenDayStreakUnlockedAt: Long? = null,
        val contributions: List<SavingsContributionEvent> = emptyList(),
        val normalizedContributions: List<NormalizedContribution> = emptyList(),
        val historyAvailable: Boolean = true
    )
}
