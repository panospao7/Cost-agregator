package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.ConfirmedOccurrence
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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import kotlinx.coroutines.flow.first
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
    private val currencySettingsRepository: CurrencySettingsRepository,
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
        // FCST-N1: Pass actual manualRecurringEntities so the assembler can
        // (a) generate concrete occurrences for manual recurring rules and
        // (b) properly deduplicate detected patterns against manual ones.
        // Previously we passed emptyList() and depended on getAllRecurringPatternsSync
        // doing the merging externally, which meant occurrence generation was skipped.
        val detectedPatterns = mergedRecurringPatternsProvider.getPatternsFromSnapshots(
            expenseSnapshots = expenseSnapshots,
            manualRecurring = emptyList() // detected-only; manual passed separately below
        )
        val assembledInput = forecastInputAssembler.assemble(
            expenses = expenseSnapshots,
            manualRecurringEntities = recurringEntities,
            detectedRecurringPatterns = detectedPatterns,
            plannedExpenses = forecastInputAssembler.mapPlannedExpenses(plannedEntities),
            savingsGoals = forecastInputAssembler.mapSavingsGoals(savingsGoals),
            budgetStatuses = forecastInputAssembler.mapBudgetSnapshots(budgetStatuses)
        )

        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(assembledInput)
        // TODO (ARCH-02 Stage 2): Reduce forecast confidence by input.dataQuality.confidencePenalty

        // 4. Generate Narrative
        val homeCurrency = try { currencySettingsRepository.homeCurrency().first() } catch (_: Exception) { "EUR" }
        val narrative = narrativeGenerator.generate(forecast, assembledInput.budgetStatuses, homeCurrency)

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
                forecast.components.plannedExpenses,
                forecast.components.confirmedOccurrences
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
        planned: List<PlannedExpense>,
        confirmedOccurrences: List<ConfirmedOccurrence> = emptyList()
    ): List<UpcomingItem> {
        val now = timeProvider.now()
        val startOfToday = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val horizon = TimePeriodUtils.addDays(startOfToday, 31) // Exclusive

        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()

        // I1: Use materialised occurrences for manual rules (captures all WEEKLY/BIWEEKLY
        // repetitions instead of just the first nextExpectedDate).
        confirmedOccurrences
            .filter { it.dueDate >= startOfToday && it.dueDate < horizon }
            .forEach { items.add(UpcomingItem.Occurrence(it)) }

        // Detected-only patterns (id == null) have no occurrences — use single-date fallback.
        // Manual patterns are already represented via confirmedOccurrences, so skip them
        // to avoid double-counting in the detected-only fallback.
        recurring
            .filter { it.id == null }
            .filter { it.nextExpectedDate >= startOfToday && it.nextExpectedDate < horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }

        // P6-P1-4: Fallback — include manual patterns that were silently dropped when their
        // occurrence generation threw in ForecastInputAssembler.assemble() (line 396-398).
        // Match by merchant name and amount since ConfirmedOccurrence does not carry sourceId.
        for (pattern in recurring.filter { it.id != null }) {
            val alreadyMatched = confirmedOccurrences.any { occ ->
                occ.merchant == pattern.merchantName &&
                    kotlin.math.abs(occ.expectedAmount - pattern.averageAmount) < 0.01
            }
            if (!alreadyMatched &&
                pattern.nextExpectedDate >= startOfToday && pattern.nextExpectedDate < horizon
            ) {
                items.add(UpcomingItem.Recurring(pattern))
            }
        }

        planned.filter { it.date >= startOfToday && it.date < horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }

        return items.sortedBy { it.date }
    }

    fun getAllRecurringPatterns(): Flow<List<RecurringPattern>> =
        combine(
            expenseRepository.getAllExpenses(),
            recurringExpenseRepository.getAllFlow()
        ) { expenses, recurringEntities ->
            getAllRecurringPatternsSync(expenses, recurringEntities)
        }

    /**
     * Synchronous helper that merges manually-entered recurring rules with
     * detected-from-history patterns. This is the non-Flow version used by
     * [getFinancialWeather] to include all pattern types in the forecast.
     */
    private suspend fun getAllRecurringPatternsSync(
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        recurringEntities: List<com.yourname.expensetracker.data.database.entity.ManualRecurringExpense>
    ): List<RecurringPattern> {
        val expenseSnapshots = forecastInputAssembler.mapExpenseSnapshots(expenses)
        return mergedRecurringPatternsProvider.getPatternsFromSnapshots(
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
