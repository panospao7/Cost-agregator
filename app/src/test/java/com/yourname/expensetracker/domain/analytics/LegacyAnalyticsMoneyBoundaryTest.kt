package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("DEPRECATION")
class LegacyAnalyticsMoneyBoundaryTest {
    private val month = MonthPeriod(2026, 2, 1_772_323_200_000L, 1_774_915_200_000L)
    private val expenses = listOf(snapshot(1L, "EUR"), snapshot(2L, "USD"))
    private val timeProvider = mockk<TimeProvider>().also { every { it.now() } returns month.startMs + 86_400_000L }

    @Test
    fun `all legacy calculation entry points reject mixed source currencies`() {
        val calls = listOf<() -> Unit>(
            { CategoryInsightEngine().calculate(month, null, emptyMap(), expenses, "EUR"); Unit },
            { DayOfWeekAnalyzer().analyze(month.startMs, month.endMs, expenses, "EUR"); Unit },
            { MonthlyComparisonCalculator().calculate(month, null, expenses, "EUR"); Unit },
            { SpendingPaceCalculator(timeProvider).calculate(month.startMs, month.startMs - 86_400_000L, month.startMs, expenses, "EUR"); Unit }
        )
        for (call in calls) {
            val failure = assertThrows(MoneyDisplayUnavailableException::class.java) { call() }
            assertThat(failure.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        }
    }

    @Test
    fun `omitted legacy currency is inferred only from one supported source currency`() {
        val comparison = MonthlyComparisonCalculator().calculate(month, null, listOf(snapshot(1L, "GBP")))
        assertThat(comparison.displayCurrency).isEqualTo("GBP")
        assertThat(comparison.currentTotal).isEqualTo(10.0)
        assertThrows(MoneyDisplayUnavailableException::class.java) {
            MonthlyComparisonCalculator().calculate(month, null, expenses)
        }
        assertThrows(MoneyDisplayUnavailableException::class.java) {
            MonthlyComparisonCalculator().calculate(month, null, emptyList<ExpenseSnapshot>())
        }
    }

    @Test
    fun `a target override cannot relabel a homogeneous source dataset`() {
        assertThrows(MoneyDisplayUnavailableException::class.java) {
            SingleCurrencyAnalyticsInput.from(listOf(snapshot(1L, "GBP")), "EUR")
        }
    }

    private fun snapshot(id: Long, currency: String) = ExpenseSnapshot(
        id, 10.0, 10.0, currency, "Merchant", null, DomainTransactionType.PURCHASE,
        month.startMs + id * 1_000L, null, false, null, null
    )
}
