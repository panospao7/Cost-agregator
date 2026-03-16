package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun build(processed: ProcessedDashboardData): DashboardBriefingInput {
        val data     = processed.data
        val summary  = processed.summary
        val weather  = data.weather
        val now      = timeProvider.now()

        // --- top categories (name only, capped at 5) -------------------------
        val topCategories = processed.categoryBreakdown
            .sortedByDescending { it.total }
            .take(5)
            .map { it.category.name }

        // --- budget warnings (exceeded + critical) ---------------------------
        val budgetWarnings = data.budgetStatuses
            .filter { it.healthStatus == BudgetHealthStatus.EXCEEDED ||
                      it.healthStatus == BudgetHealthStatus.CRITICAL }
            .mapNotNull { status ->
                val name = status.category?.name ?: "Overall"
                val pct  = (status.percentUsed * 100).toInt()
                "$name at $pct%"
            }

        // --- upcoming item labels (capped at 5) ------------------------------
        val upcomingItems = weather.upcomingItems
            .take(5)
            .map { item ->
                val dateLabel = dateKeyFormat.format(Date(item.date))
                "${item.description} €${"%.0f".format(item.amount)} on $dateLabel"
            }

        return DashboardBriefingInput(
            dateKey              = dateKeyFormat.format(Date(now)),
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
}
