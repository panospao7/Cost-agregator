package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayOfWeekAnalyzerNormalizedMoneyTest {

    private val analyzer = DayOfWeekAnalyzer()

    @Test
    fun `normalized weekday totals preserve target currency and counts`() {
        val monday = date("2026-03-02")
        val tuesday = date("2026-03-03")
        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = listOf(
                expense(1L, 100.0, "EUR", monday),
                expense(2L, 50.0, "USD", tuesday)
            )
        )

        val result = analyzer.analyze(monday - 1_000L, tuesday + 86_400_000L, input)
        val mondayInsight = result.first { it.dayIndex == 0 }
        val tuesdayInsight = result.first { it.dayIndex == 1 }

        assertThat(mondayInsight.totalSpent).isEqualTo(100.0)
        assertThat(tuesdayInsight.totalSpent).isEqualTo(50.0)
        assertThat(mondayInsight.transactionCount).isEqualTo(1)
        assertThat(tuesdayInsight.transactionCount).isEqualTo(1)
        assertThat(mondayInsight.moneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat(tuesdayInsight.moneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
    }

    @Test
    fun `normalized weekday totals fail closed for an unknown-date exclusion`() {
        val monday = date("2026-03-02")
        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 100.0, "EUR", monday)),
            excludedExpenses = listOf(
                ExcludedExpense(
                    id = 2L,
                    originalAmount = 10.0,
                    originalCurrency = "USD",
                    reason = ExclusionReason.CONVERSION_FAILED
                )
            )
        )

        val result = analyzer.analyze(monday - 1_000L, monday + 86_400_000L, input)

        assertThat(result.all { it.moneyResult is MoneyAggregateResult.Unavailable }).isTrue()
        assertThat(result.first { it.dayIndex == 0 }.totalSpent).isNull()
    }

    @Test
    fun `empty weekday remains an available zero total`() {
        val monday = date("2026-03-02")
        val result = analyzer.analyze(monday, monday + 86_400_000L, NormalizedAnalyticsInput(homeCurrency = "EUR"))

        val mondayResult = result.first { it.dayIndex == 0 }
        assertThat(mondayResult.moneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat(mondayResult.totalSpent).isEqualTo(0.0)
    }

    @Test
    fun `dated exclusions affect only their weekday and historical duplicates are counted once`() {
        val monday = date("2026-03-02")
        val tuesday = date("2026-03-03")
        val current = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 10.0, "EUR", monday)),
            excludedExpenses = listOf(ExcludedExpense(2L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED, monday))
        )
        val historical = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(expense(1L, 90.0, "EUR", monday), expense(2L, 90.0, "EUR", monday), expense(3L, 20.0, "USD", tuesday))
        )

        val result = analyzer.analyze(monday - 1_000L, tuesday + 1_000L, current, historical)
        val mondayResult = (result[0].moneyResult as MoneyAggregateResult.Available).aggregate
        val tuesdayResult = (result[1].moneyResult as MoneyAggregateResult.Available).aggregate

        assertThat(mondayResult.displayAmount).isEqualTo(10.0)
        assertThat(mondayResult.metadata.includedTransactionCount).isEqualTo(1)
        assertThat(mondayResult.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(mondayResult.isPartial).isTrue()
        assertThat(tuesdayResult.displayAmount).isEqualTo(20.0)
        assertThat(tuesdayResult.metadata.excludedTransactionCount).isEqualTo(0)
        assertThat(tuesdayResult.isPartial).isFalse()
    }

    @Test
    fun `current inclusion replaces a historical exclusion and currency mismatch stays unavailable`() {
        val monday = date("2026-03-02")
        val current = NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 10.0, "USD", monday)))
        val historical = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            excludedExpenses = listOf(ExcludedExpense(1L, 90.0, "USD", ExclusionReason.CONVERSION_FAILED, monday))
        )

        val result = analyzer.analyze(monday - 1_000L, monday + 1_000L, current, historical)
        val mismatch = analyzer.analyze(monday - 1_000L, monday + 1_000L, current, displayCurrency = "GBP")

        assertThat((result[0].moneyResult as MoneyAggregateResult.Available).aggregate.isPartial).isFalse()
        assertThat(result[0].totalSpent).isEqualTo(10.0)
        assertThat(mismatch[0].moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
    }

    private fun date(day: String): Long = LocalDate.parse(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun expense(id: Long, amount: Double, sourceCurrency: String, date: Long) =
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
            categoryId = null,
            categoryNameSnapshot = null,
            transactionType = "PURCHASE",
            isNotMine = false,
            isSharedExpense = false,
            ownershipMode = null,
            source = "TEST"
        )
}
