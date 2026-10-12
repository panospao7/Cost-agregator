package com.yourname.expensetracker.domain.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyAggregateBuilderNormalizedRowsTest {

    @Test
    fun `already normalized rows are aggregated without a second conversion`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows = listOf(
                NormalizedMoneyRow(
                    amount = 10.0,
                    normalizedCurrency = CurrencyCode.EUR,
                    sourceCurrency = CurrencyCode.USD,
                    rateValidDate = 100L
                ),
                NormalizedMoneyRow(
                    amount = 5.0,
                    normalizedCurrency = CurrencyCode.EUR,
                    sourceCurrency = CurrencyCode.EUR,
                    rateValidDate = 101L
                )
            ),
            targetCurrency = CurrencyCode.EUR,
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE
        )

        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertThat(aggregate.displayAmount).isEqualTo(15.0)
        assertThat(aggregate.displayCurrency).isEqualTo(CurrencyCode.EUR)
        assertThat(aggregate.requestedRateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(aggregate.actualRateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(aggregate.metadata.sourceCurrencyCodes)
            .containsExactly(CurrencyCode.USD, CurrencyCode.EUR)
        assertThat(aggregate.metadata.rateValidDates).containsExactly(100L, 101L)
    }

    @Test
    fun `normalized currency mismatch is unavailable rather than converted`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows = listOf(
                NormalizedMoneyRow(10.0, CurrencyCode.USD, CurrencyCode.EUR)
            ),
            targetCurrency = CurrencyCode.EUR
        )

        val unavailable = result as MoneyAggregateResult.Unavailable
        assertThat(unavailable.reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name)
        assertThat(unavailable.metadata.invalidCurrencyCount).isEqualTo(1)
    }

    @Test
    fun `slice quality preserves local exclusions and partial status`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows = listOf(NormalizedMoneyRow(10.0, CurrencyCode.EUR)),
            targetCurrency = CurrencyCode.EUR,
            quality = NormalizedMoneyQuality(
                excludedTransactionCount = 1,
                missingRateCount = 1,
                conversionQuality = ConversionQuality.PARTIAL
            ),
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE
        )

        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertThat(aggregate.displayAmount).isEqualTo(10.0)
        assertThat(aggregate.isPartial).isTrue()
        assertThat(aggregate.conversionQuality).isEqualTo(ConversionQuality.PARTIAL)
        assertThat(aggregate.metadata.includedTransactionCount).isEqualTo(1)
        assertThat(aggregate.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(aggregate.metadata.missingRateCount).isEqualTo(1)
    }

    @Test
    fun `all excluded input is unavailable instead of a fake zero total`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows = emptyList(),
            targetCurrency = CurrencyCode.EUR,
            quality = NormalizedMoneyQuality(
                excludedTransactionCount = 2,
                conversionQuality = ConversionQuality.PARTIAL
            )
        )

        val unavailable = result as MoneyAggregateResult.Unavailable
        assertThat(unavailable.reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name)
        assertThat(unavailable.metadata.excludedTransactionCount).isEqualTo(2)
    }

    @Test
    fun `a bucket cannot masquerade as one normalized transaction`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            listOf(NormalizedMoneyRow(50.0, CurrencyCode.EUR, transactionCount = 5)), CurrencyCode.EUR
        )
        assertThat((result as MoneyAggregateResult.Unavailable).reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name)
    }

    @Test
    fun `duplicate transaction identity is unavailable rather than double counted`() {
        val row = NormalizedMoneyRow(10.0, CurrencyCode.EUR, transactionId = 12L)
        val result = MoneyAggregateBuilder.fromNormalizedRows(listOf(row, row), CurrencyCode.EUR)
        assertThat((result as MoneyAggregateResult.Unavailable).reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name)
    }

    @Test
    fun `empty input without exclusions remains an available empty aggregate`() {
        val result = MoneyAggregateBuilder.fromNormalizedRows(emptyList(), CurrencyCode.EUR)

        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertThat(aggregate.displayAmount).isEqualTo(0.0)
        assertThat(aggregate.sourceBuckets).isEmpty()
        assertThat(aggregate.metadata.includedTransactionCount).isEqualTo(0)
    }
}
