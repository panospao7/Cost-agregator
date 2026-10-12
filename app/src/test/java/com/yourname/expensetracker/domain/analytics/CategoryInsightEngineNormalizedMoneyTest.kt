package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import org.junit.Test

class CategoryInsightEngineNormalizedMoneyTest {

    private val engine = CategoryInsightEngine()

    @Test
    fun `normalized category totals use target currency and retain typed result`() {
        val month = MonthPeriod(2026, 2, 1_772_323_200_000L, 1_774_915_200_000L)
        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = listOf(
                expense(1L, 100.0, "EUR", 1L, month.startMs + 1_000L),
                expense(2L, 50.0, "USD", 2L, month.startMs + 2_000L)
            )
        )

        val result = engine.calculate(
            currentMonth = month,
            previousMonth = null,
            categoryMap = mapOf(
                1L to AnalyticsCategoryRef(1L, "Food", "", ""),
                2L to AnalyticsCategoryRef(2L, "Travel", "", "")
            ),
            currentInput = input
        )

        assertThat(result).hasSize(2)
        assertThat(result.first { it.category.id == 1L }.currentTotal).isEqualTo(100.0)
        assertThat(result.first { it.category.id == 2L }.currentTotal).isEqualTo(50.0)
        assertThat(result.sumOf { requireNotNull(it.percentageOfTotal).toDouble() }).isWithin(0.001).of(100.0)
        assertThat(result.all { it.moneyResult is MoneyAggregateResult.Available }).isTrue()
    }

    @Test
    fun `normalized category totals fail closed when month exclusion cannot be attributed`() {
        val month = MonthPeriod(2026, 2, 1_772_323_200_000L, 1_774_915_200_000L)
        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 100.0, "EUR", 1L, month.startMs + 1_000L)),
            excludedExpenses = listOf(
                ExcludedExpense(
                    id = 9L,
                    originalAmount = 25.0,
                    originalCurrency = "USD",
                    reason = ExclusionReason.CONVERSION_FAILED,
                    date = month.startMs + 2_000L
                )
            )
        )

        val result = engine.calculate(month, null, emptyMap(), input)

        assertThat(result).hasSize(1)
        assertThat(result.single().moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(result.single().currentTotal).isNull()
    }

    @Test
    fun `previous unavailable category money is not treated as zero spending`() {
        val previousMonth = MonthPeriod(2026, 1, 1_769_904_000_000L, 1_772_323_200_000L)
        val currentMonth = MonthPeriod(2026, 2, previousMonth.endMs, 1_774_915_200_000L)
        val current = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 10.0, "EUR", 1L, currentMonth.startMs + 1_000L))
        )
        val previous = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            excludedExpenses = listOf(ExcludedExpense(2L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED, previousMonth.startMs + 1_000L))
        )

        val result = engine.calculate(currentMonth, previousMonth, emptyMap(), current, previous).single()

        assertThat(result.previousMoneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(result.previousTotal).isNull()
        assertThat(result.changeFromPrevious).isNull()
    }

    @Test
    fun `out of month exclusions do not poison categories and target overrides do not relabel values`() {
        val month = MonthPeriod(2026, 2, 1_772_323_200_000L, 1_774_915_200_000L)
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 10.0, "USD", 1L, month.startMs + 1_000L)),
            excludedExpenses = listOf(ExcludedExpense(2L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED, month.endMs + 1_000L))
        )

        val available = engine.calculate(month, null, emptyMap(), input).single()
        val mismatch = engine.calculate(month, null, emptyMap(), input, displayCurrency = "GBP").single()

        assertThat(available.moneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat(available.currentTotal).isEqualTo(10.0)
        assertThat(mismatch.moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(mismatch.currentTotal).isNull()
    }

    private fun expense(id: Long, amount: Double, sourceCurrency: String, categoryId: Long, date: Long) =
        NormalizedExpense(
            id = id,
            originalAmount = amount,
            originalEffectiveAmount = amount,
            originalCurrency = sourceCurrency,
            normalizedAmount = amount,
            normalizedCurrency = "EUR",
            date = date,
            merchant = "Merchant $id",
            merchantKey = null,
            categoryId = categoryId,
            categoryNameSnapshot = null,
            transactionType = "PURCHASE",
            isNotMine = false,
            isSharedExpense = false,
            ownershipMode = null,
            source = "TEST"
        )
}
