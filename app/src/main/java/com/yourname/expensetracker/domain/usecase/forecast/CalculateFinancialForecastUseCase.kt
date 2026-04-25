package com.yourname.expensetracker.domain.usecase.forecast

import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class CalculateFinancialForecastUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val forecastInputAssembler: ForecastInputAssembler,
    private val synthesisEngine: SynthesisEngine,
    private val timeBoundaryTicker: TimeBoundaryTicker
) {
    operator fun invoke(): Flow<FinancialForecast> {
        val sourceData = combine(
            expenseRepository.getAllExpenses(),
            budgetRepository.getBudgetStatuses(),
            recurringExpenseRepository.getAllFlow(),
            plannedExpenseRepository.getAllPlannedExpenses(),
            savingsGoalRepository.observeSavingsGoals()
        ) { expenses, budgetStatuses, recurringEntities, plannedEntities, goalEntities ->
            ForecastSourceData(
                expenses = expenses,
                budgetStatuses = budgetStatuses,
                recurringEntities = recurringEntities,
                plannedEntities = plannedEntities,
                goalEntities = goalEntities
            )
        }

        return combine(sourceData, timeBoundaryTicker.dayBoundaryTicks()) { source, _ ->
            synthesizeForecast(source)
        }
    }

    private suspend fun synthesizeForecast(source: ForecastSourceData): FinancialForecast {
        val expenses = source.expenses
        val budgetStatuses = source.budgetStatuses
        val recurringEntities = source.recurringEntities
        val plannedEntities = source.plannedEntities
        val goalEntities = source.goalEntities

        val expenseSnapshots = forecastInputAssembler.mapExpenseSnapshots(expenses)
        val confirmedRecurringPatterns = recurringPatternsProvider.getConfirmedPatterns(recurringEntities)

        val assembledInput = forecastInputAssembler.assemble(
            expenses = expenseSnapshots,
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = confirmedRecurringPatterns,
            plannedExpenses = forecastInputAssembler.mapPlannedExpenses(plannedEntities),
            savingsGoals = forecastInputAssembler.mapSavingsGoals(goalEntities),
            budgetStatuses = forecastInputAssembler.mapBudgetSnapshots(budgetStatuses)
        )

        return synthesisEngine.synthesize(assembledInput)
    }

    private data class ForecastSourceData(
        val expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        val budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>,
        val recurringEntities: List<com.yourname.expensetracker.data.database.entity.ManualRecurringExpense>,
        val plannedEntities: List<com.yourname.expensetracker.data.database.entity.PlannedExpense>,
        val goalEntities: List<SavingsGoal>
    )

}
