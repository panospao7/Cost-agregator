package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import org.junit.Test

class NormalizedAnalyticsMoneyAdapterTest {

    @Test
    fun `included stale metadata is not counted twice when supplied quality already includes it`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            listOf(expense(1L, 10.0, "USD", "EUR").copy(qualityWarning = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE)),
            emptyList(), "EUR", AnalyticsDataQuality(isPartial = true, staleRateCount = 1)
        )
        assertThat((result as MoneyAggregateResult.Available).aggregate.metadata.staleRateCount).isEqualTo(1)
    }

    @Test
    fun `analytics adapter requires slice local exclusion counts`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            includedExpenses = listOf(expense(1L, 10.0, "EUR", "EUR")),
            excludedExpenses = listOf(excluded(2L)),
            homeCurrency = "EUR",
            dataQuality = AnalyticsDataQuality(excludedCount = 6)
        )

        val unavailable = result as MoneyAggregateResult.Unavailable
        assertThat(unavailable.reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name)
    }

    @Test
    fun `analytics adapter carries normalized provenance and rate basis`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            includedExpenses = listOf(expense(1L, 10.0, "USD", "EUR", 123L)),
            excludedExpenses = listOf(excluded(2L)),
            homeCurrency = "EUR",
            dataQuality = AnalyticsDataQuality(
                isPartial = true,
                excludedCount = 1,
                missingRateCount = 1
            ),
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE
        )

        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertThat(aggregate.displayAmount).isEqualTo(10.0)
        assertThat(aggregate.conversionQuality).isEqualTo(ConversionQuality.PARTIAL)
        assertThat(aggregate.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(aggregate.metadata.missingRateCount).isEqualTo(1)
        assertThat(aggregate.metadata.sourceCurrencyCodes)
            .containsExactly(CurrencyCode.USD)
        assertThat(aggregate.metadata.rateValidDates).containsExactly(123L)
    }

    @Test
    fun `included stale-rate rows remain partial and retain stale metadata`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            includedExpenses = listOf(
                expense(1L, 10.0, "USD", "EUR").copy(
                    qualityWarning = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
                )
            ),
            excludedExpenses = emptyList(),
            homeCurrency = "EUR",
            dataQuality = AnalyticsDataQuality()
        )

        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertThat(aggregate.conversionQuality).isEqualTo(ConversionQuality.PARTIAL)
        assertThat(aggregate.isPartial).isTrue()
        assertThat(aggregate.metadata.staleRateCount).isEqualTo(1)
    }

    @Test
    fun `analytics adapter rejects normalized rows whose currency disagrees with home currency`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            includedExpenses = listOf(expense(1L, 10.0, "USD", "USD")),
            excludedExpenses = emptyList(),
            homeCurrency = "EUR",
            dataQuality = AnalyticsDataQuality()
        )

        val unavailable = result as MoneyAggregateResult.Unavailable
        assertThat(unavailable.reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name)
    }

    @Test
    fun `a requested display currency cannot relabel an empty normalized input`() {
        val input = NormalizedAnalyticsInput(homeCurrency = "EUR")
        val result = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            input, emptyList(), displayCurrency = "USD"
        )
        assertThat((result as MoneyAggregateResult.Unavailable).reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name)
    }

    @Test
    fun `known date does not prove category or merchant exclusion ownership`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR", excludedExpenses = listOf(excluded(2L).copy(date = 123L))
        )
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSelectedSlice(
            input, listOf(expense(1L, 10.0, "EUR", "EUR")), includeExcluded = { true }
        )
        assertThat(result).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
    }

    @Test
    fun `an identity cannot be both included and excluded`() {
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSlice(
            listOf(expense(2L, 10.0, "EUR", "EUR")), listOf(excluded(2L)), "EUR",
            AnalyticsDataQuality(excludedCount = 1)
        )
        assertThat((result as MoneyAggregateResult.Unavailable).reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name)
    }

    @Test
    fun `dimension predicates cannot discard unknown dimension exclusions`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            excludedExpenses = listOf(excluded(1L).copy(date = 1_700_000_000_000L))
        )
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSelectedSlice(
            input, emptyList(), includeExcluded = { false }
        )
        assertThat(result).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
    }

    @Test
    fun `temporal predicates cannot discard exclusions with unknown dates`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR", excludedExpenses = listOf(excluded(2L))
        )
        val result = NormalizedAnalyticsMoneyAdapter.aggregateSelectedSlice(
            input, listOf(expense(1L, 10.0, "EUR", "EUR")),
            includeExcluded = { false }, exclusionAttribution = ExclusionAttribution.TEMPORAL
        )

        assertThat(result).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat((result as MoneyAggregateResult.Unavailable).metadata.excludedTransactionCount).isEqualTo(1)
    }

    @Test
    fun `valid empty input stays available but all excluded input does not become zero`() {
        val empty = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            NormalizedAnalyticsInput(homeCurrency = "EUR"), emptyList()
        )
        val unavailable = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            NormalizedAnalyticsInput(homeCurrency = "EUR", excludedExpenses = listOf(excluded(2L))), emptyList()
        )

        assertThat((empty as MoneyAggregateResult.Available).aggregate.displayAmount).isEqualTo(0.0)
        assertThat(unavailable).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
    }

    private fun expense(
        id: Long,
        amount: Double,
        originalCurrency: String,
        normalizedCurrency: String,
        rateValidDate: Long? = null
    ) = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = originalCurrency,
        normalizedAmount = amount,
        normalizedCurrency = normalizedCurrency,
        date = 1_700_000_000_000L,
        merchant = "Merchant $id",
        merchantKey = null,
        categoryId = null,
        categoryNameSnapshot = null,
        transactionType = "PURCHASE",
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = "TEST",
        rateBasis = RateBasis.TRANSACTION_DATE.name,
        rateUsed = null,
        rateValidDate = rateValidDate,
        rateLastUpdated = null,
        rateSource = null,
        conversionPath = null,
        qualityWarning = null
    )

    private fun excluded(id: Long) = ExcludedExpense(
        id = id,
        originalAmount = 5.0,
        originalCurrency = "USD",
        reason = ExclusionReason.CONVERSION_FAILED
    )
}
