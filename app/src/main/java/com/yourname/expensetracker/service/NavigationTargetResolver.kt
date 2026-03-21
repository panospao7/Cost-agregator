package com.yourname.expensetracker.service

import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves recommendation navigation targets to concrete [NavigationAction]s.
 *
 * Converts engine-generated navigation target strings and filter JSON into type-safe
 * navigation actions that the UI layer can consume.
 *
 * ## Fallback Behavior
 * When an unknown or invalid navigation target is encountered, the resolver gracefully
 * falls back to [NavigationAction.ToTransactionList] with an empty filter, ensuring
 * users can always access the transaction list even if the recommendation engine
 * produces unexpected navigation targets.
 */
interface NavigationTargetResolver {
    fun resolve(target: String, filterJson: String?): NavigationAction
    fun canHandle(target: String): Boolean
}

sealed class NavigationAction {
    data class ToTransactionList(val filter: TransactionFilter) : NavigationAction()
    data class ToBudgetDetail(val category: String) : NavigationAction()
    data class ToAnalytics(val period: String) : NavigationAction()
    data class ToMap(val location: String?) : NavigationAction()
    data object NoOp : NavigationAction()
}

@Singleton
class NavigationTargetResolverImpl @Inject constructor(
    private val filterSerializer: TransactionFilterSerializer
) : NavigationTargetResolver {

    override fun canHandle(target: String): Boolean {
        return when (target.trim().uppercase()) {
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL,
            DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL,
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            "MAP" -> true
            else -> false
        }
    }

    override fun resolve(target: String, filterJson: String?): NavigationAction {
        val normalized = target.trim().uppercase()
        val parsedFilter = parseFilterOrDefault(filterJson)

        return when (normalized) {
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL -> {
                NavigationAction.ToTransactionList(parsedFilter)
            }
            DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL -> {
                val category = parsedFilter.categoryId?.toString() ?: "GENERAL"
                NavigationAction.ToBudgetDetail(category)
            }
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS -> {
                NavigationAction.ToAnalytics(period = derivePeriod(parsedFilter))
            }
            "MAP" -> {
                NavigationAction.ToMap(location = parsedFilter.merchantName)
            }
            else -> {
                // Graceful fallback: open transactions unfiltered.
                // This ensures users can always navigate somewhere useful even if
                // the recommendation engine produces an unknown navigation target.
                NavigationAction.ToTransactionList(TransactionFilter())
            }
        }
    }

    private fun parseFilterOrDefault(filterJson: String?): TransactionFilter {
        if (filterJson.isNullOrBlank()) return TransactionFilter()
        return filterSerializer.deserialize(filterJson) ?: TransactionFilter()
    }

    private fun derivePeriod(filter: TransactionFilter): String {
        val range = filter.dateRange ?: return "month"
        val spanDays = ((range.second - range.first).coerceAtLeast(0L)) / (24L * 60 * 60 * 1000)
        return when {
            spanDays <= 8 -> "week"
            spanDays <= 32 -> "month"
            else -> "custom"
        }
    }
}
