package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import timber.log.Timber

@Singleton
@OptIn(kotlinx.coroutines.FlowPreview::class)
class FinancialWeatherRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val mergedRecurringPatternsProvider: MergedRecurringPatternsProvider,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: DomainSavingsGoalRepository,
    private val forecastInputAssembler: ForecastInputAssembler,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator,
    private val analyticsRepository: AnalyticsRepository,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {
    fun getFinancialWeather(): Flow<FinancialWeather> = combine(
        expenseRepository.getAllExpenses().catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) },
        recurringExpenseRepository.getAllFlow().catch { emit(emptyList()) },
        plannedExpenseRepository.getAllPlannedExpenses().catch { emit(emptyList()) },
        savingsGoalRepository.observeSavingsGoals().catch { emit(emptyList()) }
    ) { expenses, budgetStatuses, recurringEntities, plannedEntities, savingsGoals ->
        val expenseSnapshots = forecastInputAssembler.mapExpenseSnapshots(expenses)
        val confirmedRecurringPatterns = mergedRecurringPatternsProvider.getConfirmedPatterns(
            manualRecurring = recurringEntities
        )
        val assembledInput = forecastInputAssembler.assemble(
            expenses = expenseSnapshots,
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = confirmedRecurringPatterns,
            plannedExpenses = forecastInputAssembler.mapPlannedExpenses(plannedEntities),
            savingsGoals = forecastInputAssembler.mapSavingsGoals(savingsGoals),
            budgetStatuses = forecastInputAssembler.mapBudgetSnapshots(budgetStatuses)
        )

        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(assembledInput)
        
        // 4. Generate Narrative
        val narrative = narrativeGenerator.generate(forecast, assembledInput.budgetStatuses)

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
            totalRecurringCount = assembledInput.recurringPatterns.size,
            details = narrative.details
        )
    }.catch { e ->
        Timber.e(e, "Error generating weather")
        emit(FinancialWeather(
            state = WeatherState.UNKNOWN,
            headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_UNAVAILABLE),
            summary = UiText.fromKey(DomainTextKeys.WEATHER_SUMMARY_UNAVAILABLE),
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
        val horizon = TimePeriodUtils.addDays(startOfToday, 31) // Exclusive
        
        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()
        
        recurring.filter { it.nextExpectedDate >= startOfToday && it.nextExpectedDate < horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }
            
        planned.filter { it.date >= startOfToday && it.date < horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }
            
        return items.sortedBy { it.date }
    }

    fun getAllRecurringPatterns(): Flow<List<RecurringPattern>> =
        combine(
            expenseRepository.getAllExpenses(),
            recurringExpenseRepository.getAllFlow()
        ) { expenses, recurringEntities ->
            val expenseSnapshots = forecastInputAssembler.mapExpenseSnapshots(expenses)
            mergedRecurringPatternsProvider.getPatternsFromSnapshots(
                expenseSnapshots = expenseSnapshots,
                manualRecurring = recurringEntities
            )
        }

    fun getConfirmedRecurringPatterns(): Flow<List<RecurringPattern>> =
        recurringExpenseRepository.getAllFlow().map { recurringEntities ->
            mergedRecurringPatternsProvider.getConfirmedPatterns(recurringEntities)
        }

    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> =
        plannedExpenseRepository.getAllPlannedExpenses().map { entities ->
            forecastInputAssembler.mapPlannedExpenses(entities)
        }
}
