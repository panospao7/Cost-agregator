package com.yourname.expensetracker.domain.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAggregateBuilderSourceCurrencyTest {

    @Test
    fun `same source currency preserves value counts and identity provenance`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(12.5, 7.25), " usd ")
        assertTrue(result is MoneyAggregateResult.Available)
        val aggregate = (result as MoneyAggregateResult.Available).aggregate

        assertEquals(19.75, aggregate.displayAmount, 0.0)
        assertEquals(CurrencyCode.USD, aggregate.displayCurrency)
        assertEquals(listOf(MoneyBucket(CurrencyCode.USD, 19.75, 2)), aggregate.sourceBuckets)
        assertEquals(2, aggregate.metadata.includedTransactionCount)
        assertEquals(0, aggregate.metadata.excludedTransactionCount)
        assertEquals(RateBasis.IDENTITY, aggregate.requestedRateBasis)
        assertEquals(RateBasis.IDENTITY, aggregate.actualRateBasis)
        assertEquals(ConversionQuality.COMPLETE, aggregate.conversionQuality)
        assertFalse(aggregate.isPartial)
        assertTrue(aggregate.conversionFailures.isEmpty())
    }

    @Test
    fun `valid empty input is available but has no source bucket or included values`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(emptyList(), "JPY")
        assertTrue(result is MoneyAggregateResult.Available)
        val aggregate = (result as MoneyAggregateResult.Available).aggregate

        assertEquals(CurrencyCode.JPY, aggregate.displayCurrency)
        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(0, aggregate.metadata.includedTransactionCount)
        assertTrue(aggregate.sourceBuckets.isEmpty())
        assertEquals(RateBasis.IDENTITY, aggregate.actualRateBasis)
        assertFalse(aggregate.isPartial)
    }

    @Test
    fun `missing malformed and unsupported source currencies never become a default currency`() {
        for (currency in listOf(null, "", " ", "US", "123", "ZZZ")) {
            val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(1.0, 2.0), currency)
            assertTrue(result is MoneyAggregateResult.Unavailable)
            val unavailable = result as MoneyAggregateResult.Unavailable

            assertEquals(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name, unavailable.reason)
            assertEquals(0, unavailable.metadata.includedTransactionCount)
            assertEquals(2, unavailable.metadata.excludedTransactionCount)
            assertEquals(1, unavailable.metadata.invalidCurrencyCount)
            assertEquals(RateBasis.IDENTITY, unavailable.requestedRateBasis)
        }
    }

    @Test
    fun `empty input still requires a valid source currency`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(emptyList(), null)
        assertTrue(result is MoneyAggregateResult.Unavailable)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
            (result as MoneyAggregateResult.Unavailable).reason
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `home currency aggregation rejects an unsupported ISO code`() {
        MoneyAggregateBuilder.fromHomeCurrencyAmounts(listOf(1.0), "XXX")
    }

    @Test
    fun `non finite inputs make the entire aggregate unavailable not a successful partial total`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(1.0, invalid, 2.0), "EUR")
            assertTrue(result is MoneyAggregateResult.Unavailable)
            val unavailable = result as MoneyAggregateResult.Unavailable

            assertEquals(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name, unavailable.reason)
            assertEquals(0, unavailable.metadata.includedTransactionCount)
            assertEquals(3, unavailable.metadata.excludedTransactionCount)
        }
    }

    @Test
    fun `finite inputs that overflow return a controlled unavailable result`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(
            listOf(Double.MAX_VALUE, Double.MAX_VALUE), "EUR"
        )
        assertTrue(result is MoneyAggregateResult.Unavailable)
        val unavailable = result as MoneyAggregateResult.Unavailable

        assertEquals(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name, unavailable.reason)
        assertEquals(2, unavailable.metadata.excludedTransactionCount)
    }

    @Test
    fun `net zero preserves included values rather than pretending the input was empty`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(10.0, -10.0, 0.0), "GBP")
        val aggregate = (result as MoneyAggregateResult.Available).aggregate

        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(3, aggregate.metadata.includedTransactionCount)
        assertEquals(3, aggregate.totalTransactionCount)
        assertEquals(1, aggregate.sourceBuckets.size)
    }

    @Test
    fun `aggregation does not round source contributions before summing`() {
        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(0.004, 0.004), "EUR")
        val aggregate = (result as MoneyAggregateResult.Available).aggregate

        assertEquals(0.008, aggregate.displayAmount, 0.0)
    }

    @Test
    fun `input is enumerated once including when validation fails`() {
        var iterations = 0
        val amounts = Iterable {
            iterations++
            check(iterations == 1)
            listOf(1.0, Double.NaN, 2.0).iterator()
        }

        val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(amounts, "EUR")

        assertTrue(result is MoneyAggregateResult.Unavailable)
        assertEquals(1, iterations)
        assertEquals(3, (result as MoneyAggregateResult.Unavailable).metadata.excludedTransactionCount)
    }
}
