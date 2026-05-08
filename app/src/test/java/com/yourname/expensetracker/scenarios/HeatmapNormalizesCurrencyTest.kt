package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DB-backed scenario tests verifying that [AnalyticsCurrencyNormalizer]
 * correctly handles multi-currency expenses for heatmap / analytics pipelines.
 *
 * Expenses are seeded into an in-memory database via the DAO, then normalized
 * through the same pipeline used by production analytics code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeatmapNormalizesCurrencyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var exchangeRateStore: FakeExchangeRateStore
    private lateinit var normalizer: AnalyticsCurrencyNormalizer

    private val homeCurrency = "EUR"
    private val baseTime = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        exchangeRateStore = FakeExchangeRateStore()
        normalizer = AnalyticsCurrencyNormalizer(
            CurrencyConverter(exchangeRateStore, timeProvider = mockk(relaxed = true))
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** Insert a single expense directly into the DB and return its generated ID. */
    private suspend fun insertExpense(
        amount: Double,
        currency: String,
        merchant: String,
        date: Long = baseTime
    ): Long {
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = date
        )
        return db.expenseDao().insert(expense)
    }

    // ── Test 1: Converted expenses included ──────────────────────────────────

    @Test
    fun `converted expenses included in normalized heatmap`() = runTest {
        // GIVEN: EUR expense + USD expense (with a rate available)
        exchangeRateStore.putRate("USD", "EUR", rate = 0.85, updatedAt = baseTime)

        insertExpense(amount = 50.0, currency = "EUR", merchant = "Carrefour")
        insertExpense(amount = 100.0, currency = "USD", merchant = "Amazon")

        val allExpenses = db.expenseDao().getAll()

        // WHEN: normalizing to home currency
        val result = normalizer.normalizeExpenses(
            expenses = allExpenses,
            homeCurrencyCode = homeCurrency
        )

        // THEN: both expenses are included
        assertEquals(
            "Both EUR and USD expenses should be included after normalization",
            2, result.includedExpenses.size
        )
        assertEquals(
            "USD expense should be converted to EUR",
            85.0, result.includedExpenses.first { it.merchant == "Amazon" }.effectiveAmount, 0.001
        )
        assertEquals(
            "EUR expense should keep its original amount",
            50.0, result.includedExpenses.first { it.merchant == "Carrefour" }.effectiveAmount, 0.001
        )
        assertEquals(
            "All included expenses should be in home currency",
            2, result.includedExpenses.count { it.currency == homeCurrency }
        )
        assertTrue("No warnings expected when all rates are available", result.warnings.isEmpty())
        assertEquals("No expenses should be excluded", 0, result.excludedCount)
    }

    // ── Test 2: Failed conversion expenses excluded ──────────────────────────

    @Test
    fun `failed conversion expenses excluded from normalized heatmap`() = runTest {
        // GIVEN: EUR expense + JPY expense (NO rate available)
        // Only EUR rate is available — JPY has no rate
        exchangeRateStore.putRate("USD", "EUR", rate = 0.85, updatedAt = baseTime)

        insertExpense(amount = 30.0, currency = "EUR", merchant = "Boulangerie")
        insertExpense(amount = 2000.0, currency = "JPY", merchant = "Uniqlo")

        val allExpenses = db.expenseDao().getAll()

        // WHEN: normalizing to home currency
        val result = normalizer.normalizeExpenses(
            expenses = allExpenses,
            homeCurrencyCode = homeCurrency
        )

        // THEN: only the EUR expense is included; JPY is excluded
        assertEquals(
            "Only EUR expense should be included; JPY lacks a rate",
            1, result.includedExpenses.size
        )
        assertEquals(
            "Included expense should be the EUR one",
            "Boulangerie", result.includedExpenses.single().merchant
        )
        assertEquals(
            "One expense should be excluded",
            1, result.excludedCount
        )
        assertTrue(
            "A warning for missing exchange rate should be present",
            result.warnings.any { it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE }
        )
    }

    // ── Test 3: Home-currency expenses included directly ─────────────────────

    @Test
    fun `home currency expenses included directly`() = runTest {
        // GIVEN: Only EUR expenses (all in home currency)
        insertExpense(amount = 25.0, currency = "EUR", merchant = "Starbucks")
        insertExpense(amount = 12.50, currency = "EUR", merchant = "McDonald's")
        insertExpense(amount = 60.0, currency = "EUR", merchant = "Zara")

        val allExpenses = db.expenseDao().getAll()

        // WHEN: normalizing to home currency
        val result = normalizer.normalizeExpenses(
            expenses = allExpenses,
            homeCurrencyCode = homeCurrency
        )

        // THEN: all three are included without any conversion
        assertEquals(
            "All home-currency expenses should be included directly",
            3, result.includedExpenses.size
        )
        assertEquals(
            "No expenses should be excluded",
            0, result.excludedCount
        )
        assertTrue(
            "No conversion warnings when everything is in home currency",
            result.warnings.isEmpty()
        )
        assertTrue(
            "All included expenses should be in EUR",
            result.includedExpenses.all { it.currency == homeCurrency }
        )
    }

    // ── Fake ExchangeRateStore ───────────────────────────────────────────────

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

        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? =
            getRate(fromCurrency, toCurrency)

        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()

        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }

        override suspend fun deleteOldRates(olderThan: Long) {
            rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
        }
    }
}
