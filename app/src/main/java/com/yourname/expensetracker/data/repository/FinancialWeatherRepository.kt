package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority as EntityPlannedPriority
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel as EntityGoalProtection
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.PlannedExpensePriority as DomainPlannedPriority
import com.yourname.expensetracker.domain.model.GoalProtectionLevel as DomainGoalProtection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import timber.log.Timber

enum class WeatherState {
    CLEAR_SKIES,      // 🌤️ Comfortable buffer
    PARTLY_CLOUDY,    // ⛅ Moderate, watch spending
    CLOUDY,           // ☁️ Tight, limited discretionary
    RAINY,            // 🌧️ Multiple bills, over pace
    STORMY,           // ⛈️ Budget danger, immediate action
    UNKNOWN
}

data class FinancialWeather(
    val state: WeatherState,
    val headline: String,
    val summary: String,
    val icon: String, // Emoji
    val riskLevel: Int, // 0-100
    val totalCommitted: Double,
    val totalLikely: Double,
    val predictedDiscretionary: Double,
    val discretionaryBudget: Double,
    val pastSpendingPoints: List<Double> = emptyList(),
    val projectedSpendingPoints: List<Double> = emptyList(),
    val upcomingItems: List<UpcomingItem> = emptyList(),
    val totalRecurringCount: Int = 0,
    val details: List<NarrativeSection> = emptyList()
)

@Singleton
@OptIn(kotlinx.coroutines.FlowPreview::class)
class FinancialWeatherRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val insightsEngine: InsightsEngine,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator,
    private val analyticsRepository: AnalyticsRepository,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {
    private fun com.yourname.expensetracker.data.database.entity.PlannedExpense.toDomain(): PlannedExpense {
        return PlannedExpense(
            id = this.id,
            description = this.description,
            amount = this.amount,
            date = this.date,
            categoryId = this.categoryId,
            isRecurring = this.isRecurring,
            priority = when(this.priority) {
                EntityPlannedPriority.MUST -> DomainPlannedPriority.MUST
                EntityPlannedPriority.LIKELY -> DomainPlannedPriority.LIKELY
                EntityPlannedPriority.OPTIONAL -> DomainPlannedPriority.OPTIONAL
            }
        )
    }

    fun getFinancialWeather(): Flow<FinancialWeather> = combine(
        expenseRepository.getAllExpenses().catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) },
        recurringExpenseRepository.getAllFlow().catch { emit(emptyList()) },
        plannedExpenseRepository.getAllPlannedExpenses().catch { emit(emptyList()) },
        savingsGoalRepository.getAllGoals().catch { emit(emptyList()) }
    ) { expenses, budgetStatuses, recurringEntities, plannedEntities, goalEntities ->
        
        val plannedExpenses = plannedEntities.map { it.toDomain() }
        
        // Use RecurringExpenseEngine to get patterns with ACTUAL confidence scores
        // This properly detects recurring expenses from transaction history with
        // confidence values based on detection consistency
        val recurringPatterns = recurringExpenseEngine.getPatterns(expenses)
        
        // Also include manual recurring expenses that may not have been detected
        // These have 100% confidence since they're user-defined
        val manualPatterns = recurringEntities.map { entity ->
            RecurringPattern(
                id = entity.id,
                merchantName = entity.merchant,
                averageAmount = entity.amount,
                currency = entity.currency,
                frequency = entity.frequency,
                nextExpectedDate = entity.nextDate,
                confidence = 1.0f, // Manual entries are 100% confident
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                previousDates = emptyList()
            )
        }
        
        // Merge detected patterns with manual patterns, removing duplicates by merchant
        // Manual patterns take precedence (higher confidence)
        val merchantToPattern = mutableMapOf<String, RecurringPattern>()
        (recurringPatterns + manualPatterns).forEach { pattern ->
            val key = pattern.merchantName.lowercase()
            val existing = merchantToPattern[key]
            if (existing == null || pattern.confidence > existing.confidence) {
                merchantToPattern[key] = pattern
            }
        }
        val allRecurringPatterns = merchantToPattern.values.toList()
        
        val savingsGoals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = when(entity.protectionLevel) {
                    EntityGoalProtection.STRICT -> DomainGoalProtection.STRICT
                    EntityGoalProtection.WARNING -> DomainGoalProtection.WARNING
                    EntityGoalProtection.TRACKING -> DomainGoalProtection.TRACKING
                }
            )
        }
        
        // 1. Calculate Past Daily Cumulative Spend
        // 1. Calculate Past Daily Cumulative Spend
        val now = timeProvider.now()
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val currentDay = ((now - monthStart) / 86400000L).toInt()

        val purchases = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE && 
            it.date >= monthStart &&
            !it.isNotMine
        }
        
        val amountByDay = DoubleArray(currentDay + 1)
        val startOfDay = monthStart 
        
        purchases.forEach { expense ->
             val dayIndex = ((expense.date - startOfDay) / 86400000L).toInt()
             if (dayIndex in 0..currentDay) {
                 amountByDay[dayIndex] += expense.effectiveAmount
             }
        }
        
        var runningTotal = 0.0
        val pastSumDaily = (0..currentDay).map { day ->
            runningTotal += amountByDay[day]
            runningTotal
        }

        // 2. Get Engines data - Reusing already fetched expenses to avoid redundant DB queries
        // Filter out isNotMine expenses
        val expensesForPace = expenses.filter { !it.isNotMine }
        val pace = insightsEngine.getSpendingPaceSuspend(expensesForPace)
        
        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = allRecurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetStatuses,
            spendingPace = pace
        )
        
        // 4. Generate Narrative
        val narrative = narrativeGenerator.generate(forecast, budgetStatuses)

        // 5. Map to UI Model
        FinancialWeather(
            state = narrative.state,
            headline = narrative.headline,
            summary = narrative.summary,
            icon = narrative.icon,
            riskLevel = when (forecast.components.riskLevel) {
                RiskLevel.LOW -> 10
                RiskLevel.MEDIUM -> 40
                RiskLevel.HIGH -> 70
                RiskLevel.CRITICAL -> 100
            },
            totalCommitted = forecast.components.totalCommitted,
            totalLikely = forecast.components.totalLikely,
            predictedDiscretionary = forecast.components.predictedDiscretionary,
            discretionaryBudget = forecast.components.discretionaryBudget,
            pastSpendingPoints = forecast.components.pastSpendingPoints,
            projectedSpendingPoints = forecast.components.projectedSpendingPoints,
            upcomingItems = buildUpcomingItems(
                forecast.components.recurringExpenses,
                forecast.components.plannedExpenses
            ),
            totalRecurringCount = allRecurringPatterns.size,
            details = narrative.details
        )
    }.catch { e ->
        Timber.e(e, "Error generating weather")
        emit(FinancialWeather(
            state = WeatherState.UNKNOWN,
            headline = "Weather Unavailable",
            summary = "We couldn't calculate your financial outlook right now.",
            icon = "❓",
            riskLevel = 0,
            totalCommitted = 0.0,
            totalLikely = 0.0,
            predictedDiscretionary = 0.0,
            discretionaryBudget = 0.0
        ))
    }

    private fun buildUpcomingItems(
        recurring: List<RecurringPattern>,
        planned: List<PlannedExpense>
    ): List<UpcomingItem> {
        val now = timeProvider.now()
        val startOfToday = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val horizon = startOfToday + (31 * 86_400_000L) // Show next 31 days
        
        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()
        
        recurring.filter { it.nextExpectedDate in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }
            
        planned.filter { it.date in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }
            
        return items.sortedBy { it.date }
    }

    fun getAllRecurringPatterns(): Flow<List<com.yourname.expensetracker.data.database.entity.ManualRecurringExpense>> {
        return recurringExpenseRepository.getAllFlow()
    }

    fun getAllPlannedExpenses(): Flow<List<com.yourname.expensetracker.data.database.entity.PlannedExpense>> {
        return plannedExpenseRepository.getAllPlannedExpenses()
    }
}
