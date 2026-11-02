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
import java.util.Locale

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

    @Test
    fun normalizedPeriodTotalsProduceAsciiDayKeysUnderArabicLocale() {
        val previousLocale = Locale.getDefault()
        val arabicLocale = Locale.forLanguageTag("ar-EG")
        val day1 = Instant.parse("2026-01-10T12:00:00Z").toEpochMilli()
        val day2 = Instant.parse("2026-01-11T12:00:00Z").toEpochMilli()
        val input = NormalizedAnalyticsInput(
            homeCurrency = CurrencyCode.EUR.code,
            period = PeriodRange(PeriodKind.CUSTOM, day1, day2 + 86_400_000L),
            includedExpenses = listOf(
                normalizedExpense(day1, 15.0, 1L),
                normalizedExpense(day2, 25.0, 2L)
            )
        )

        val rootTotals: List<com.yourname.expensetracker.domain.model.PeriodTotal>
        val arabicTotals: List<com.yourname.expensetracker.domain.model.PeriodTotal>
        try {
            Locale.setDefault(Locale.ROOT)
            rootTotals = engine.computeFromNormalized(input)

            Locale.setDefault(arabicLocale)
            arabicTotals = engine.computeFromNormalized(input)
        } finally {
            Locale.setDefault(previousLocale)
        }

        val asciiDayKeyRegex = Regex("^[0-9]{8}$")
        assertThat(arabicTotals).hasSize(2)
        arabicTotals.forEach { period ->
            assertThat(asciiDayKeyRegex.matches(period.periodKey)).isTrue()
            assertThat(period.periodKey.none { it.isDigit() && it !in '0'..'9' }).isTrue()
        }
        assertThat(arabicTotals.map { it.periodKey }).isEqualTo(rootTotals.map { it.periodKey })
        assertThat(arabicTotals.map { it.totalAmount }).containsExactly(15.0, 25.0).inOrder()
        assertThat(arabicTotals.map { it.totalAmount }).isEqualTo(rootTotals.map { it.totalAmount })
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
