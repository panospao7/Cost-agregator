package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Behavioral tests for invalid currency handling in the normalization pipeline.
 *
 * Verifies that invalid currency codes produce partial failures (not crashes)
 * throughout the entire pipeline: [MoneyNormalizationEngine],
 * [MoneyAggregateBuilder], and [MoneyMappers].
 */
class InvalidCurrencyBehavioralTest {

    private lateinit var store: FakeExchangeRateStore
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var converter: CurrencyConverter

    private val NOW = 1716163200000L // 2024-05-20 00:00 UTC
    private val DAY_MS = 86400000L

    @Before
    fun setup() {
        store = FakeExchangeRateStore()
        timeProvider = FakeTimeProvider(NOW)
        converter = CurrencyConverter(store, timeProvider)
    }

    // --- MoneyNormalizationEngine: invalid currency scenarios ---

    @Test
    fun `normalizeExpense with numeric currency returns excluded with INVALID_CURRENCY`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expense = fakeExpense(1, 100.0, "123", NOW)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue("Expected Excluded for numeric currency", result is NormalizationResult.Excluded)
        val excluded = result as NormalizationResult.Excluded
        assertEquals(FailureReason.INVALID_CURRENCY, excluded.failure.reason)
    }

    @Test
    fun `normalizeExpense with short currency returns excluded with INVALID_CURRENCY`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expense = fakeExpense(1, 100.0, "AB", NOW)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue("Expected Excluded for short currency", result is NormalizationResult.Excluded)
        val excluded = result as NormalizationResult.Excluded
        assertEquals(FailureReason.INVALID_CURRENCY, excluded.failure.reason)
    }

    @Test
    fun `normalizeExpense with currency containing digits returns excluded`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expense = fakeExpense(1, 100.0, "AB1", NOW)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue("Expected Excluded for currency with digits", result is NormalizationResult.Excluded)
        val excluded = result as NormalizationResult.Excluded
        assertEquals(FailureReason.INVALID_CURRENCY, excluded.failure.reason)
    }

    @Test
    fun `normalizeExpense with valid currency still succeeds`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expense = fakeExpense(1, 100.0, "USD", NOW)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue("Expected Included for valid currency", result is NormalizationResult.Included)
    }

    // --- MoneyNormalizationEngine.aggregateExpenses ---

    @Test
    fun `aggregateExpenses skips invalid currency expenses and marks partial`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "USD", NOW),
            fakeExpense(2, 50.0, "123", NOW),
            fakeExpense(3, 25.0, "", NOW)     // blank currency
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue(aggregate.isPartial)
        assertEquals(2, aggregate.conversionFailures.size)
        assertEquals(92.0, aggregate.displayAmount, 0.01)
        assertEquals(
            2,
            aggregate.conversionFailures.count { it.reason == FailureReason.INVALID_CURRENCY }
        )
    }

    @Test
    fun `aggregateExpenses all invalid currencies returns UNAVAILABLE`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "XYZ!", NOW),
            fakeExpense(2, 50.0, "AB", NOW),
            fakeExpense(3, 25.0, "12345", NOW)
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertEquals(ConversionQuality.UNAVAILABLE, aggregate.conversionQuality)
        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(3, aggregate.conversionFailures.size)
    }

    @Test
    fun `aggregateExpenses invalid currency metadata counted correctly`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "USD", NOW),
            fakeExpense(2, 50.0, "INVALID", NOW),
            fakeExpense(3, 30.0, "USD", NOW)
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertNotNull(aggregate.metadata)
        assertEquals(1, aggregate.metadata!!.invalidCurrencyCount)
        assertEquals(0, aggregate.metadata!!.missingRateCount)
        assertEquals(2, aggregate.metadata!!.includedTransactionCount)
        assertEquals(1, aggregate.metadata!!.excludedTransactionCount)
    }

    // --- MoneyAggregateBuilder.fromBuckets (legacy) ---

    @Test
    fun `legacy fromBuckets handles invalid currency without crash`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val buckets = listOf(
            100.0 to "USD",
            50.0 to "XYZ",   // invalid — would previously crash
            25.0 to "AB"     // invalid — too short
        )
        val aggregate = MoneyAggregateBuilder.fromBuckets(
            buckets = buckets,
            homeCurrency = "EUR",
            converter = converter
        )

        // Should not crash; USD should convert, invalid currencies handled gracefully
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.sourceBuckets.size)
    }

    @Test
    fun `legacy fromBuckets all invalid currencies returns aggregate`() = runTest {
        val buckets = listOf(
            100.0 to "123",
            50.0 to "AB"
        )
        val aggregate = MoneyAggregateBuilder.fromBuckets(
            buckets = buckets,
            homeCurrency = "EUR",
            converter = converter
        )

        assertTrue(aggregate.isPartial)
        assertEquals(0.0, aggregate.displayAmount, 0.0)
    }

    // --- MoneyMappers ---

    @Test
    fun `toConversionFailure handles invalid originalCurrency`() {
        val oldFailure = com.yourname.expensetracker.domain.currency.FailedConversion(
            originalAmount = 100.0,
            originalCurrency = "INVALID",
            targetCurrency = "EUR",
            reason = "missing rate",
            failureType = "MISSING_RATE"
        )
        // Should not crash
        val failure = oldFailure.toConversionFailure()
        assertEquals(FailureReason.MISSING_RATE, failure.reason)
        // Currency should be EUR (fallback)
        assertEquals("EUR", failure.originalAmount.currency.code)
    }

    @Test
    fun `toConversionFailure handles invalid targetCurrency`() {
        val oldFailure = com.yourname.expensetracker.domain.currency.FailedConversion(
            originalAmount = 100.0,
            originalCurrency = "USD",
            targetCurrency = "XYZ",
            reason = "missing rate",
            failureType = "MISSING_RATE"
        )
        // Should not crash
        val failure = oldFailure.toConversionFailure()
        // targetCurrency should fall back to EUR
        assertEquals("EUR", failure.targetCurrency.code)
    }

    @Test
    fun `toMoneyAggregate handles invalid currencies in failed conversions`() {
        val aggregate = com.yourname.expensetracker.domain.currency.MultiConversionAggregate(
            total = 0.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                com.yourname.expensetracker.domain.currency.FailedConversion(
                    originalAmount = 50.0,
                    originalCurrency = "INVALID",
                    targetCurrency = "EUR",
                    reason = "missing rate",
                    failureType = "MISSING_RATE"
                )
            )
        )
        // Should not crash
        val result = aggregate.toMoneyAggregate()
        assertEquals(1, result.conversionFailures.size)
        assertEquals("EUR", result.conversionFailures[0].originalAmount.currency.code)
    }

    // --- Helpers ---

    private fun fakeExpense(id: Long, amount: Double, currency: String, date: Long) =
        com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Test",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = date
        )
}

// --- Test doubles ---

private class FakeExchangeRateStore : ExchangeRateStore {
    val rates = mutableMapOf<String, DomainExchangeRate>()
    val ratesAsOf = mutableMapOf<String, DomainExchangeRate>()
    var latestByValidDate = false

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        if (latestByValidDate) {
            val candidates = rates.values.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            return candidates.maxByOrNull { it.validDate ?: 0L }
        }
        return rates["${fromCurrency}_${toCurrency}"]
    }

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        val candidates = rates.values.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
        return candidates.maxByOrNull { it.validDate ?: 0L }
            ?: rates["${fromCurrency}_${toCurrency}"]
            ?: rates["${fromCurrency}_${toCurrency}_fresh"]
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        return ratesAsOf["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: rates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
