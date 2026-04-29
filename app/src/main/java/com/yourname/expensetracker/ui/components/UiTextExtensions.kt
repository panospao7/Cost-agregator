package com.yourname.expensetracker.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.DateFormatterUtils

fun UiText.asString(context: Context): String {
    return when (this) {
        is UiText.StringResource -> {
            val resolvedArgs = args.resolveArgs()
            if (args.isEmpty()) context.getString(resId)
            else context.getString(resId, *resolvedArgs.toTypedArray())
        }
        is UiText.DynamicString -> value
        is UiText.PluralResource -> {
            val resolvedArgs = args.resolveArgs()
            if (args.isEmpty()) context.resources.getQuantityString(resId, quantity, quantity)
            else context.resources.getQuantityString(resId, quantity, *resolvedArgs.toTypedArray())
        }
        is UiText.MessageKey -> {
            val mappedResId = key.toResourceIdOrNull()
            val resolvedArgs = args.resolveArgs()
            if (mappedResId != null) {
                if (args.isEmpty()) context.getString(mappedResId)
                else context.getString(mappedResId, *resolvedArgs.toTypedArray())
            } else {
                if (args.isEmpty()) key else "$key ${args.joinToString(", ")}" 
            }
        }
    }
}

@Composable
fun UiText.asString(): String {
    return when (this) {
        is UiText.StringResource -> {
            val resolvedArgs = args.resolveArgs()
            if (args.isEmpty()) stringResource(resId)
            else stringResource(resId, *resolvedArgs.toTypedArray())
        }
        is UiText.DynamicString -> value
        is UiText.PluralResource -> {
            val resolvedArgs = args.resolveArgs()
            if (args.isEmpty()) pluralStringResource(resId, quantity, quantity)
            else pluralStringResource(resId, quantity, *resolvedArgs.toTypedArray())
        }
        is UiText.MessageKey -> {
            val mappedResId = key.toResourceIdOrNull()
            if (mappedResId != null) {
                val resolvedArgs = args.resolveArgs()
                if (args.isEmpty()) stringResource(mappedResId)
                else stringResource(mappedResId, *resolvedArgs.toTypedArray())
            } else {
                if (args.isEmpty()) key else "$key ${args.joinToString(", ")}" 
            }
        }
    }
}

private fun List<Any>.resolveArgs(): List<Any> = map { arg ->
    when (arg) {
        is UiTextArg.Money -> {
            CurrencyFormatter.format(arg.amount, arg.currency, arg.showCents)
        }

        is UiTextArg.Percent -> "%1$.${arg.decimals}f%%".format(arg.value)
        is UiTextArg.DateMillis -> DateFormatterUtils.formatTimestampJavaTime(arg.timestamp, arg.pattern)
        else -> arg
    }
}

@Composable
fun UiText?.asStringOrNull(): String? = this?.asString()

@Composable
fun UiText?.asStringOrDefault(default: String): String = this?.asString() ?: default

private fun String.toResourceIdOrNull(): Int? {
    return when (this) {
        DomainTextKeys.DASHBOARD_BRIEFING_OVERALL -> R.string.dashboard_briefing_overall
        DomainTextKeys.COMMON_UNKNOWN -> R.string.unknown
        DomainTextKeys.COMMON_DAY_MONDAY -> R.string.day_monday
        DomainTextKeys.COMMON_DAY_TUESDAY -> R.string.day_tuesday
        DomainTextKeys.COMMON_DAY_WEDNESDAY -> R.string.day_wednesday
        DomainTextKeys.COMMON_DAY_THURSDAY -> R.string.day_thursday
        DomainTextKeys.COMMON_DAY_FRIDAY -> R.string.day_friday
        DomainTextKeys.COMMON_DAY_SATURDAY -> R.string.day_saturday
        DomainTextKeys.COMMON_DAY_SUNDAY -> R.string.day_sunday
        DomainTextKeys.ANALYTICS_HIGH_SPENDING_DESCRIPTION_FORMAT -> R.string.analytics_high_spending_description
        DomainTextKeys.ANALYTICS_WEEKEND_SPENDING_DESCRIPTION -> R.string.analytics_weekend_spending_description
        DomainTextKeys.ANALYTICS_GREAT_SAVINGS_DESCRIPTION_FORMAT -> R.string.analytics_great_savings_description
        DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_UP_TITLE_FORMAT -> R.string.analytics_insight_spending_up_title_format
        DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_UP_DESCRIPTION_FORMAT -> R.string.analytics_insight_spending_up_description_format
        DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_DOWN_TITLE_FORMAT -> R.string.analytics_insight_spending_down_title_format
        DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_DOWN_DESCRIPTION_FORMAT -> R.string.analytics_insight_spending_down_description_format
        DomainTextKeys.ANALYTICS_INSIGHT_PACE_WARNING_TITLE -> R.string.analytics_insight_pace_warning_title
        DomainTextKeys.ANALYTICS_INSIGHT_PACE_WARNING_DESCRIPTION_FORMAT -> R.string.analytics_insight_pace_warning_description_format
        DomainTextKeys.ANALYTICS_INSIGHT_CATEGORY_UP_TITLE_FORMAT -> R.string.analytics_insight_category_up_title_format
        DomainTextKeys.ANALYTICS_INSIGHT_CATEGORY_UP_DESCRIPTION_FORMAT -> R.string.analytics_insight_category_up_description_format
        DomainTextKeys.ANALYTICS_INSIGHT_RECURRING_TITLE_FORMAT -> R.string.analytics_insight_recurring_title_format
        DomainTextKeys.ANALYTICS_INSIGHT_RECURRING_DESCRIPTION_FORMAT -> R.string.analytics_insight_recurring_description_format
        DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_TITLE_FORMAT -> R.string.analytics_insight_largest_transaction_title_format
        DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_DESCRIPTION_FORMAT -> R.string.analytics_insight_largest_transaction_description_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_SINGLE_BILL_DUE_FORMAT -> R.string.money_radar_reason_single_bill_due_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_BILLS_DUE_FORMAT -> R.string.money_radar_reason_multiple_bills_due_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_SINGLE_ANOMALY_FORMAT -> R.string.money_radar_reason_single_anomaly_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_ANOMALIES_FORMAT -> R.string.money_radar_reason_multiple_anomalies_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_CRITICAL_FORMAT -> R.string.money_radar_reason_budget_risk_critical_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_HIGH_FORMAT -> R.string.money_radar_reason_budget_risk_high_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_MEDIUM_FORMAT -> R.string.money_radar_reason_budget_risk_medium_format
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY -> R.string.money_radar_reason_finances_healthy
        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MONITOR_SPENDING -> R.string.money_radar_reason_monitor_spending
        DomainTextKeys.BUDGET_REDUCE_URGENT -> R.string.domain_budget_reduce_urgent
        DomainTextKeys.BUDGET_PAUSE_NON_ESSENTIAL -> R.string.domain_budget_pause_non_essential
        DomainTextKeys.BUDGET_REVIEW_SUBSCRIPTIONS -> R.string.domain_budget_review_subscriptions
        DomainTextKeys.BUDGET_BUILD_HISTORY -> R.string.domain_budget_build_history
        DomainTextKeys.BUDGET_EARLY_WARNING -> R.string.domain_budget_early_warning
        DomainTextKeys.BUDGET_INCREASE -> R.string.domain_budget_increase
        DomainTextKeys.SAVINGS_GOAL_SETTER -> R.string.domain_savings_goal_setter
        DomainTextKeys.SAVINGS_WEEK_WARRIOR -> R.string.domain_savings_week_warrior
        DomainTextKeys.SAVINGS_CENTURY_CLUB -> R.string.domain_savings_century_club
        DomainTextKeys.SAVINGS_GOAL_CRUSHER -> R.string.domain_savings_goal_crusher
        DomainTextKeys.SAVINGS_GRAND_SAVER -> R.string.domain_savings_grand_saver
        DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_FIRST_GOAL -> R.string.domain_savings_achievement_desc_first_goal
        DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_STREAK -> R.string.domain_savings_achievement_desc_streak
        DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_SAVE_TOTAL_FORMAT -> R.string.domain_savings_achievement_desc_save_total_format
        DomainTextKeys.SAVINGS_ACHIEVEMENT_DESC_COMPLETE_FIRST_GOAL -> R.string.domain_savings_achievement_desc_complete_first_goal
        DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_CREATE_GOAL -> R.string.domain_savings_achievement_req_create_goal
        DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_STREAK -> R.string.domain_savings_achievement_req_streak
        DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_SAVED_FORMAT -> R.string.domain_savings_achievement_req_saved_format
        DomainTextKeys.SAVINGS_ACHIEVEMENT_REQ_COMPLETE_GOAL -> R.string.domain_savings_achievement_req_complete_goal
        DomainTextKeys.NARRATIVE_BUDGET_ALERTS -> R.string.domain_narrative_budget_alerts
        DomainTextKeys.NARRATIVE_BUDGET_HEALTH -> R.string.domain_narrative_budget_health
        DomainTextKeys.NARRATIVE_GOAL_RESERVES -> R.string.domain_narrative_goal_reserves
        DomainTextKeys.NARRATIVE_COMMITTED_PLANS -> R.string.domain_narrative_committed_plans
        DomainTextKeys.NARRATIVE_PREDICTED_ACTIVITY -> R.string.domain_narrative_predicted_activity
        DomainTextKeys.WEATHER_HEADLINE_STORMY -> R.string.domain_weather_headline_stormy_weather
        DomainTextKeys.WEATHER_SUMMARY_STORMY -> R.string.domain_weather_summary_stormy_weather
        DomainTextKeys.WEATHER_HEADLINE_RAINY -> R.string.domain_weather_headline_rainy_conditions
        DomainTextKeys.WEATHER_SUMMARY_RAINY -> R.string.domain_weather_summary_rainy_conditions
        DomainTextKeys.WEATHER_HEADLINE_CLOUDY -> R.string.domain_weather_headline_cloudy_forecast
        DomainTextKeys.WEATHER_SUMMARY_CLOUDY_FORMAT -> R.string.domain_weather_summary_cloudy_forecast_format
        DomainTextKeys.WEATHER_HEADLINE_CLEAR -> R.string.domain_weather_headline_clear_skies
        DomainTextKeys.WEATHER_SUMMARY_CLEAR_FORMAT -> R.string.domain_weather_summary_clear_skies_format
        DomainTextKeys.WEATHER_HEADLINE_PARTLY_CLOUDY -> R.string.domain_weather_headline_partly_cloudy
        DomainTextKeys.WEATHER_SUMMARY_PARTLY_CLOUDY_FORMAT -> R.string.domain_weather_summary_partly_cloudy_format
        DomainTextKeys.WEATHER_HEADLINE_MIXED -> R.string.domain_weather_headline_mixed_signals
        DomainTextKeys.WEATHER_SUMMARY_MIXED -> R.string.domain_weather_summary_mixed_signals
        DomainTextKeys.WEATHER_HEADLINE_UNAVAILABLE -> R.string.domain_weather_headline_unavailable
        DomainTextKeys.WEATHER_SUMMARY_UNAVAILABLE -> R.string.domain_weather_summary_unavailable
        DomainTextKeys.NARRATIVE_BUDGET_EXCEEDED_SPENT_FORMAT -> R.string.domain_narrative_budget_exceeded_spent_format
        DomainTextKeys.NARRATIVE_TOTAL_BUDGET_EXCEEDED_SPENT_FORMAT -> R.string.domain_narrative_total_budget_exceeded_spent_format
        DomainTextKeys.NARRATIVE_BUDGET_CRITICAL_SPENT_FORMAT -> R.string.domain_narrative_budget_critical_spent_format
        DomainTextKeys.NARRATIVE_TOTAL_BUDGET_CRITICAL_SPENT_FORMAT -> R.string.domain_narrative_total_budget_critical_spent_format
        DomainTextKeys.NARRATIVE_BUDGET_WARNING_SPENT_FORMAT -> R.string.domain_narrative_budget_warning_spent_format
        DomainTextKeys.NARRATIVE_BUDGET_ON_TRACK_SPENT_FORMAT -> R.string.domain_narrative_budget_on_track_spent_format
        DomainTextKeys.NARRATIVE_ALL_BUDGETS_ON_TRACK -> R.string.domain_narrative_all_budgets_on_track
        DomainTextKeys.NARRATIVE_GOAL_RESERVES_LOCKED_FORMAT -> R.string.domain_narrative_goal_reserves_locked_format
        DomainTextKeys.NARRATIVE_MUST_PLAN_FORMAT -> R.string.domain_narrative_must_plan_format
        DomainTextKeys.NARRATIVE_LIKELY_PLAN_FORMAT -> R.string.domain_narrative_likely_plan_format
        DomainTextKeys.NARRATIVE_HABIT_FORECAST_FORMAT -> R.string.domain_narrative_habit_forecast_format
        DomainTextKeys.SYNTHESIS_SPENDING_PACE_HIGHER -> R.string.domain_synthesis_spending_pace_higher
        DomainTextKeys.SYNTHESIS_BUDGETS_EXCEEDED_FORMAT -> R.string.domain_synthesis_budgets_exceeded_format
        DomainTextKeys.SYNTHESIS_STRICT_SAVINGS_GOALS_ACTIVE_FORMAT -> R.string.domain_synthesis_strict_savings_goals_active_format
        DomainTextKeys.SYNTHESIS_MUST_PAY_PLANNED_EXPENSES_FORMAT -> R.string.domain_synthesis_must_pay_planned_expenses_format
        DomainTextKeys.SAVINGS_PORTFOLIO_RECOMMENDATION_READY -> R.string.domain_savings_portfolio_recommendation_ready
        DomainTextKeys.SAVINGS_IMPACT_GOAL_ALREADY_REACHED -> R.string.domain_savings_impact_goal_already_reached
        DomainTextKeys.SAVINGS_IMPACT_REACH_IN_DAYS_FORMAT -> R.string.domain_savings_impact_reach_in_days_format
        DomainTextKeys.SAVINGS_IMPACT_ON_TRACK_MONTHS_FORMAT -> R.string.domain_savings_impact_on_track_months_format
        DomainTextKeys.SAVINGS_IMPACT_STEADY_PROGRESS_FORMAT -> R.string.domain_savings_impact_steady_progress_format
        DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT -> R.string.widget_budget_exceeded_format
        DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK -> R.string.widget_all_budgets_on_track
        DashboardTextKeys.WIDGET_INSIGHT_SPENT_LESS_FORMAT -> R.string.widget_insight_spent_less_format
        DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT -> R.string.widget_insight_spent_higher_format
        DashboardTextKeys.WIDGET_INSIGHT_TODAY_SPENT_FORMAT -> R.string.widget_insight_today_spent_format
        else -> null
    }
}
