package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class AnalyticsCurrencyNormalizerTest {

    private lateinit var exchangeRateStore: FakeExchangeRateStore
    private lateinit var normalizer: AnalyticsCurrencyNormalizer

    @Before
    fun setup() {
        exchangeRateStore = FakeExchangeRateStore()
        normalizer = AnalyticsCurrencyNormalizer(CurrencyConverter(exchangeRateStore))
    }

    @Test
    fun `normalizeExpenses keeps same-currency transactions without warnings`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 1L, amount = 25.0, currency = "EUR")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.homeCurrency).isEqualTo("EUR")
        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.includedExpenses.single().effectiveAmount).isEqualTo(25.0)
        assertThat(result.includedExpenses.single().currency).isEqualTo("EUR")
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `normalizeExpenses converts foreign currency into home currency`() = runTest {
        exchangeRateStore.putRate("USD", "EUR", rate = 2.0, updatedAt = 1234L)

        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 2L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.includedExpenses.single().effectiveAmount).isEqualTo(20.0)
        assertThat(result.includedExpenses.single().currency).isEqualTo("EUR")
        assertThat(result.latestRateTimestamp).isEqualTo(1234L)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `normalizeExpenses excludes invalid transaction currency with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 3L, amount = 10.0, currency = "???")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY)
        assertThat(result.warnings.single().affectedTransactionCount).isEqualTo(1)
    }

    @Test
    fun `normalizeExpenses rejects invalid home currency with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 4L, amount = 10.0, currency = "EUR")),
            homeCurrencyCode = "bad-home"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.INVALID_HOME_CURRENCY)
        assertThat(result.warnings.single().affectedTransactionCount).isEqualTo(1)
    }

    @Test
    fun `normalizeExpenses excludes missing-rate transactions with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 5L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE)
        assertThat(result.warnings.single().sourceCurrencies).containsExactly("USD")
    }

    private fun expense(id: Long, amount: Double, currency: String) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        rawNotificationId = null,
        categoryId = null,
        createdAt = 1_700_000_000_000L,
        paymentMethod = com.yourname.expensetracker.data.database.entity.PaymentMethod.UNKNOWN,
        isManualEntry = false,
        notes = null,
        dedupeKey = null,
        transferDirection = null,
        transferAccountName = null,
        isNotMine = false,
        ownerName = null,
        isSharedExpense = false,
        sharedWithName = null,
        mySharePercentage = null,
        myShareAmount = null
    )

    private class FakeExchangeRateStore : ExchangeRateStore {
        private val rates = mutableMapOf<Pair<String, String>, DomainExchangeRate>()

        fun putRate(from: String, to: String, rate: Double, updatedAt: Long) {
            rates[from.uppercase() to to.uppercase()] = DomainExchangeRate(
                fromCurrency = from.uppercase(),
                toCurrency = to.uppercase(),
                rate = rate,
                lastUpdated = updatedAt
            )
        }

        override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            return rates[fromCurrency.uppercase() to toCurrency.uppercase()]
        }

        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rates[rate.fromCurrency.uppercase() to rate.toCurrency.uppercase()] = rate
        }

        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
            rates.forEach { insertOrUpdate(it) }
        }

        override fun getAllRatesForBase(baseCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()

        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }

        override suspend fun deleteOldRates(olderThan: Long) {
            rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
        }
    }
}
