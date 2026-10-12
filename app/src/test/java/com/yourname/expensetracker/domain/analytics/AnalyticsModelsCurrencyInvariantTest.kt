package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalyticsModelsCurrencyInvariantTest {
    private fun period(previous: MoneyAmount?, daily: Map<String, MoneyAmount> = emptyMap()) = SpendingPeriod(
        "Month", 0L, 1L, MoneyAmount(100.0, CurrencyCode.EUR), previous,
        emptyList(), emptyList(), daily, 1
    )

    @Test
    fun `period comparison retains same currency percentage`() {
        assertEquals(100f, requireNotNull(period(MoneyAmount(50.0, CurrencyCode.EUR)).changePercent), 0f)
        assertNull(period(null).changePercent)
        assertNull(period(MoneyAmount.zero(CurrencyCode.EUR)).changePercent)
    }

    @Test
    fun `period refuses mixed currency comparisons and daily buckets`() {
        assertThrows(IllegalArgumentException::class.java) { period(MoneyAmount(50.0, CurrencyCode.USD)) }
        assertThrows(IllegalArgumentException::class.java) {
            period(null, mapOf("day" to MoneyAmount(10.0, CurrencyCode.USD)))
        }
    }

    @Test
    fun `monthly total refuses a different display currency`() {
        val month = MonthlyYearTotal(0, "Jan", MoneyAmount(50.0, CurrencyCode.EUR), 1, "EUR")
        assertEquals(CurrencyCode.EUR, month.moneyTotal.currency)
        assertThrows(IllegalArgumentException::class.java) { month.copy(displayCurrency = "USD") }
    }

    @Test
    fun `category breakdown refuses a different display currency`() {
        val category = AnalyticsCategoryBreakdown(
            AnalyticsCategoryRef(1L, "Food", "", ""), MoneyAmount(50.0, CurrencyCode.EUR), 1, 100f, "EUR"
        )
        assertThrows(IllegalArgumentException::class.java) { category.copy(displayCurrency = "USD") }
    }
}
