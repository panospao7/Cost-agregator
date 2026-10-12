package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.analytics.ExcludedExpense
import com.yourname.expensetracker.domain.analytics.ExclusionReason
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsMoneyAdapter
import com.yourname.expensetracker.ui.model.LoadableUiState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PR2: Verifies that [AnalyticsState.moneyCurrentTotalOrNull] returns null
 * for unavailable currency and that the deprecated scalar helper fails closed.
 */
class AnalyticsStateMoneySafetyTest {

    @Test
    fun analyticsState_loading_moneyCurrentTotalFailsClosed() {
        val state = AnalyticsState(isLoading = true, homeCurrency = null)

        assertNull(state.moneyCurrentTotalOrNull)
        assertFailsWith<MoneyDisplayUnavailableException> {
            state.moneyCurrentTotal
        }
    }

    @Test
    fun analyticsState_nullHomeCurrency_moneyCurrentTotalIsNull() {
        val state = AnalyticsState(isLoading = true, homeCurrency = null)
        assertNull(state.moneyCurrentTotalOrNull)
    }

    @Test
    fun analyticsState_blankHomeCurrency_moneyCurrentTotalIsNull() {
        val state = AnalyticsState(isLoading = true, homeCurrency = "")
        assertNull(state.moneyCurrentTotalOrNull)
    }

    @Test
    fun analyticsState_invalidHomeCurrency_moneyCurrentTotalIsNull() {
        val state = AnalyticsState(isLoading = true, homeCurrency = "INVALID")
        assertNull(state.moneyCurrentTotalOrNull)
    }

    @Test
    fun analyticsState_validHomeCurrency_returnsMoneyAmount() {
        val state = AnalyticsState(
            isLoading = false,
            homeCurrency = "EUR",
            currentTotal = 100.0
        )
        val result = state.moneyCurrentTotalOrNull
        assertNotNull(result)
        assertEquals(100.0, result.amount, 0.0)
        assertEquals(CurrencyCode.EUR, result.currency)
    }

    @Test
    fun `unavailable typed money never exposes a fallback scalar`() {
        val state = AnalyticsState(
            isLoading = false, homeCurrency = "EUR", currentTotal = 999.0,
            currentTotalMoneyResult = MoneyAggregateResult.Unavailable("DISPLAY_CONVERSION_UNAVAILABLE", RateBasis.TRANSACTION_DATE)
        )

        assertNull(state.moneyCurrentTotalOrNull)
        assertTrue(state.loadableState is LoadableUiState.Error)
        assertFailsWith<MoneyDisplayUnavailableException> { state.moneyCurrentTotal }
    }

    @Test
    fun `available typed money is authoritative over a stale scalar`() {
        val state = AnalyticsState(
            isLoading = false, homeCurrency = "EUR", currentTotal = 999.0,
            currentTotalMoneyResult = MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(10.0, CurrencyCode.EUR))
        )

        assertEquals(10.0, assertNotNull(state.moneyCurrentTotalOrNull).amount)
        assertTrue(state.loadableState is LoadableUiState.Data<*>)
    }

    @Test
    fun `available aggregate cannot be relabeled using the state currency`() {
        val state = AnalyticsState(
            isLoading = false, homeCurrency = "EUR",
            currentTotalMoneyResult = MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(10.0, CurrencyCode.USD))
        )

        assertNull(state.moneyCurrentTotalOrNull)
        assertTrue(state.loadableState is LoadableUiState.Error)
    }

    @Test
    fun `loading and invalid legacy scalars do not expose money`() {
        assertNull(AnalyticsState(isLoading = true, homeCurrency = "EUR", currentTotal = 10.0).moneyCurrentTotalOrNull)
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(AnalyticsState(isLoading = false, homeCurrency = "EUR", currentTotal = amount).moneyCurrentTotalOrNull)
        }
    }

    @Test
    fun `all excluded normalized input renders unavailable rather than empty spending`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            excludedExpenses = listOf(ExcludedExpense(1L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED))
        )
        val state = AnalyticsState(
            isLoading = false, homeCurrency = "EUR",
            currentTotalMoneyResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(input, emptyList())
        )

        assertNull(state.moneyCurrentTotalOrNull)
        assertTrue(state.loadableState is LoadableUiState.Error)
    }

    @Test
    fun `valid empty normalized input remains available zero money`() {
        val input = NormalizedAnalyticsInput(homeCurrency = "EUR")
        val state = AnalyticsState(
            isLoading = false, homeCurrency = "EUR",
            currentTotalMoneyResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(input, emptyList())
        )

        assertEquals(0.0, assertNotNull(state.moneyCurrentTotalOrNull).amount)
        assertTrue(state.loadableState is LoadableUiState.Data<*>)
    }
}
