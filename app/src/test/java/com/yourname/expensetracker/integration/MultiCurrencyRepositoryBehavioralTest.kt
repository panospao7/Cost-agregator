package com.yourname.expensetracker.integration

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.*
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * PR 9 — Behavioral tests for MultiCurrencyRepository.
 * Proves historical vs latest API behavior and partial quality propagation.
 *
 * Uses MoneyNormalizationEngine directly (the repository delegates to it)
 * to avoid needing a full ExpenseDao implementation.
 */
class MultiCurrencyRepositoryBehavioralTest {

    private lateinit var store: SentinelStore
    private lateinit var converter: CurrencyConverter
    private lateinit var engine: MoneyNormalizationEngine

    private val JAN_1 = 1704067200000L
    private val FEB_1 = 1706745600000L
    private val NOW = 1716163200000L

    @Before
    fun setup() {
        store = SentinelStore()
        val time = object : TimeProvider { override fun now() = NOW }
        converter = CurrencyConverter(store, time)
        engine = MoneyNormalizationEngine(converter)
    }

    @Test
    fun `historical aggregation uses per-expense transaction dates`() = runTest {
        val expenses = listOf(
            expense(1, 100.0, "USD", JAN_1),
            expense(2, 200.0, "USD", FEB_1),
            expense(3, 50.0, "EUR", JAN_1)
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        // Expense 1: 100 USD * 1.0 (Jan rate) = 100 EUR
        // Expense 2: 200 USD * 2.0 (Feb rate) = 400 EUR
        // Expense 3: 50 EUR (identity) = 50 EUR
        // Total = 550 EUR
        assertEquals(550.0, agg.displayAmount, 0.01)
        assertEquals(RateBasis.TRANSACTION_DATE, agg.rateBasis)
    }

    @Test
    fun `historical aggregation does not use latest rate`() = runTest {
        val expenses = listOf(
            expense(1, 100.0, "USD", JAN_1),
            expense(2, 200.0, "USD", FEB_1)
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        // If latest rate (10.0) were used: 100*10 + 200*10 = 3000
        assertNotEquals(3000.0, agg.displayAmount, 1.0)
        // Actual: 100*1.0 + 200*2.0 = 500
        assertEquals(500.0, agg.displayAmount, 0.01)
    }

    @Test
    fun `historical aggregation with missing rate marks partial`() = runTest {
        val expenses = listOf(
            expense(1, 100.0, "USD", JAN_1),
            expense(2, 50.0, "GBP", JAN_1) // no GBP rate
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertTrue(agg.isPartial)
        assertEquals(ConversionQuality.PARTIAL, agg.conversionQuality)
        assertTrue(agg.metadata.excludedTransactionCount > 0)
    }

    @Test
    fun `category aggregates use correct per-expense rates`() = runTest {
        val expenses = listOf(
            expense(1, 100.0, "USD", JAN_1, categoryId = 1L),
            expense(2, 200.0, "USD", FEB_1, categoryId = 2L)
        )
        val byCategory = expenses.groupBy { it.categoryId }
        val result = byCategory.mapValues { (_, group) ->
            engine.aggregateExpenses(group, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        }

        assertEquals(100.0, result[1L]?.displayAmount ?: 0.0, 0.01) // 100 * 1.0
        assertEquals(400.0, result[2L]?.displayAmount ?: 0.0, 0.01) // 200 * 2.0
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun expense(id: Long, amount: Double, currency: String, date: Long, categoryId: Long? = null) = Expense(
        id = id, amount = amount, currency = currency, merchant = "Test",
        transactionType = TransactionType.PURCHASE, date = date, categoryId = categoryId
    )
}

// ── Sentinel rate store ───────────────────────────────────────────────────────

private class SentinelStore : ExchangeRateStore {
    private val JAN_1 = 1704067200000L
    private val FEB_1 = 1706745600000L
    private val NOW = 1716163200000L

    override suspend fun getLatestRateForPair(from: String, to: String) = when {
        from == "USD" && to == "EUR" -> DomainExchangeRate("USD", "EUR", 10.0, NOW, "api", NOW)
        else -> null
    }

    override suspend fun getRateAsOf(from: String, to: String, atMillis: Long) = when {
        from == "USD" && to == "EUR" && atMillis >= FEB_1 -> DomainExchangeRate("USD", "EUR", 2.0, NOW, "api", FEB_1)
        from == "USD" && to == "EUR" && atMillis >= JAN_1 -> DomainExchangeRate("USD", "EUR", 1.0, NOW, "api", JAN_1)
        else -> null
    }

    override suspend fun getRate(from: String, to: String) = getLatestRateForPair(from, to)
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {}
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {}
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = null
    override suspend fun deleteOldRates(olderThan: Long) {}
}
