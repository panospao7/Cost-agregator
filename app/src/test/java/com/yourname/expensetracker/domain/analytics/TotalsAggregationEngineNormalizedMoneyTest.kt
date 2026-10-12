package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.time.Instant

class TotalsAggregationEngineNormalizedMoneyTest {

    private val engine = TotalsAggregationEngine(
        expenseRepository = mockk<ExpenseRepository>(relaxed = true),
        timeProvider = mockk<TimeProvider>(relaxed = true),
        multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
        categoryRepository = mockk<CategoryRepository>(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun normalizedPeriodTotalsRetainTypedMoneyProvenance() {
        val day = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli()
        val input = NormalizedAnalyticsInput(
            homeCurrency = CurrencyCode.EUR.code,
            period = PeriodRange(PeriodKind.CUSTOM, day, day + 86_400_000L),
            includedExpenses = listOf(normalizedExpense(day))
        )

        val totals = engine.computeFromNormalized(input)

        assertThat(totals).hasSize(1)
        val result = totals.single().moneyResult as MoneyAggregateResult.Available
        assertThat(result.aggregate.displayAmount).isEqualTo(9.0)
        assertThat(result.aggregate.displayCurrency).isEqualTo(CurrencyCode.EUR)
        assertThat(result.aggregate.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.USD)
        assertThat(result.aggregate.metadata.rateValidDates).containsExactly(100L)
        assertThat(totals.single().totalAmount).isEqualTo(9.0)
        assertThat(totals.single().periodType).isEqualTo(PeriodType.DAY)
    }

    @Test
    fun normalizedSummaryRetainsTypedResultAndCounts() {
        val day = 1_767_916_800_000L
        val input = NormalizedAnalyticsInput(
            homeCurrency = CurrencyCode.EUR.code,
            period = PeriodRange(PeriodKind.CUSTOM, day, day + 2 * 86_400_000L),
            includedExpenses = listOf(
                normalizedExpense(day + 1_000L, 4.0, 1L),
                normalizedExpense(day + 86_400_000L + 1_000L, 6.0, 2L)
            )
        )

        val summary = engine.summarize(input)

        assertThat(summary.total?.amount).isEqualTo(10.0)
        assertThat(summary.count).isEqualTo(2)
        assertThat(summary.moneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat((summary.moneyResult as MoneyAggregateResult.Available).aggregate.totalTransactionCount)
            .isEqualTo(2)
    }

    @Test
    fun unavailableSummaryNeverPublishesZeroSpending() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            excludedExpenses = listOf(ExcludedExpense(1L, 5.0, "USD", ExclusionReason.CONVERSION_FAILED))
        )
        val summary = engine.summarize(input)
        assertThat(summary.moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(summary.total).isNull()
        assertThat(summary.avgPerDay).isNull()
        assertThat(summary.avgPerWeek).isNull()
    }

    private fun normalizedExpense(date: Long, amount: Double = 9.0, id: Long = 1L) = NormalizedExpense(
        id = id,
        originalAmount = 10.0,
        originalEffectiveAmount = 10.0,
        originalCurrency = CurrencyCode.USD.code,
        normalizedAmount = amount,
        normalizedCurrency = CurrencyCode.EUR.code,
        date = date,
        merchant = CurrencyCode.EUR.code,
        merchantKey = null,
        categoryId = null,
        categoryNameSnapshot = null,
        transactionType = DomainTransactionType.PURCHASE.name,
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = null,
        rateBasis = RateBasis.TRANSACTION_DATE.name,
        rateUsed = 0.9,
        rateValidDate = 100L,
        rateLastUpdated = null,
        rateSource = null,
        conversionPath = null
    )
}
