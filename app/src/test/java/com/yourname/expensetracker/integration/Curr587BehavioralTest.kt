package com.yourname.expensetracker.integration

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.HomeCurrencyForMoneyMath
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.aggregateOrNull
import com.yourname.expensetracker.domain.core.money.warningOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CURR-587-10: Behavioral tests proving currency-normalization invariants.
 *
 * Only the cases that exercise production helpers/defaults remain here. Pure data-class
 * constructor echoes were removed, and the StaleRatePolicy selection pins moved to
 * domain.core.money.StaleRatePolicyTest.
 */
class Curr587BehavioralTest {

    @Test
    fun `MoneyAggregateResult_Available_wraps_MoneyAggregate`() {
        val aggregate = MoneyAggregate.empty(CurrencyCode("EUR"), RateBasis.TRANSACTION_DATE)
        val result = MoneyAggregateResult.Available(aggregate)
        assertNotNull(result.aggregate)
        assertEquals("EUR", result.aggregate.displayCurrency.code)
    }

    @Test
    fun `MoneyAggregateResult_warningOrNull_returns_reason_for_unavailable`() {
        val result = MoneyAggregateResult.Unavailable(
            reason = "test reason",
            requestedRateBasis = RateBasis.TRANSACTION_DATE
        )
        assertEquals("test reason", result.warningOrNull())
    }

    @Test
    fun `HomeCurrencyForMoneyMath_Available_defaults_firstRunDefault_to_false`() {
        val result = HomeCurrencyForMoneyMath.Available(CurrencyCode("EUR"))
        assertEquals("EUR", result.currency.code)
        assertFalse(result.firstRunDefault)
    }

    @Test
    fun `no_CurrencyCode_XXX_unavailable_result_has_no_aggregate`() {
        // G-MONEY-11 model level: the typed Unavailable result replaces any fake XXX currency,
        // so it can never expose an aggregate (and therefore never a display currency).
        val unavailable = MoneyAggregateResult.Unavailable(
            reason = "test",
            requestedRateBasis = RateBasis.TRANSACTION_DATE
        )
        assertNull(unavailable.aggregateOrNull())
    }
}
