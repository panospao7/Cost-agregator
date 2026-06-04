package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PR2: Verifies that [AnalyticsState.moneyCurrentTotal] does not crash when
 * [AnalyticsState.homeCurrency] is null, blank, or invalid, and that the new
 * [AnalyticsState.moneyCurrentTotalOrNull] correctly returns null in those cases.
 */
class AnalyticsStateMoneySafetyTest {

    @Test
    fun analyticsState_loading_moneyCurrentTotalDoesNotThrow() {
        val state = AnalyticsState(isLoading = true, homeCurrency = null)

        // moneyCurrentTotalOrNull must be null when homeCurrency is null
        assertNull(state.moneyCurrentTotalOrNull)

        // moneyCurrentTotal must NOT throw — falls back to EUR
        val total = state.moneyCurrentTotal
        assertEquals(CurrencyCode.EUR, total.currency)
        assertEquals(0.0, total.amount, 0.0)
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
}
