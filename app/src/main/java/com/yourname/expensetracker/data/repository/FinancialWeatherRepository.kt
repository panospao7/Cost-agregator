package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
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
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

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
class FinancialWeatherRepository @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val insightsEngine: InsightsEngine,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator
) {

    fun getFinancialWeather(): Flow<FinancialWeather> = combine(
        notificationRepository.getAllExpenses(),
        budgetRepository.getBudgetStatuses(),
        getAllRecurringPatterns(),
        plannedExpenseDao.getAllPlannedExpenses(),
        savingsGoalDao.getAllGoals()
    ) { expenses, budgetStatuses, recurringPatterns, plannedEntities, goalEntities ->
        
        val plannedExpenses = plannedEntities.map { entity ->
            PlannedExpense(
                id = entity.id,
                description = entity.description,
                amount = entity.amount,
                date = entity.date,
                categoryId = entity.categoryId,
                isRecurring = entity.isRecurring,
                priority = when(entity.priority) {
                    EntityPlannedPriority.MUST -> DomainPlannedPriority.MUST
                    EntityPlannedPriority.LIKELY -> DomainPlannedPriority.LIKELY
                    EntityPlannedPriority.OPTIONAL -> DomainPlannedPriority.OPTIONAL
                }
            )
        }
        
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
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        
        val purchases = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE 
        }
        
        // 1. Calculate Past Daily Cumulative Spend - Optimized single pass
        val calInstance = Calendar.getInstance()
        val amountByDay = DoubleArray(currentDay + 1)
        
        for (expense in purchases) {
            if (expense.date >= monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= currentDay) {
                    amountByDay[day] += expense.amount
                }
            }
        }
        
        var runningTotal = 0.0
        val pastSumDaily = (1..currentDay).map { day ->
            runningTotal += amountByDay[day]
            runningTotal
        }

        // 2. Get Engines data - Reusing already fetched expenses to avoid redundant DB queries
        val pace = insightsEngine.getSpendingPaceSuspend(expenses)
        
        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
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
            totalRecurringCount = recurringPatterns.size,
            details = narrative.details
        )
    }.catch { e ->
        android.util.Log.e("FinancialWeatherRepo", "Error generating weather", e)
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
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val horizon = startOfToday + (31 * 86_400_000L) // Show next 31 days in the list
        
        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()
        
        recurring.filter { it.nextExpectedDate in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }
            
        planned.filter { it.date in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }
            
        return items.sortedBy { it.date }
    }

    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> = plannedExpenseDao.getAllPlannedExpenses()
        .map { entities ->
            entities.map { entity ->
                PlannedExpense(
                    id = entity.id,
                    description = entity.description,
                    amount = entity.amount,
                    date = entity.date,
                    categoryId = entity.categoryId,
                    isRecurring = entity.isRecurring,
                    priority = when(entity.priority) {
                        EntityPlannedPriority.MUST -> DomainPlannedPriority.MUST
                        EntityPlannedPriority.LIKELY -> DomainPlannedPriority.LIKELY
                        EntityPlannedPriority.OPTIONAL -> DomainPlannedPriority.OPTIONAL
                    }
                )
            }
        }

    fun getAllRecurringPatterns(): Flow<List<RecurringPattern>> = recurringExpenseDao.getAllFlow()
        .map { recurringExpenseEngine.getPatterns() }
}
