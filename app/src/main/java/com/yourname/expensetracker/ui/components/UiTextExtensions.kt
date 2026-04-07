package com.yourname.expensetracker.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.text.DomainTextKeys

fun UiText.asString(context: Context): String {
    return when (this) {
        is UiText.StringResource -> {
            if (args.isEmpty()) context.getString(resId)
            else context.getString(resId, *args.toTypedArray())
        }
        is UiText.DynamicString -> value
        is UiText.PluralResource -> {
            if (args.isEmpty()) context.resources.getQuantityString(resId, quantity, quantity)
            else context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
        }
        is UiText.MessageKey -> {
            val mappedResId = key.toResourceIdOrNull()
            if (mappedResId != null) {
                if (args.isEmpty()) context.getString(mappedResId)
                else context.getString(mappedResId, *args.toTypedArray())
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
            if (args.isEmpty()) stringResource(resId)
            else stringResource(resId, *args.toTypedArray())
        }
        is UiText.DynamicString -> value
        is UiText.PluralResource -> {
            if (args.isEmpty()) pluralStringResource(resId, quantity, quantity)
            else pluralStringResource(resId, quantity, *args.toTypedArray())
        }
        is UiText.MessageKey -> {
            val mappedResId = key.toResourceIdOrNull()
            if (mappedResId != null) {
                if (args.isEmpty()) stringResource(mappedResId)
                else stringResource(mappedResId, *args.toTypedArray())
            } else {
                if (args.isEmpty()) key else "$key ${args.joinToString(", ")}"
            }
        }
    }
}

@Composable
fun UiText?.asStringOrNull(): String? = this?.asString()

@Composable
fun UiText?.asStringOrDefault(default: String): String = this?.asString() ?: default

private fun String.toResourceIdOrNull(): Int? {
    return when (this) {
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
        DomainTextKeys.NARRATIVE_BUDGET_ALERTS -> R.string.domain_narrative_budget_alerts
        DomainTextKeys.NARRATIVE_BUDGET_HEALTH -> R.string.domain_narrative_budget_health
        DomainTextKeys.NARRATIVE_GOAL_RESERVES -> R.string.domain_narrative_goal_reserves
        DomainTextKeys.NARRATIVE_COMMITTED_PLANS -> R.string.domain_narrative_committed_plans
        DomainTextKeys.NARRATIVE_PREDICTED_ACTIVITY -> R.string.domain_narrative_predicted_activity
        DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT -> R.string.widget_budget_exceeded_format
        DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK -> R.string.widget_all_budgets_on_track
        DashboardTextKeys.WIDGET_INSIGHT_SPENT_LESS_FORMAT -> R.string.widget_insight_spent_less_format
        DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT -> R.string.widget_insight_spent_higher_format
        DashboardTextKeys.WIDGET_INSIGHT_TODAY_SPENT_FORMAT -> R.string.widget_insight_today_spent_format
        else -> null
    }
}
