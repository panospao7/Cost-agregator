package com.yourname.expensetracker.scenarios

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure domain tests for [MoneyAggregateBuilder] — no Room database needed.
 *
 * Verifies that [MoneyAggregateBuilder.fromBuckets] correctly:
 * - Returns home-currency empty aggregate for empty input
 * - Skips conversion for single home-currency bucket
 * - Converts single non-home currency bucket to home
 * - Converts all convertible buckets in mixed input
 * - Maps stale rates to [FailureReason.RATE_STALE]
 * - Maps missing rates to [FailureReason.MISSING_RATE]
 * - Warns about 'currency bucket' not 'transaction'
 * - Preserves transaction counts in sourceBuckets
 */
class MoneyAggregateBuilderTest {

    private val mockConverter: CurrencyConverter = mockk()

    @Test
    fun `empty buckets returns home currency empty aggregate`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = emptyList(),
            homeCurrency = "EUR",
            converter = mockConverter
        )

        assertEquals("Display amount should be 0.0", 0.0, result.displayAmount, 0.001)
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, result.displayCurrency)
        assertTrue("Source buckets should be empty", result.sourceBuckets.isEmpty())
        assertFalse("Empty aggregate should not be partial", result.isPartial)
        assertNull("Empty aggregate should have no warning", result.warningMessage)
    }

    @Test
    fun `single home currency bucket returns no conversion`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(150.0 to "EUR"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(3)
        )

        assertEquals("Display amount should be 150.0", 150.0, result.displayAmount, 0.001)
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, result.displayCurrency)
        assertEquals("Should have 1 source bucket", 1, result.sourceBuckets.size)
        assertEquals("Bucket transaction count should be 3", 3, result.sourceBuckets.first().transactionCount)
        assertFalse("Should not be partial", result.isPartial)
        assertTrue("Should have no conversion failures", result.conversionFailures.isEmpty())
        assertNull("Should have no warning message", result.warningMessage)
    }

    @Test
    fun `single non-home currency bucket converts to home`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(100.0 to "USD"), "EUR")
        } returns MultiConversionAggregate(
            total = 92.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(2)
        )

        assertEquals("Display amount should be 92.0 (converted)", 92.0, result.displayAmount, 0.001)
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, result.displayCurrency)
        assertEquals("Should have 1 source bucket", 1, result.sourceBuckets.size)
        assertEquals("Source bucket currency should be USD", CurrencyCode("USD"), result.sourceBuckets.first().currency)
        assertEquals("Source bucket amount should be 100.0", 100.0, result.sourceBuckets.first().amount, 0.001)
        assertEquals("Bucket transaction count should be 2", 2, result.sourceBuckets.first().transactionCount)
        assertFalse("Should not be partial", result.isPartial)
        assertTrue("Should have no conversion failures", result.conversionFailures.isEmpty())
    }

    @Test
    fun `mixed buckets convert all convertible buckets`() = runTest {
        coEvery {
            mockConverter.convertMultiple(
                listOf(100.0 to "EUR", 50.0 to "USD", 75.0 to "GBP"),
                "EUR"
            )
        } returns MultiConversionAggregate(
            total = 215.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "EUR", 50.0 to "USD", 75.0 to "GBP"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1, 2, 3)
        )

        assertEquals("Display amount should be 215.0", 215.0, result.displayAmount, 0.001)
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, result.displayCurrency)
        assertEquals("Should have 3 source buckets", 3, result.sourceBuckets.size)

        val eurBucket = result.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket amount should be 100.0", 100.0, eurBucket.amount, 0.001)
        assertEquals("EUR bucket transaction count should be 1", 1, eurBucket.transactionCount)

        val usdBucket = result.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket amount should be 50.0", 50.0, usdBucket.amount, 0.001)
        assertEquals("USD bucket transaction count should be 2", 2, usdBucket.transactionCount)

        val gbpBucket = result.sourceBuckets.single { it.currency.code == "GBP" }
        assertEquals("GBP bucket amount should be 75.0", 75.0, gbpBucket.amount, 0.001)
        assertEquals("GBP bucket transaction count should be 3", 3, gbpBucket.transactionCount)

        assertFalse("Should not be partial", result.isPartial)
        assertNull("Should have no warning", result.warningMessage)
    }

    @Test
    fun `stale rate maps to FailureReason RATE_STALE`() = runTest {
        coEvery {
            mockConverter.convertMultiple(
                listOf(100.0 to "USD"),
                "EUR"
            )
        } returns MultiConversionAggregate(
            total = 0.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 100.0,
                    originalCurrency = "USD",
                    targetCurrency = "EUR",
                    reason = "Stale exchange rate from USD to EUR",
                    failureType = FailedConversion.STALE_RATE
                )
            )
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1)
        )

        assertTrue("Should be partial", result.isPartial)
        assertEquals("Should have 1 conversion failure", 1, result.conversionFailures.size)
        val failure = result.conversionFailures.first()
        assertEquals("Failure reason should be RATE_STALE", FailureReason.RATE_STALE, failure.reason)
        assertEquals("Original currency should be USD", CurrencyCode("USD"), failure.originalAmount.currency)
        assertEquals("Original amount should be 100.0", 100.0, failure.originalAmount.amount, 0.001)
        assertEquals("Target currency should be EUR", CurrencyCode.EUR, failure.targetCurrency)
    }

    @Test
    fun `missing rate maps to FailureReason MISSING_RATE`() = runTest {
        coEvery {
            mockConverter.convertMultiple(
                listOf(100.0 to "USD"),
                "EUR"
            )
        } returns MultiConversionAggregate(
            total = 0.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 100.0,
                    originalCurrency = "USD",
                    targetCurrency = "EUR",
                    reason = "Missing exchange rate from USD to EUR",
                    failureType = FailedConversion.MISSING_RATE
                )
            )
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1)
        )

        assertTrue("Should be partial", result.isPartial)
        assertEquals("Should have 1 conversion failure", 1, result.conversionFailures.size)
        val failure = result.conversionFailures.first()
        assertEquals("Failure reason should be MISSING_RATE", FailureReason.MISSING_RATE, failure.reason)
    }

    @Test
    fun `warning message says currency bucket not transaction`() = runTest {
        coEvery {
            mockConverter.convertMultiple(
                listOf(100.0 to "USD", 50.0 to "GBP"),
                "EUR"
            )
        } returns MultiConversionAggregate(
            total = 0.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 100.0,
                    originalCurrency = "USD",
                    targetCurrency = "EUR",
                    reason = "Missing exchange rate from USD to EUR",
                    failureType = FailedConversion.MISSING_RATE
                ),
                FailedConversion(
                    originalAmount = 50.0,
                    originalCurrency = "GBP",
                    targetCurrency = "EUR",
                    reason = "Missing exchange rate from GBP to EUR",
                    failureType = FailedConversion.MISSING_RATE
                )
            )
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD", 50.0 to "GBP"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1, 2)
        )

        // Warning message should mention transaction count AND currency bucket count
        assertNotNull("Warning message should be present", result.warningMessage)
        assertTrue(
            "Warning message should contain 'transaction(s)'",
            result.warningMessage!!.contains("transaction(s)")
        )
        assertTrue(
            "Warning message should contain 'currency bucket(s)'",
            result.warningMessage!!.contains("currency bucket(s)")
        )
        assertTrue(
            "Warning message should contain failed transaction count",
            result.warningMessage!!.contains("3 transaction(s)")
        )
    }

    @Test
    fun `transaction counts preserved in sourceBuckets`() = runTest {
        coEvery {
            mockConverter.convertMultiple(
                listOf(200.0 to "EUR", 100.0 to "USD"),
                "EUR"
            )
        } returns MultiConversionAggregate(
            total = 292.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(200.0 to "EUR", 100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(5, 3)
        )

        assertEquals("Should have 2 source buckets", 2, result.sourceBuckets.size)

        val eurBucket = result.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket transaction count should be 5", 5, eurBucket.transactionCount)

        val usdBucket = result.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket transaction count should be 3", 3, usdBucket.transactionCount)

        assertEquals(
            "Total transaction count should be 8",
            8, result.totalTransactionCount
        )
    }
}
