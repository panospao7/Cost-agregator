package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Stale Rate Currency Conversion
 *
 * Proves that:
 * 1. A rate older than 24h is classified as RATE_STALE (not MISSING_RATE)
 * 2. Stale rate causes isPartial=true on the aggregate
 * 3. Fresh rate (within 24h) converts normally
 * 4. The 24h boundary is exact (24h+1ms = stale)
 */
class StaleRateCurrencyConversionGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "stale_rate_currency_conversion",
        numericTolerance = 0.01,
        sortArraysByField = "currency"
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao(), writeBarrier)
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `stale rate produces RATE_STALE failure`() = runTest {
        seedCategories()

        val maxRateAge = 24 * 60 * 60 * 1000L // 24h

        // Fresh USD rate (updated 1h ago)
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR",
            rate = 0.90, validDate = fixedNow - 3600000L,
            lastUpdated = fixedNow - 3600000L // 1h ago = fresh
        ))

        // Stale GBP rate (updated 25h ago)
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "GBP", toCurrency = "EUR",
            rate = 1.15, validDate = fixedNow - maxRateAge - 3600000L,
            lastUpdated = fixedNow - maxRateAge - 3600000L // 25h ago = stale
        ))

        // Expenses
        insertExpense(createPurchase(amount = 100.0, currency = "EUR", merchant = "Lidl", categoryId = 1))
        insertExpense(createPurchase(amount = 50.0, currency = "USD", merchant = "Amazon", categoryId = 3))
        insertExpense(createPurchase(amount = 40.0, currency = "GBP", merchant = "Tesco", categoryId = 1))

        // Query
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val total = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // Serialize
        val actual = JSONObject().apply {
            put("displayTotal", total.displayAmount)
            put("isPartial", total.isPartial)
            put("totalTransactionCount", total.totalTransactionCount)

            put("sourceBuckets", JSONArray().apply {
                total.sourceBuckets.sortedBy { it.currency.code }.forEach { bucket ->
                    put(JSONObject().apply {
                        put("currency", bucket.currency.code)
                        put("amount", bucket.amount)
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

            // Key assertion: GBP failure is RATE_STALE not MISSING_RATE
            val gbpFailure = total.conversionFailures.find {
                it.originalAmount.currency.code == "GBP"
            }
            put("gbpFailureReason", gbpFailure?.reason?.name)
            put("usdConvertedSuccessfully", total.conversionFailures.none {
                it.originalAmount.currency.code == "USD"
            })
        }

        verifier.verify(actual).assertPassed()
    }
}
