package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.WeatherNarrative
import com.yourname.expensetracker.domain.model.NarrativeSection
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
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
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_STORMY),
                summary = UiText.fromKey(DomainTextKeys.WEATHER_SUMMARY_STORMY)
            )
            risk == RiskLevel.HIGH && discretionary <= 0 -> WeatherNarrative(
                state = WeatherState.RAINY,
                icon = "🌧️",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_RAINY),
                summary = UiText.fromKey(DomainTextKeys.WEATHER_SUMMARY_RAINY)
            )
            risk == RiskLevel.HIGH -> WeatherNarrative(
                state = WeatherState.CLOUDY,
                icon = "☁️",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_CLOUDY),
                summary = UiText.fromKey(
                    DomainTextKeys.WEATHER_SUMMARY_CLOUDY_FORMAT,
                    UiTextArg.Money(discretionary)
                )
            )
            risk == RiskLevel.LOW && discretionary > 100.0 -> WeatherNarrative(
                state = WeatherState.CLEAR_SKIES,
                icon = "☀️",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_CLEAR),
                summary = UiText.fromKey(
                    DomainTextKeys.WEATHER_SUMMARY_CLEAR_FORMAT,
                    UiTextArg.Money(discretionary)
                )
            )
            risk == RiskLevel.LOW -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_PARTLY_CLOUDY),
                summary = UiText.fromKey(
                    DomainTextKeys.WEATHER_SUMMARY_PARTLY_CLOUDY_FORMAT,
                    UiTextArg.Money(discretionary)
                )
            )
            risk == RiskLevel.MEDIUM -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_PARTLY_CLOUDY),
                summary = UiText.fromKey(
                    DomainTextKeys.WEATHER_SUMMARY_PARTLY_CLOUDY_FORMAT,
                    UiTextArg.Money(discretionary)
                )
            )
            else -> WeatherNarrative(
                state = WeatherState.UNKNOWN,
                icon = "❓",
                headline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_MIXED),
                summary = UiText.fromKey(DomainTextKeys.WEATHER_SUMMARY_MIXED)
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
                        when (it.healthStatus) {
                            BudgetHealthStatus.EXCEEDED -> it.categoryName?.let { name ->
                                UiText.fromKey(
                                    DomainTextKeys.NARRATIVE_BUDGET_EXCEEDED_SPENT_FORMAT,
                                    name,
                                    UiTextArg.Money(it.spentAmount, showCents = false)
                                )
                            } ?: UiText.fromKey(
                                DomainTextKeys.NARRATIVE_TOTAL_BUDGET_EXCEEDED_SPENT_FORMAT,
                                UiTextArg.Money(it.spentAmount, showCents = false)
                            )
                            BudgetHealthStatus.CRITICAL -> it.categoryName?.let { name ->
                                UiText.fromKey(
                                    DomainTextKeys.NARRATIVE_BUDGET_CRITICAL_SPENT_FORMAT,
                                    name,
                                    UiTextArg.Money(it.spentAmount, showCents = false)
                                )
                            } ?: UiText.fromKey(
                                DomainTextKeys.NARRATIVE_TOTAL_BUDGET_CRITICAL_SPENT_FORMAT,
                                UiTextArg.Money(it.spentAmount, showCents = false)
                            )
                            BudgetHealthStatus.WARNING -> UiText.fromKey(
                                DomainTextKeys.NARRATIVE_BUDGET_WARNING_SPENT_FORMAT,
                                it.categoryName ?: UiTextArg.Money(it.spentAmount, showCents = false),
                                UiTextArg.Money(it.spentAmount, showCents = false)
                            )
                            BudgetHealthStatus.ON_TRACK -> UiText.fromKey(
                                DomainTextKeys.NARRATIVE_BUDGET_ON_TRACK_SPENT_FORMAT,
                                it.categoryName ?: UiTextArg.Money(it.spentAmount, showCents = false),
                                UiTextArg.Money(it.spentAmount, showCents = false)
                            )
                        }
                    }
                )
            )
        } else if (budgetStatuses.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_HEALTH),
                    icon = "✅",
                    items = listOf(
                        UiText.fromKey(DomainTextKeys.NARRATIVE_ALL_BUDGETS_ON_TRACK)
                    )
                )
            )
        }

        // 2. Goal Protection (Constraint)
        if (components.goalReserves > 0) {
            sections.add(
                NarrativeSection(
                    title = UiText.fromKey(DomainTextKeys.NARRATIVE_GOAL_RESERVES),
                    icon = "⛨",
                    items = listOf(
                        UiText.fromKey(
                            DomainTextKeys.NARRATIVE_GOAL_RESERVES_LOCKED_FORMAT,
                            UiTextArg.Money(components.goalReserves, showCents = false)
                        )
                    )
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
                        if (it.priority == PlannedExpensePriority.MUST) {
                            UiText.fromKey(
                                DomainTextKeys.NARRATIVE_MUST_PLAN_FORMAT,
                                it.description,
                                UiTextArg.Money(it.amount, showCents = false)
                            )
                        } else {
                            UiText.fromKey(
                                DomainTextKeys.NARRATIVE_LIKELY_PLAN_FORMAT,
                                it.description,
                                UiTextArg.Money(it.amount, showCents = false)
                            )
                        }
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
                        UiText.fromKey(
                            DomainTextKeys.NARRATIVE_HABIT_FORECAST_FORMAT,
                            UiTextArg.Money(components.predictedDiscretionary, showCents = false)
                        )
                    )
                )
            )
        }

        return sections
    }
}
