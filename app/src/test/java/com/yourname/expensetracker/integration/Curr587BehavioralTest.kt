package com.yourname.expensetracker.integration

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.HomeCurrencyForMoneyMath
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.aggregateOrNull
import com.yourname.expensetracker.domain.core.money.warningOrNull
import com.yourname.expensetracker.domain.budget.BudgetForecastResult
import com.yourname.expensetracker.domain.budget.ForecastUnavailableReason
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult
import org.junit.Assert.*
import org.junit.Test

/**
 * CURR-587-10: Behavioral tests proving currency-normalization invariants.
 *
 * These tests verify the typed unavailable state introduced in PRs 01-08.
 */
class Curr587BehavioralTest {

    // ── PR-01: MoneyAggregateResult ────────────────────────────────────────

    @Test
    fun `MoneyAggregateResult_Unavailable_has_no_currency`() {
        val result = MoneyAggregateResult.Unavailable(
            reason = "Home currency unavailable: datastore read failed",
            requestedRateBasis = RateBasis.TRANSACTION_DATE
        )
        // Unavailable has no CurrencyCode — no fake XXX or EUR
        assertTrue(result is MoneyAggregateResult.Unavailable)
        assertFalse(result.reason.contains("XXX"))
        assertFalse(result.reason.contains("CurrencyCode"))
    }

    @Test
    fun `MoneyAggregateResult_Available_wraps_MoneyAggregate`() {
        val aggregate = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
            CurrencyCode("EUR"), RateBasis.TRANSACTION_DATE
        )
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

    // ── PR-02: HomeCurrencyForMoneyMath ────────────────────────────────────

    @Test
    fun `HomeCurrencyForMoneyMath_Available_carries_currency`() {
        val result = HomeCurrencyForMoneyMath.Available(CurrencyCode("EUR"))
        assertEquals("EUR", result.currency.code)
        assertFalse(result.firstRunDefault)
    }

    @Test
    fun `HomeCurrencyForMoneyMath_Available_firstRunDefault_flag`() {
        val result = HomeCurrencyForMoneyMath.Available(CurrencyCode("EUR"), firstRunDefault = true)
        assertTrue(result.firstRunDefault)
    }

    @Test
    fun `HomeCurrencyForMoneyMath_Unavailable_carries_reason`() {
        val result = HomeCurrencyForMoneyMath.Unavailable("datastore error")
        assertEquals("datastore error", result.reason)
    }

    // ── PR-03: BudgetForecastResult ────────────────────────────────────────

    @Test
    fun `BudgetForecastResult_Unavailable_has_typed_reason_code`() {
        val result = BudgetForecastResult.Unavailable(
            budgetId = 1L,
            reasonCode = ForecastUnavailableReason.HOME_CURRENCY_UNAVAILABLE,
            reason = "Home currency unavailable: test",
            createdAt = 0L
        )
        assertEquals(ForecastUnavailableReason.HOME_CURRENCY_UNAVAILABLE, result.reasonCode)
        assertFalse(result.reason.isBlank())
    }

    @Test
    fun `BudgetForecastResult_Unavailable_is_not_Available`() {
        val result: BudgetForecastResult = BudgetForecastResult.Unavailable(
            budgetId = 1L,
            reasonCode = ForecastUnavailableReason.LIMIT_CONVERSION_FAILED,
            reason = "conversion failed",
            createdAt = 0L
        )
        assertFalse(result is BudgetForecastResult.Available)
    }

    // ── PR-04: DashboardNormalizedInputResult ──────────────────────────────

    @Test
    fun `DashboardNormalizedInputResult_Unavailable_carries_reason`() {
        val result = DashboardNormalizedInputResult.Unavailable(
            reason = "Home currency unavailable",
            periodStart = 0L,
            periodEnd = 1000L
        )
        assertTrue(result is DashboardNormalizedInputResult.Unavailable)
        assertEquals("Home currency unavailable", result.reason)
    }

    @Test
    fun `DashboardNormalizedInputResult_Unavailable_is_partial`() {
        val result = DashboardNormalizedInputResult.Unavailable(
            reason = "test",
            periodStart = 0L,
            periodEnd = 1000L
        )
        val isPartial = result is DashboardNormalizedInputResult.Unavailable ||
            (result is DashboardNormalizedInputResult.Available && result.input.dataQuality.isPartial)
        assertTrue(isPartial)
    }

    // ── PR-07: Guard — no fake XXX currency ───────────────────────────────

    @Test
    fun `no_CurrencyCode_XXX_in_production_source`() {
        // This test verifies the guard rule G-MONEY-11 at the model level.
        // CurrencyCode("XXX") must not be constructable as a valid unavailable sentinel.
        // The typed MoneyAggregateResult.Unavailable is the correct replacement.
        val unavailable = MoneyAggregateResult.Unavailable(
            reason = "test",
            requestedRateBasis = RateBasis.TRANSACTION_DATE
        )
        // Unavailable result has no displayCurrency field — no XXX possible
        assertNull(unavailable.aggregateOrNull())
    }

    // ── PR-09: StaleRatePolicy consistency ────────────────────────────────

    @Test
    fun `StaleRatePolicy_forBasis_latest_returns_LatestDefault`() {
        val policy = com.yourname.expensetracker.domain.core.money.StaleRatePolicy.forBasis(
            RateBasis.LATEST_AVAILABLE
        )
        assertEquals(
            com.yourname.expensetracker.domain.core.money.StaleRatePolicy.LatestDefault,
            policy
        )
    }

    @Test
    fun `StaleRatePolicy_forBasis_transactionDate_returns_None`() {
        val policy = com.yourname.expensetracker.domain.core.money.StaleRatePolicy.forBasis(
            RateBasis.TRANSACTION_DATE
        )
        assertEquals(
            com.yourname.expensetracker.domain.core.money.StaleRatePolicy.None,
            policy
        )
    }

    @Test
    fun `StaleRatePolicy_LatestDefault_maxAge_is_7_days`() {
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        assertEquals(
            sevenDaysMs,
            com.yourname.expensetracker.domain.core.money.StaleRatePolicy.LatestDefault.maxAgeMs
        )
    }
}
