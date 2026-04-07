package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.WeatherNarrative
import com.yourname.expensetracker.domain.model.NarrativeSection
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrativeGenerator @Inject constructor() {

    fun generate(
        forecast: FinancialForecast, 
        budgetStatuses: List<BudgetStatusSnapshot>
    ): WeatherNarrative {
        val components = forecast.components
        val risk = components.riskLevel
        val discretionary = components.discretionaryBudget

        val basic = when {
            risk == RiskLevel.CRITICAL -> WeatherNarrative(
                state = WeatherState.STORMY,
                icon = "⛈️",
                headline = "Stormy Weather",
                summary = "⚠️ Immediate action required. Budgets exceeded and no discretionary buffer remains."
            )
            risk == RiskLevel.HIGH && discretionary <= 0 -> WeatherNarrative(
                state = WeatherState.RAINY,
                icon = "🌧️",
                headline = "Rainy Conditions",
                summary = "Over pace on budgets and high committed costs. Caution is highly advised."
            )
            risk == RiskLevel.HIGH -> WeatherNarrative(
                state = WeatherState.CLOUDY,
                icon = "☁️",
                headline = "Cloudy Forecast",
                summary = "Spending is tight. You only have €${String.format(java.util.Locale.US, "%.2f", discretionary)} remaining for unpredicted expenses."
            )
            risk == RiskLevel.LOW && discretionary > 100.0 -> WeatherNarrative(
                state = WeatherState.CLEAR_SKIES,
                icon = "☀️",
                headline = "Clear Skies",
                summary = "You have a comfortable buffer of €${String.format(java.util.Locale.US, "%.2f", discretionary)} for the rest of the month."
            )
            risk == RiskLevel.LOW -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = "Partly Cloudy",
                summary = "Everything is on track, though discretionary buffer is moderate (€${String.format(java.util.Locale.US, "%.2f", discretionary)})."
            )
            risk == RiskLevel.MEDIUM -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = "Partly Cloudy",
                summary = "Everything is on track, though discretionary buffer is moderate (€${String.format(java.util.Locale.US, "%.2f", discretionary)})."
            )
            else -> WeatherNarrative(
                state = WeatherState.UNKNOWN,
                icon = "❓",
                headline = "Mixed Signals",
                summary = "Not enough data to provide a clear outlook yet."
            )
        }

        return basic.copy(details = buildDetails(forecast, budgetStatuses))
    }

    private fun buildDetails(
        forecast: FinancialForecast,
        budgetStatuses: List<BudgetStatusSnapshot>
    ): List<NarrativeSection> {
        val sections = mutableListOf<NarrativeSection>()
        val components = forecast.components

        // 1. Budget Health Section (Momentum Engine)
        val criticalBudgets = budgetStatuses.filter { 
            it.healthStatus == BudgetHealthStatus.EXCEEDED ||
            it.healthStatus == BudgetHealthStatus.CRITICAL 
        }
        if (criticalBudgets.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_ALERTS),
                    icon = "🚨",
                    items = criticalBudgets.map { 
                        val name = it.categoryName ?: "Total Budget"
                        "$name is ${it.healthStatus.name}: €${String.format(java.util.Locale.US, "%.0f", it.spentAmount)} spent"
                    }
                )
            )
        } else if (budgetStatuses.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_HEALTH),
                    icon = "✅",
                    items = listOf("All active budgets are currently on track")
                )
            )
        }

        // 2. Goal Protection (Constraint)
        if (components.goalReserves > 0) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_GOAL_RESERVES),
                    icon = "⛨",
                    items = listOf("€${String.format(java.util.Locale.US, "%.0f", components.goalReserves)} locked for high-priority savings")
                )
            )
        }

        // 3. Planned Intentions (Intention Engine)
        val importantPlans = components.plannedExpenses.filter { 
            it.priority == PlannedExpensePriority.MUST || 
            it.priority == PlannedExpensePriority.LIKELY 
        }
        if (importantPlans.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_COMMITTED_PLANS),
                    icon = "🎯",
                    items = importantPlans.map { 
                        val priorityLabel = if (it.priority == PlannedExpensePriority.MUST) "Must" else "Likely"
                        "${it.description}: €${String.format(java.util.Locale.US, "%.0f", it.amount)} ($priorityLabel)"
                    }
                )
            )
        }

        // 4. Predicted Habits (Behavioral)
        if (components.predictedDiscretionary > 0) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_PREDICTED_ACTIVITY),
                    icon = "📈",
                    items = listOf(
                        "Habit-based forecast: €${String.format(java.util.Locale.US, "%.0f", components.predictedDiscretionary)} likely spending based on your typical month."
                    )
                )
            )
        }

        return sections
    }
}
