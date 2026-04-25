package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.DashboardBudgetWarningInput
import com.yourname.expensetracker.domain.ai.model.DashboardUpcomingItemInput
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Maps a [ProcessedDashboardData] snapshot into a [DashboardBriefingInput].
 *
 * This is a pure data-extraction step: no AI calls, no suspend, no side effects.
 * The resulting [DashboardBriefingInput] is the immutable contract passed to
 * [DashboardBriefingService.generate] and is also hashed for cache-freshness
 * checks inside [GenerateDashboardBriefingUseCase].
 */
class DashboardBriefingInputBuilder @Inject constructor(
    private val timeProvider: TimeProvider
) {

    private val dateKeyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun build(
        processed: ProcessedDashboardData,
        eventTimeMillis: Long? = null
    ): DashboardBriefingInput {
        val data     = processed.data
        val summary  = processed.summary
        val weather  = data.weather
        val now      = eventTimeMillis ?: timeProvider.now()

        // --- top categories (name only, capped at 5) -------------------------
        val topCategories = processed.categoryBreakdown
            .sortedByDescending { it.amount }
            .take(5)
            .map { it.categoryName }

        // --- budget warnings (exceeded + critical) ---------------------------
        val budgetWarnings = data.budgetStatuses
            .filter { it.healthStatus == BudgetHealthStatus.EXCEEDED ||
                      it.healthStatus == BudgetHealthStatus.CRITICAL }
            .map { status ->
                DashboardBudgetWarningInput(
                    categoryLabel = status.categoryName
                        ?.let(UiText::from)
                        ?: UiText.fromKey(DomainTextKeys.DASHBOARD_BRIEFING_OVERALL),
                    percentUsed = (status.percentUsed * 100).toInt()
                )
            }

        // --- upcoming item facts (capped at 5) --------------------------------
        val upcomingItems = weather.upcomingItems
            .take(5)
            .map { item ->
                DashboardUpcomingItemInput(
                    description = item.description,
                    amount = item.amount,
                    dateMillis = item.date,
                    currencyCode = item.currencyCodeOrNull()
                )
            }

        return DashboardBriefingInput(
            dateKey              = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .format(dateKeyFormat),
            weatherHeadline      = weather.headline,
            weatherSummary       = weather.summary,
            discretionaryBudget  = weather.discretionaryBudget,
            totalCommitted       = weather.totalCommitted,
            totalLikely          = weather.totalLikely,
            pendingReviewCount   = data.pendingCount,
            currentMonthSpent    = summary.totalSpent,
            topCategories        = topCategories,
            budgetWarnings       = budgetWarnings,
            upcomingItems        = upcomingItems
        )
    }

    private fun com.yourname.expensetracker.domain.model.UpcomingItem.currencyCodeOrNull(): String? = when (this) {
        is com.yourname.expensetracker.domain.model.UpcomingItem.Recurring -> pattern.currency
        is com.yourname.expensetracker.domain.model.UpcomingItem.Planned -> null
    }
}
