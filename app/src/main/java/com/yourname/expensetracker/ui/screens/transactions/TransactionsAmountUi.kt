package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.ReadHomeFailure

/**
 * Bounded, UI-facing summary of the read-time home currency for amount ordering (INV-9).
 * Carries no transaction content.
 */
enum class AmountOrderingState {
    /** Home currency read; amount sort, min/max and the unranked count are active. */
    AVAILABLE,

    /** Home currency not read yet; list is newest first, amount operations are paused. */
    HOME_CURRENCY_LOADING,

    /** Home currency could not be read; list is newest first, amount operations are paused. */
    HOME_CURRENCY_UNAVAILABLE
}

/**
 * One sealed state per surface for the Transactions amount-ordering notice (ux-flows section 4).
 * Count fields are integers only (INV-22); [ReadHomeFailure] is a bounded code, never raw text.
 */
sealed interface TransactionsAmountUi {

    /** Date sort, no amount bound: no amount semantics engaged, nothing extra to show. */
    data object Inactive : TransactionsAmountUi

    /** Home currency available, amount sort or bound engaged, count still being read. */
    data object CountPending : TransactionsAmountUi

    /**
     * Home currency available and the count is known.
     *
     * @param count rows without a value in [home].
     * @param home home currency the count was computed for.
     * @param hidesRows true when a min/max bound is set (those rows are hidden); false for a
     * sort only (those rows are listed last).
     */
    data class Ready(
        val count: Int,
        val home: CurrencyCode,
        val hidesRows: Boolean
    ) : TransactionsAmountUi

    /** The count query failed. Without [hidesRows] nothing is shown; with it a generic notice. */
    data class CountUnavailable(
        val home: CurrencyCode,
        val hidesRows: Boolean
    ) : TransactionsAmountUi

    /** [com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState.Loading]. */
    data object HomeLoading : TransactionsAmountUi

    /** [com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState.Unavailable]. */
    data class HomeUnavailable(val reason: ReadHomeFailure) : TransactionsAmountUi
}
