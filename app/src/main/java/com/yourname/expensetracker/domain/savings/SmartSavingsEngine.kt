package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SavingsRecommendation(
    val safeAmount: Double,
    val confidence: Double,
    val impact: String,
    val source: RecommendationSource
)

data class GoalSavingsRecommendation(
    val goal: SavingsGoal,
    val recommendation: SavingsRecommendation
)

enum class RecommendationSource {
    BUDGET_SURPLUS,
    SPENDING_PACE,
    MONTE_CARLO,
    INCOME_DEPOSIT,
    ROUND_UP
}

@Singleton
class SmartSavingsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val budgetCalculator: BudgetCalculator,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    // TODO: Inject RecurringLifecycleCoordinator for recurring-aware safe-to-save
    // calculations. The coordinator's generateOccurrences() should be used to
    // project recurring commitments when determining discretionary spending
    // capacity, replacing the current ad-hoc recurring pattern estimates.
) {
    companion object {
        private const val DAY_IN_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * Default weekly safe-save cap, denominated in the user's home currency.
         *
         * These caps represent conservative upper bounds on how much the engine
         * will recommend saving in a single week/month/quarter. They prevent
         * overly aggressive savings recommendations. Values are in home-currency
         * units (e.g., EUR, USD) and should be tuned per-market. Future iterations
         * may expose these as user-editable settings via [CurrencySettingsRepository].
         */
        private const val DEFAULT_CAP_WEEK = 75.0

        /** @see DEFAULT_CAP_WEEK */
        private const val DEFAULT_CAP_MONTH = 200.0

        /** @see DEFAULT_CAP_WEEK */
        private const val DEFAULT_CAP_QUARTER = 500.0

        /**
         * Fallback monthly discretionary spending used when historical data is
         * insufficient to compute a user-specific baseline. Denominated in home
         * currency.
         */
        private const val DEFAULT_FALLBACK_MONTHLY_DISCRETIONARY = 500.0
    }

    suspend fun calculateSafeToSaveAmount(
        goal: SavingsGoal,
        timeHorizon: TimeHorizon = TimeHorizon.MONTH,
        homeCurrency: String = "EUR"
    ): SavingsRecommendation {
        return calculatePortfolioRecommendations(listOf(goal), timeHorizon, homeCurrency)
            .firstOrNull()
            ?.recommendation
            ?: SavingsRecommendation(
                safeAmount = 0.0,
                confidence = 0.40,
                impact = generateImpactMessage(0.0, goal, timeHorizon, homeCurrency),
                source = RecommendationSource.BUDGET_SURPLUS
            )
    }

    suspend fun calculatePortfolioRecommendations(
        goals: List<SavingsGoal>,
        timeHorizon: TimeHorizon = TimeHorizon.MONTH,
        homeCurrency: String = "EUR"
    ): List<GoalSavingsRecommendation> {
        val incompleteGoals = goals.filter { goal ->
            (goal.targetAmount - goal.currentAmount) > 0.0
        }

        if (incompleteGoals.isEmpty()) {
            return emptyList()
        }

        val portfolioRecommendation = calculatePortfolioSafeToSaveAmount(
            sampleGoal = incompleteGoals.first(),
            timeHorizon = timeHorizon,
            homeCurrency = homeCurrency
        )
        val allocations = allocateAcrossGoals(incompleteGoals, portfolioRecommendation.safeAmount)

        return incompleteGoals.mapIndexed { index, goal ->
            val allocatedAmount = allocations.getOrElse(index) { 0.0 }
            GoalSavingsRecommendation(
                goal = goal,
                recommendation = portfolioRecommendation.copy(
                    safeAmount = allocatedAmount,
                    impact = generateImpactMessage(allocatedAmount, goal, timeHorizon, homeCurrency)
                )
            )
        }
    }
    
    private fun calculateBudgetSurplus(budgetStatuses: List<BudgetStatus>): Double {
        val scopedStatuses = budgetStatuses
            .let { allStatuses ->
                val overallStatuses = allStatuses.filter { it.budget.categoryId == null }
                if (overallStatuses.isNotEmpty()) overallStatuses else allStatuses
            }
            .filter { it.remainingAmount > 0.0 }

        var totalSurplus = 0.0
        for (status in scopedStatuses) {
            // Only count 50% of surplus to be conservative
            totalSurplus += status.remainingAmount * 0.5
        }
        return totalSurplus
    }

    private suspend fun calculatePortfolioSafeToSaveAmount(
        sampleGoal: SavingsGoal,
        timeHorizon: TimeHorizon,
        homeCurrency: String
    ): SavingsRecommendation {
        val now = timeProvider.now()
        val budgetStatuses = budgetRepository.getBudgetStatuses().first()

        val budgetSurplus = calculateBudgetSurplus(budgetStatuses)
        val spendingPace = analyzeSpendingPace(timeHorizon, homeCurrency)
        val monteCarloResult = runMonteCarloSimulation(sampleGoal, now, timeHorizon, homeCurrency)
        val safeAmount = calculateWeightedSafeAmount(
            budgetSurplus = budgetSurplus,
            spendingPace = spendingPace,
            monteCarloResult = monteCarloResult,
            timeHorizon = timeHorizon
        )

        return SavingsRecommendation(
            safeAmount = safeAmount,
            confidence = calculateConfidence(budgetSurplus, spendingPace, monteCarloResult),
            impact = UiText.fromKey(DomainTextKeys.SAVINGS_PORTFOLIO_RECOMMENDATION_READY).asFallbackString(),
            source = determinePrimarySource(budgetSurplus, spendingPace, monteCarloResult)
        )
    }

    private fun allocateAcrossGoals(
        goals: List<SavingsGoal>,
        totalSafeAmount: Double
    ): List<Double> {
        if (goals.isEmpty() || totalSafeAmount <= 0.0) {
            return List(goals.size) { 0.0 }
        }

        val allocations = MutableList(goals.size) { 0.0 }
        val remainingGaps = goals
            .map { (it.targetAmount - it.currentAmount).coerceAtLeast(0.0) }
            .toMutableList()
        val weights = goals.map { calculateAllocationWeight(it) }
        var remainingSafeAmount = totalSafeAmount
        val activeIndexes = goals.indices.toMutableSet()

        while (remainingSafeAmount > 0.01 && activeIndexes.isNotEmpty()) {
            val totalWeight = activeIndexes.sumOf { index -> weights[index] }
            if (totalWeight <= 0.0) {
                break
            }

            var allocatedThisRound = 0.0
            val filledIndexes = mutableSetOf<Int>()
            for (index in activeIndexes) {
                val proposedAmount = remainingSafeAmount * (weights[index] / totalWeight)
                val cappedAmount = minOf(proposedAmount, remainingGaps[index])
                if (cappedAmount > 0.0) {
                    allocations[index] += cappedAmount
                    remainingGaps[index] -= cappedAmount
                    allocatedThisRound += cappedAmount
                }
                if (remainingGaps[index] <= 0.01) {
                    filledIndexes += index
                }
            }

            if (allocatedThisRound <= 0.0) {
                break
            }

            remainingSafeAmount -= allocatedThisRound
            activeIndexes.removeAll(filledIndexes)
        }

        return allocations.mapIndexed { index, amount ->
            amount.coerceAtMost((goals[index].targetAmount - goals[index].currentAmount).coerceAtLeast(0.0))
        }
    }

    private fun calculateAllocationWeight(goal: SavingsGoal): Double {
        val remainingGap = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
        if (remainingGap <= 0.0) {
            return 0.0
        }

        val urgencyMultiplier = goal.targetDate?.let { targetDate ->
            val millisUntilTarget = targetDate - timeProvider.now()
            val daysUntilTarget = millisUntilTarget / (24.0 * 60 * 60 * 1000)
            when {
                daysUntilTarget <= 30.0 -> 2.0
                daysUntilTarget <= 90.0 -> 1.5
                daysUntilTarget <= 180.0 -> 1.25
                else -> 1.0
            }
        } ?: 1.0

        return remainingGap * urgencyMultiplier
    }
    
    private suspend fun analyzeSpendingPace(timeHorizon: TimeHorizon, homeCurrency: String): Double {
        val now = timeProvider.now()
        val dayOfMonth = TimePeriodUtils.getDayOfMonth(now).coerceAtLeast(1)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
        
        // Get current month's spending (normalized to home currency)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        
        val rawExpenses = expenseRepository.getExpenseSnapshotsBetween(monthStart, now)
        val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawExpenses, homeCurrency)
        val expenses = normalized.includedExpenses
        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 231
        val totalSpent = expenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
            .sumOf { it.effectiveAmount }
        
        // Calculate days elapsed and month length
        
        // If spending slower than average pace, suggest saving the difference
        val averageDailySpending = totalSpent / dayOfMonth
        val projectedMonthTotal = averageDailySpending * daysInMonth
        
        // Get typical monthly spending from history
        val historyDays = when (timeHorizon) {
            TimeHorizon.WEEK -> 28L
            TimeHorizon.MONTH -> 90L
            TimeHorizon.QUARTER -> 365L
        }
        val horizonMonths = when (timeHorizon) {
            TimeHorizon.WEEK -> 0.25
            TimeHorizon.MONTH -> 1.0
            TimeHorizon.QUARTER -> 3.0
        }

        val historyStart = now - (historyDays * 24 * 60 * 60 * 1000)
        val rawHistoryExpenses = expenseRepository.getExpenseSnapshotsBetween(historyStart, now)
        val historyNormalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawHistoryExpenses, homeCurrency)
        val historyExpenses = historyNormalized.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }

        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 257
        val totalHistorySpending = historyExpenses.sumOf { it.effectiveAmount }
        val monthlyBaselineSpending = if (historyDays > 0) {
            totalHistorySpending / historyDays.toDouble() * 30.0
        } else {
            0.0
        }
        val horizonBaselineSpending = monthlyBaselineSpending * horizonMonths
        val projectedHorizonTotal = projectedMonthTotal * horizonMonths
        
        // If projected spending is less than average, we can save the difference
        return if (projectedHorizonTotal < horizonBaselineSpending) {
            (horizonBaselineSpending - projectedHorizonTotal) * 0.3 // Conservative 30%
        } else {
            0.0
        }
    }
    
    private suspend fun runMonteCarloSimulation(
        _goal: SavingsGoal,
        now: Long,
        timeHorizon: TimeHorizon,
        homeCurrency: String
    ): Double {
        val simulationConfig = getSimulationConfig(timeHorizon)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val rawMonthExpenses = expenseRepository.getExpenseSnapshotsBetween(monthStart, now)
        val monthNormalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawMonthExpenses, homeCurrency)
        val monthPurchases = monthNormalized.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 287
        val monthSpentToDate = monthPurchases.sumOf { it.effectiveAmount }

        val lookbackStart = now - (simulationConfig.lookbackDays * DAY_IN_MILLIS)
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        val rawHistoricalPurchases = expenseRepository.getExpenseSnapshotsBetween(lookbackStart, now)
        val historicalNormalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawHistoricalPurchases, homeCurrency)
        val historicalPurchases = historicalNormalized.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 295
        val discretionaryHistoricalTotal = historicalPurchases
            .filter {
                isDiscretionaryCategory(it.categoryId, categoriesById)
            }
            .sumOf { it.effectiveAmount }
        val endOfLastCompleteMonth = TimePeriodUtils.getStartOfMonth(now) - 1L
        val lookbackMonthCount = countCalendarMonthsInRange(
            startInclusive = lookbackStart,
            endInclusive = endOfLastCompleteMonth
        )
        val monthlyDiscretionary = if (lookbackMonthCount > 0) {
            discretionaryHistoricalTotal / lookbackMonthCount.toDouble()
        } else {
            simulationConfig.fallbackMonthlyDiscretionary
        }

        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 295
        val averageHistoricalDailySpending = if (historicalPurchases.isNotEmpty()) {
            historicalPurchases.sumOf { it.effectiveAmount } / simulationConfig.lookbackDays.toDouble()
        } else {
            0.0
        }

        val knownUpcoming = 0.0
        val monthlyResult = monteCarloSimulator.simulate(
            spentToDate = monthSpentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = null // No budget constraint
        )

        val projectedHorizonSpending = when (timeHorizon) {
            TimeHorizon.WEEK -> simulateWeeklyProjectedSpending(now, averageHistoricalDailySpending, homeCurrency)
            TimeHorizon.MONTH -> monthlyResult?.percentile50
                ?: simulateProjectedSpendingForRange(
                    periodStart = monthStart,
                    periodEnd = TimePeriodUtils.getEndOfMonth(now),
                    now = now,
                    averageHistoricalDailySpending = averageHistoricalDailySpending,
                    homeCurrency = homeCurrency
                )
            TimeHorizon.QUARTER -> simulateProjectedSpendingForRange(
                periodStart = TimePeriodUtils.getStartOfQuarter(now),
                periodEnd = TimePeriodUtils.getEndOfQuarter(now),
                now = now,
                averageHistoricalDailySpending = averageHistoricalDailySpending,
                homeCurrency = homeCurrency
            )
        }

        val horizonDiscretionary = when (timeHorizon) {
            TimeHorizon.WEEK -> monthlyDiscretionary / TimePeriodUtils.getDaysInMonth(now) * 7.0
            TimeHorizon.MONTH -> monthlyDiscretionary
            TimeHorizon.QUARTER -> monthlyDiscretionary * countCalendarMonthsInRange(
                TimePeriodUtils.getStartOfQuarter(now),
                TimePeriodUtils.getEndOfQuarter(now) - 1
            )
        }

        val remaining = horizonDiscretionary -
            (projectedHorizonSpending * simulationConfig.projectedSpendingRiskBufferRatio)

        return if (remaining > 0.0) remaining * simulationConfig.safeToSaveRatio else 0.0
    }
    
    private fun calculateWeightedSafeAmount(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double,
        timeHorizon: TimeHorizon
    ): Double {
        // Weighted combination adjusted by time horizon
        // Caps are in home currency (see DEFAULT_CAP_WEEK / _MONTH / _QUARTER)
        val (budgetWeight, paceWeight, monteCarloWeight, cap) = when (timeHorizon) {
            TimeHorizon.WEEK -> HorizonWeights(0.30, 0.45, 0.25, DEFAULT_CAP_WEEK)
            TimeHorizon.MONTH -> HorizonWeights(0.40, 0.30, 0.30, DEFAULT_CAP_MONTH)
            TimeHorizon.QUARTER -> HorizonWeights(0.35, 0.20, 0.45, DEFAULT_CAP_QUARTER)
        }

        val weighted =
            budgetSurplus * budgetWeight +
            spendingPace * paceWeight +
            monteCarloResult * monteCarloWeight
        
        return minOf(weighted, cap).coerceAtLeast(0.0)
    }
    
    private fun calculateConfidence(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double
    ): Double {
        // Higher confidence if multiple sources agree
        val sources = listOf(budgetSurplus, spendingPace, monteCarloResult).count { it > 10.0 }
        return when (sources) {
            3 -> 0.95
            2 -> 0.80
            1 -> 0.60
            else -> 0.40
        }
    }
    
    private fun generateImpactMessage(
        amount: Double,
        goal: SavingsGoal,
        timeHorizon: TimeHorizon,
        homeCurrency: String = "EUR"
    ): String {
        val remaining = goal.targetAmount - goal.currentAmount
        if (remaining <= 0) {
            return UiText.fromKey(DomainTextKeys.SAVINGS_IMPACT_GOAL_ALREADY_REACHED).asFallbackString()
        }

        val horizonDays = when (timeHorizon) {
            TimeHorizon.WEEK -> 7.0
            TimeHorizon.MONTH -> 30.0
            TimeHorizon.QUARTER -> 90.0
        }
        val daysToGoal = if (amount > 0) {
            kotlin.math.ceil((remaining / amount) * horizonDays).toInt()
        } else {
            Int.MAX_VALUE
        }
        
        return when {
            daysToGoal <= 30 -> UiText.fromKey(
                DomainTextKeys.SAVINGS_IMPACT_REACH_IN_DAYS_FORMAT,
                daysToGoal
            ).asFallbackString()

            daysToGoal <= 90 -> UiText.fromKey(
                DomainTextKeys.SAVINGS_IMPACT_ON_TRACK_MONTHS_FORMAT,
                daysToGoal / 30
            ).asFallbackString()

            else -> UiText.fromKey(
                DomainTextKeys.SAVINGS_IMPACT_STEADY_PROGRESS_FORMAT,
                UiTextArg.Money(goal.targetAmount, currency = homeCurrency, currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)
            ).asFallbackString()
        }
    }

    private fun UiText.asFallbackString(): String {
        return when (this) {
            is UiText.DynamicString -> value
            is UiText.MessageKey -> if (args.isEmpty()) key else "$key ${args.joinToString(", ")}"
            is UiText.PluralResource -> "plural:$resId($quantity)"
            is UiText.StringResource -> "res:$resId"
        }
    }
    
    private fun determinePrimarySource(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double
    ): RecommendationSource {
        return when {
            budgetSurplus > spendingPace && budgetSurplus > monteCarloResult -> RecommendationSource.BUDGET_SURPLUS
            spendingPace > budgetSurplus && spendingPace > monteCarloResult -> RecommendationSource.SPENDING_PACE
            else -> RecommendationSource.MONTE_CARLO
        }
    }

    private fun isDiscretionaryCategory(
        categoryId: Long?,
        categoriesById: Map<Long, Category>
    ): Boolean {
        val essentialCategories = setOf(
            "groceries", "rent", "utilities", "transport",
            "insurance", "healthcare", "bills", "mortgage", "education", "loan"
        )

        val categoryName = categoryId
            ?.let { categoriesById[it]?.name }
            ?.trim()
            ?.lowercase()

        // Treat uncategorized entries as discretionary by default.
        return categoryName == null || categoryName !in essentialCategories
    }

    private data class HorizonWeights(
        val budgetWeight: Double,
        val paceWeight: Double,
        val monteCarloWeight: Double,
        val cap: Double
    )

    private data class HorizonSimulationConfig(
        val lookbackDays: Long,
        val projectedSpendingRiskBufferRatio: Double,
        val safeToSaveRatio: Double,
        val fallbackMonthlyDiscretionary: Double
    )

    private fun getSimulationConfig(timeHorizon: TimeHorizon): HorizonSimulationConfig {
        return when (timeHorizon) {
            TimeHorizon.WEEK -> HorizonSimulationConfig(
                lookbackDays = 28L,
                projectedSpendingRiskBufferRatio = 0.30,
                safeToSaveRatio = 0.20,
                fallbackMonthlyDiscretionary = DEFAULT_FALLBACK_MONTHLY_DISCRETIONARY
            )
            TimeHorizon.MONTH -> HorizonSimulationConfig(
                lookbackDays = 90L,
                projectedSpendingRiskBufferRatio = 0.30,
                safeToSaveRatio = 0.20,
                fallbackMonthlyDiscretionary = DEFAULT_FALLBACK_MONTHLY_DISCRETIONARY
            )
            TimeHorizon.QUARTER -> HorizonSimulationConfig(
                lookbackDays = 365L,
                projectedSpendingRiskBufferRatio = 0.30,
                safeToSaveRatio = 0.20,
                fallbackMonthlyDiscretionary = DEFAULT_FALLBACK_MONTHLY_DISCRETIONARY
            )
        }
    }

    private suspend fun simulateWeeklyProjectedSpending(
        now: Long,
        averageHistoricalDailySpending: Double,
        homeCurrency: String
    ): Double {
        return simulateProjectedSpendingForRange(
            periodStart = TimePeriodUtils.getStartOfWeek(now),
            periodEnd = TimePeriodUtils.getEndOfWeek(now),
            now = now,
            averageHistoricalDailySpending = averageHistoricalDailySpending,
            homeCurrency = homeCurrency
        )
    }

    private suspend fun simulateProjectedSpendingForRange(
        periodStart: Long,
        periodEnd: Long,
        now: Long,
        averageHistoricalDailySpending: Double,
        homeCurrency: String
    ): Double {
        val rawPurchases = expenseRepository.getExpenseSnapshotsBetween(periodStart, now)
        val purchasesNormalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawPurchases, homeCurrency)
        val purchases = purchasesNormalized.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 536
        val spentToDate = purchases.sumOf { it.effectiveAmount }
        val remainingDays = ((periodEnd - now).coerceAtLeast(0L)).toDouble() / DAY_IN_MILLIS.toDouble()
        return spentToDate + (averageHistoricalDailySpending * remainingDays)
    }

    private fun countCalendarMonthsInRange(startInclusive: Long, endInclusive: Long): Int {
        if (endInclusive < startInclusive) return 0

        val startYear = TimePeriodUtils.getYear(startInclusive)
        val startMonth = TimePeriodUtils.getMonth(startInclusive)
        val endYear = TimePeriodUtils.getYear(endInclusive)
        val endMonth = TimePeriodUtils.getMonth(endInclusive)

        return ((endYear - startYear) * 12 + (endMonth - startMonth) + 1).coerceAtLeast(1)
    }

    enum class TimeHorizon {
        WEEK, MONTH, QUARTER
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
