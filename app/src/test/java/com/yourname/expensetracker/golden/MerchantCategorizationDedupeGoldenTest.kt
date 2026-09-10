package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
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
 * Golden Scenario Test: Merchant Categorization & Dedup
 *
 * Proves that:
 * 1. MerchantKeyGenerator normalizes Greek→Latin, lowercase, strip non-alphanumeric
 * 2. Different merchant name variants produce the same key (dedup grouping)
 * 3. Analytics groups expenses by merchantKey (not raw merchant name)
 * 4. Dedup detection uses merchantKey for matching
 * 5. Expenses with same merchantKey are grouped in merchant totals
 */
class MerchantCategorizationDedupeGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "merchant_categorization_dedup",
        numericTolerance = 0.01
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
    fun `merchant key normalization groups variants together`() = runTest {
        seedCategories()

        // Test MerchantKeyGenerator normalization
        val variants = listOf(
            "ΣΚΛΑΒΕΝΙΤΗΣ",      // Greek uppercase
            "Σκλαβενίτης",      // Greek mixed case
            "Sklavenitis",      // Latin
            "SKLAVENITIS",      // Latin uppercase
            "sklavenitis"       // Latin lowercase
        )
        val keys = variants.map { MerchantKeyGenerator.generate(it) }

        // All variants should produce the same key
        val allSameKey = keys.distinct().size == 1
        val canonicalKey = keys.first()

        // Insert expenses with different merchant name variants but same merchantKey
        insertExpense(createPurchase(amount = 30.0, merchant = "ΣΚΛΑΒΕΝΙΤΗΣ", categoryId = 1)
            .copy(merchantKey = MerchantKeyGenerator.generate("ΣΚΛΑΒΕΝΙΤΗΣ")))
        insertExpense(createPurchase(amount = 25.0, merchant = "Sklavenitis", categoryId = 1)
            .copy(merchantKey = MerchantKeyGenerator.generate("Sklavenitis")))
        insertExpense(createPurchase(amount = 45.0, merchant = "SKLAVENITIS", categoryId = 1)
            .copy(merchantKey = MerchantKeyGenerator.generate("SKLAVENITIS")))

        // Insert a different merchant
        insertExpense(createPurchase(amount = 60.0, merchant = "Lidl", categoryId = 1)
            .copy(merchantKey = MerchantKeyGenerator.generate("Lidl")))

        // Query merchant totals (grouped by merchantKey) - use currency-aware variant
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val merchantTotals = database.expenseDao().getMerchantTotalsBetweenByCurrency(periodStart, periodEnd)

        // Query dedup: would a new "Σκλαβενίτης" 30 EUR be detected as duplicate?
        val wouldBeDuplicate = database.expenseDao().existsByMerchantKeyInRange(
            merchantKey = MerchantKeyGenerator.generate("Σκλαβενίτης"),
            startDate = fixedNow - 300000L, // 5 min window
            endDate = fixedNow + 300000L,
            minAmount = 28.0, // 30 - tolerance
            maxAmount = 32.0  // 30 + tolerance
        )

        // Serialize
        val actual = JSONObject().apply {
            put("canonicalKey", canonicalKey)
            put("allVariantsProduceSameKey", allSameKey)
            put("variantCount", variants.size)

            put("merchantKeys", JSONArray().apply {
                variants.zip(keys).forEach { (name, key) ->
                    put(JSONObject().apply {
                        put("input", name)
                        put("key", key)
                    })
                }
            })

            // Merchant totals grouped by key
            put("merchantGroupCount", merchantTotals.size)
            put("sklavenitisTotalAmount", merchantTotals
                .filter { it.merchant.lowercase().contains("sklav") || it.merchant.contains("ΣΚΛΑΒ") }
                .sumOf { it.total })
            put("sklavenitsTransactionCount", merchantTotals
                .filter { it.merchant.lowercase().contains("sklav") || it.merchant.contains("ΣΚΛΑΒ") }
                .sumOf { it.txCount })

            put("lidlTotalAmount", merchantTotals
                .filter { it.merchant.lowercase().contains("lidl") }
                .sumOf { it.total })

            put("dedupeWouldCatchVariant", wouldBeDuplicate)
        }

        verifier.verify(actual).assertPassed()
    }
}
