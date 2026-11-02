package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.forecasting.StressRecommendationAction
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

/**
 * Maps a typed stress-forecast recommendation action to an existing Home navigation callback
 * (stress-forecast-actions, INV-3/INV-4).
 *
 * Pure dispatcher: it takes the action only, never display text, so routing cannot depend on the
 * locale. The `when` is exhaustive without `else`, so adding an action fails compilation here.
 * [StressRecommendationAction.None] (informational rows) fires nothing.
 */
internal fun dispatchStressRecommendationAction(
    action: StressRecommendationAction,
    referenceNowMillis: Long,
    onOpenRecurring: () -> Unit,
    onOpenTransactions: (TransactionFilter) -> Unit,
    onOpenSavingsGoals: () -> Unit
) {
    when (action) {
        StressRecommendationAction.OpenRecurring -> onOpenRecurring()
        StressRecommendationAction.OpenTransactionsThisMonth -> onOpenTransactions(
            TransactionFilter(dateRange = TimePeriodUtils.getMonthRange(referenceNowMillis))
        )
        StressRecommendationAction.OpenSavingsGoals -> onOpenSavingsGoals()
        StressRecommendationAction.None -> Unit
    }
}
