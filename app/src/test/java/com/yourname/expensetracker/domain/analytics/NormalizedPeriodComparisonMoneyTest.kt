package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.time.Instant

class NormalizedPeriodComparisonMoneyTest {

    private val comparisonCalculator = MonthlyComparisonCalculator()
    private val timeProvider = mockk<TimeProvider>()
    private val paceCalculator = SpendingPaceCalculator(timeProvider)

    @Test
    fun `monthly comparison retains typed current and previous aggregates`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(1L, 12.0, "USD", 100L, currentMonth.startMs + 1_000L))
        )
        val previousInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(2L, 8.0, "GBP", 90L, previousMonth.startMs + 1_000L))
        )

        val result = comparisonCalculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentInput = currentInput,
            previousInput = previousInput
        )

        val current = result.currentMoneyResult as MoneyAggregateResult.Available
        val previous = result.previousMoneyResult as MoneyAggregateResult.Available
        assertThat(result.currentTotal).isEqualTo(12.0)
        assertThat(result.previousTotal).isEqualTo(8.0)
        assertThat(result.changeAmount).isEqualTo(4.0)
        assertThat(result.currentCount).isEqualTo(1)
        assertThat(result.previousCount).isEqualTo(1)
        assertThat(current.aggregate.displayCurrency).isEqualTo(CurrencyCode.EUR)
        assertThat(current.aggregate.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.USD)
        assertThat(current.aggregate.metadata.rateValidDates).containsExactly(100L)
        assertThat(previous.aggregate.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.GBP)
        assertThat(previous.aggregate.metadata.rateValidDates).containsExactly(90L)
    }

    @Test
    fun `monthly comparison keeps exclusions local to their period`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(1L, 12.0, "EUR", 100L, currentMonth.startMs + 1_000L)),
            excludedExpenses = listOf(
                ExcludedExpense(
                    id = 3L,
                    originalAmount = 4.0,
                    originalCurrency = "USD",
                    reason = ExclusionReason.CONVERSION_FAILED,
                    date = currentMonth.startMs + 2_000L,
                    warningType = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
                )
            ),
            dataQuality = AnalyticsDataQuality(isPartial = true, excludedCount = 1, missingRateCount = 1)
        )
        val previousInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(2L, 8.0, "EUR", 90L, previousMonth.startMs + 1_000L))
        )

        val result = comparisonCalculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentInput = currentInput,
            previousInput = previousInput
        )

        val current = result.currentMoneyResult as MoneyAggregateResult.Available
        val previous = result.previousMoneyResult as MoneyAggregateResult.Available
        assertThat(current.aggregate.isPartial).isTrue()
        assertThat(current.aggregate.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(previous.aggregate.isPartial).isFalse()
        assertThat(previous.aggregate.metadata.excludedTransactionCount).isEqualTo(0)
    }

    @Test
    fun `unavailable current month does not publish a change amount`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(1L, 12.0, "UNKNOWN", 100L, currentMonth.startMs + 1_000L))
        )
        val previousInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(2L, 8.0, "EUR", 90L, previousMonth.startMs + 1_000L))
        )

        val result = comparisonCalculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentInput = currentInput,
            previousInput = previousInput
        )

        assertThat(result.currentMoneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(result.currentTotal).isNull()
        assertThat(result.changeAmount).isNull()
        assertThat(result.changePercentage).isNull()
    }

    @Test
    fun `empty available current month remains a valid zero total`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val result = comparisonCalculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentInput = NormalizedAnalyticsInput(
                period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
                homeCurrency = CurrencyCode.EUR.code
            ),
            previousInput = NormalizedAnalyticsInput(
                period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
                homeCurrency = CurrencyCode.EUR.code,
                includedExpenses = listOf(expense(2L, 8.0, "EUR", 90L, previousMonth.startMs + 1_000L))
            )
        )

        assertThat(result.currentMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat(result.currentTotal).isEqualTo(0.0)
        assertThat(result.changeAmount).isEqualTo(-8.0)
    }

    @Test
    fun `normalized pace retains typed results and canonical baseline`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val referenceNow = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        every { timeProvider.now() } returns referenceNow
        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(1L, 150.0, "USD", 100L, currentMonth.startMs + 1_000L))
        )
        val previousInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(2L, 280.0, "USD", 90L, previousMonth.startMs + 1_000L))
        )

        val result = paceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            currentInput = currentInput,
            previousInput = previousInput,
            referenceNowMs = referenceNow
        )

        assertThat(result.currentMonthSpent).isEqualTo(150.0)
        assertThat(result.previousMonthTotal).isEqualTo(280.0)
        assertThat(result.pacePercentage).isEqualTo(100.0f)
        assertThat(result.paceStatus).isEqualTo(PaceStatus.ON_PACE)
        assertThat(result.currentMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        assertThat(result.previousMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
    }

    @Test
    fun `unavailable normalized pace cannot publish a baseline`() {
        val currentMonth = month(2026, 3)
        val previousMonth = month(2026, 2)
        val referenceNow = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        every { timeProvider.now() } returns referenceNow
        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, currentMonth.startMs, currentMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(1L, 150.0, "UNKNOWN", 100L, currentMonth.startMs + 1_000L))
        )
        val previousInput = NormalizedAnalyticsInput(
            period = PeriodRange(PeriodKind.CUSTOM, previousMonth.startMs, previousMonth.endMs),
            homeCurrency = CurrencyCode.EUR.code,
            includedExpenses = listOf(expense(2L, 280.0, "EUR", 90L, previousMonth.startMs + 1_000L))
        )

        val result = paceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            currentInput = currentInput,
            previousInput = previousInput,
            referenceNowMs = referenceNow
        )

        assertThat(result.currentMoneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(result.previousMonthTotal).isNull()
        assertThat(result.paceStatus).isEqualTo(PaceStatus.NO_BASELINE)
        assertThat(result.currentMonthSpent).isNull()
        assertThat(result.projectedTotal).isNull()
        assertThat(result.pacePercentage).isEqualTo(-1.0f)
    }

    private fun month(year: Int, month: Int): MonthPeriod {
        val range = TimePeriodUtils.getMonthRange(year, month)
        val start = range.first
        val end = range.second
        return MonthPeriod(year, month - 1, start, end)
    }

    private fun expense(
        id: Long,
        amount: Double,
        sourceCurrency: String,
        rateValidDate: Long,
        date: Long
    ) = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = sourceCurrency,
        normalizedAmount = amount,
        normalizedCurrency = CurrencyCode.EUR.code,
        date = date,
        merchant = "Merchant $id",
        merchantKey = null,
        categoryId = null,
        categoryNameSnapshot = null,
        transactionType = DomainTransactionType.PURCHASE.name,
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = "TEST",
        rateBasis = RateBasis.TRANSACTION_DATE.name,
        rateUsed = 1.0,
        rateValidDate = rateValidDate,
        rateLastUpdated = null,
        rateSource = "TEST",
        conversionPath = "TEST"
    )
}
