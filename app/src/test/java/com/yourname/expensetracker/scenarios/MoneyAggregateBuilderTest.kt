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

    // ── NEW-P5-009: counts/buckets size-mismatch count integrity ──────────

    @Test
    fun `shorter counts list marks aggregate partial with countsIncomplete`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(100.0 to "USD", 50.0 to "GBP"), "EUR")
        } returns MultiConversionAggregate(
            total = 145.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD", 50.0 to "GBP"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1) // 1 count for 2 buckets — SHORTER
        )

        assertTrue("Size mismatch must mark the aggregate partial", result.isPartial)
        assertTrue(
            "Metadata must carry countsIncomplete for a shorter counts list",
            result.metadata.countsIncomplete
        )
        assertNotNull("Controlled warning must be present", result.warningMessage)
        assertTrue(
            "Warning must be the controlled count-integrity text",
            result.warningMessage!!.contains("Transaction counts incomplete")
        )
        // Missing count stays silently-defaulted 0 (never a negative sentinel),
        // but the incompletion is now visible via isPartial + metadata.
        assertEquals(1, result.totalTransactionCount)
        assertTrue("No negative sentinels", result.totalTransactionCount >= 0)
        // Converted amounts remain trustworthy — only counts are suspect.
        assertEquals(145.0, result.displayAmount, 0.001)
        assertTrue(result.conversionFailures.isEmpty())
    }

    @Test
    fun `longer counts list marks aggregate partial with countsIncomplete`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(100.0 to "USD", 50.0 to "GBP"), "EUR")
        } returns MultiConversionAggregate(
            total = 145.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD", 50.0 to "GBP"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1, 2, 3) // 3 counts for 2 buckets — LONGER
        )

        assertTrue("Longer counts list must also mark the aggregate partial", result.isPartial)
        assertTrue(
            "Metadata must carry countsIncomplete for a longer counts list",
            result.metadata.countsIncomplete
        )
        assertTrue(
            "Warning must be the controlled count-integrity text",
            result.warningMessage!!.contains("Transaction counts incomplete")
        )
        // Counts 1 + 2 land in buckets; the third count has no bucket and is
        // never folded into any total (no fabricated counts).
        assertEquals(3, result.totalTransactionCount)
        assertEquals(145.0, result.displayAmount, 0.001)
    }

    @Test
    fun `empty counts list stays count-agnostic and not partial`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(100.0 to "USD"), "EUR")
        } returns MultiConversionAggregate(
            total = 92.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter
            // transactionCounts omitted — the count-agnostic opt-out default
        )

        assertFalse(
            "Count-agnostic (empty) counts list is unknown-by-design, not damaged",
            result.isPartial
        )
        assertFalse("Empty counts list must NOT set countsIncomplete", result.metadata.countsIncomplete)
        assertNull("No warning for the count-agnostic path", result.warningMessage)
        assertEquals(92.0, result.displayAmount, 0.001)
    }

    @Test
    fun `complete counts list regression unchanged behavior`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(200.0 to "EUR", 100.0 to "USD"), "EUR")
        } returns MultiConversionAggregate(
            total = 292.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(200.0 to "EUR", 100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(5, 3) // matches buckets — COMPLETE
        )

        assertFalse("Complete lists must not be partial", result.isPartial)
        assertFalse(
            "Complete lists must leave countsIncomplete false",
            result.metadata.countsIncomplete
        )
        assertNull("Complete lists must have no warning", result.warningMessage)
        assertEquals(292.0, result.displayAmount, 0.001)
        assertEquals(8, result.totalTransactionCount)
    }

    @Test
    fun `count mismatch combines warning with conversion-failure warning`() = runTest {
        coEvery {
            mockConverter.convertMultiple(listOf(100.0 to "USD"), "EUR")
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

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(2, 7) // longer than buckets — mismatch
        )

        assertTrue(result.isPartial)
        assertTrue(result.metadata.countsIncomplete)
        assertNotNull(result.warningMessage)
        assertTrue(
            "Both count-integrity and conversion warnings must be present",
            result.warningMessage!!.contains("Transaction counts incomplete") &&
                result.warningMessage!!.contains("currency bucket(s)")
        )
        assertEquals(1, result.conversionFailures.size)
    }

    @Test
    fun `count mismatch on home-currency-only path still marks partial`() = runTest {
        // Home-currency-only input returns BEFORE conversion — the mismatch
        // flag must survive that early return. Negative wiring check: if the
        // converter were ever reached, this stub fails the test loudly.
        coEvery {
            mockConverter.convertMultiple(any(), any())
        } throws AssertionError("home-currency-only buckets must not reach the converter")

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(150.0 to "EUR"),
            homeCurrency = "EUR",
            converter = mockConverter,
            transactionCounts = listOf(1, 2) // longer than buckets — mismatch
        )

        assertEquals(150.0, result.displayAmount, 0.001)
        assertEquals(1, result.totalTransactionCount)
        assertTrue("Early-return path must still flag count incompletion", result.isPartial)
        assertTrue(result.metadata.countsIncomplete)
        assertTrue(
            result.warningMessage!!.contains("Transaction counts incomplete")
        )
    }
}
