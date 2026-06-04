package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Multi-Currency Analytics Dashboard + Budget
 *
 * Proves that when a user has expenses in multiple currencies:
 * 1. Home-currency total correctly converts all known rates
 * 2. Missing rates produce isPartial=true with ConversionFailure
 * 3. Source buckets preserve original currency amounts
 * 4. Category totals sum to the same grand total
 * 5. Deposits are excluded from spending totals
 *
 * Uses REAL Room DB + REAL CurrencyConverter + REAL MultiCurrencyRepository.
 * Only CurrencySettingsRepository is mocked (returns fixed "EUR").
 */
class MulticurrencyAnalyticsDashboardBudgetGoldenTest : GoldenTestBase() {

    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var currencySettings: CurrencySettingsRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "multicurrency_analytics_dashboard_budget",
        numericTolerance = 0.01,
        sortArraysByField = "currency"
    )

    @Before
    override fun setUp() {
        super.setUp()

        currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }

        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `multicurrency purchase total with partial conversion`() = runTest {
        // ── SEED ──
        seedCategories()
        seedExchangeRates()
        seedExpenses()

        // ── ACT: Get purchase total in home currency ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L

        val total = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val categoryTotals = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(periodStart, periodEnd)

        // ── SERIALIZE to golden JSON ──
        val actual = serializeResult(total, categoryTotals)

        // ── VERIFY against golden file ──
        verifier.verify(actual).assertPassed()
    }

    // ── Seed data ──

    private suspend fun seedExchangeRates() {
        // USD→EUR = 0.90
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR",
            rate = 0.90, validDate = fixedNow, lastUpdated = fixedNow
        ))
        // GBP→EUR = 1.15
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "GBP", toCurrency = "EUR",
            rate = 1.15, validDate = fixedNow, lastUpdated = fixedNow
        ))
        // NO CHF→EUR rate (will cause partial conversion)
    }

    private suspend fun seedExpenses() {
        // 1. EUR groceries (Food, cat 1) — no conversion needed
        insertExpense(createPurchase(amount = 100.0, currency = "EUR", merchant = "Lidl", categoryId = 1))

        // 2. USD shopping (Shopping, cat 3) — converts at 0.90 → 45.00 EUR
        insertExpense(createPurchase(amount = 50.0, currency = "USD", merchant = "Amazon", categoryId = 3))

        // 3. GBP travel (Transport, cat 2) — converts at 1.15 → 46.00 EUR
        insertExpense(createPurchase(amount = 40.0, currency = "GBP", merchant = "Uber", categoryId = 2))

        // 4. CHF dining (Food, cat 1) — MISSING RATE → conversion failure
        insertExpense(createPurchase(amount = 20.0, currency = "CHF", merchant = "Restaurant", categoryId = 1))

        // 5. Deposit (should be EXCLUDED from spending totals)
        insertExpense(Expense(
            amount = 3000.0, currency = "EUR", merchant = "Salary",
            merchantKey = "salary", date = fixedNow,
            transactionType = TransactionType.DEPOSIT, createdAt = fixedNow
        ))
    }

    // ── Serialization ──

    private fun serializeResult(
        total: MoneyAggregate,
        categoryTotals: Map<Long?, MoneyAggregate>
    ): JSONObject {
        return JSONObject().apply {
            put("homeCurrency", total.displayCurrency.code)
            put("displayTotal", total.displayAmount)
            put("isPartial", total.isPartial)
            put("totalTransactionCount", total.totalTransactionCount)

            put("sourceBuckets", JSONArray().apply {
                total.sourceBuckets.forEach { bucket ->
                    put(JSONObject().apply {
                        put("currency", bucket.currency.code)
                        put("amount", bucket.amount)
                        put("transactionCount", bucket.transactionCount)
                    })
                }
            })

            put("conversionFailures", JSONArray().apply {
                total.conversionFailures.forEach { failure ->
                    put(JSONObject().apply {
                        put("currency", failure.originalAmount.currency.code)
                        put("amount", failure.originalAmount.amount)
                        put("reason", failure.reason.name)
                    })
                }
            })

            put("categoryTotals", JSONObject().apply {
                categoryTotals.forEach { (catId, aggregate) ->
                    put(catId?.toString() ?: "uncategorized", JSONObject().apply {
                        put("displayAmount", aggregate.displayAmount)
                        put("isPartial", aggregate.isPartial)
                        put("transactionCount", aggregate.totalTransactionCount)
                    })
                }
            })
        }
    }
}
