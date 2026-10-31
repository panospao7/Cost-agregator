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
        // S8a (INV-31, fail-closed): only the variants explicitly mapped in
        // toCloudInputOrNull() reach the payload. Any other UpcomingItem variant is
        // dropped WHOLE before the cap, so the cap fills from allowed items only.
        val upcomingItems = weather.upcomingItems
            .mapNotNull { item -> item.toCloudInputOrNull() }
            .take(5)

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

    /**
     * Explicit allow-list mapping (INV-31). The `else` branch is deliberate: a variant
     * added to [com.yourname.expensetracker.domain.model.UpcomingItem] later (for example a
     * locally projected plan item) is excluded from the cloud payload by default and has to
     * be opted in here, never the other way round. Exclusion is whole-item: no field of an
     * excluded item is copied. Never a user setting.
     */
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun com.yourname.expensetracker.domain.model.UpcomingItem.toCloudInputOrNull(): DashboardUpcomingItemInput? =
        when (this) {
            is com.yourname.expensetracker.domain.model.UpcomingItem.Recurring -> cloudInput(pattern.currency)
            is com.yourname.expensetracker.domain.model.UpcomingItem.Occurrence -> cloudInput(occurrence.expectedCurrency)
            is com.yourname.expensetracker.domain.model.UpcomingItem.Planned -> cloudInput(null)
            else -> null
        }

    private fun com.yourname.expensetracker.domain.model.UpcomingItem.cloudInput(
        currencyCode: String?
    ): DashboardUpcomingItemInput = DashboardUpcomingItemInput(
        description = description,
        amount = amount,
        dateMillis = date,
        currencyCode = currencyCode
    )
}
