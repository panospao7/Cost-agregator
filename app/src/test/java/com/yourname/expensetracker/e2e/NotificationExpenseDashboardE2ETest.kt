package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.golden.GoldenTestBase
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * E2E Test 1: Notification → Parse → Expense → Dashboard
 *
 * Wires the REAL parser registry with real Greek bank parser to prove:
 * 1. Greek bank notification text is correctly parsed (amount, merchant, currency)
 * 2. Parsed expense inserted into DB with correct fields
 * 3. Dashboard total reflects the new expense
 * 4. Duplicate notification (same dedupeKey) is rejected
 * 5. Revolut notification also parses correctly
 *
 * This test exercises the parser + DB layer end-to-end.
 * The full NotificationProcessingPipeline (24 deps) is too complex to wire,
 * so we test: parse → insert → dashboard, which is the critical data path.
 */
class NotificationExpenseDashboardE2ETest : GoldenTestBase() {

    private lateinit var parserRegistry: AppParserRegistry
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "e2e_notification_expense_dashboard",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        // Real parser registry with all real parsers
        val currencyNormalizer = CurrencyNormalizer()
        val merchantCleaner = MerchantCleaner()
        val directionDetector = TransferDirectionDetector()

        parserRegistry = AppParserRegistry(
            greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, "EUR"),
            revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
            smsParser = SmsParser(currencyNormalizer, merchantCleaner),
            googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
            genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider),
            aiFallbackParser = object : NotificationFallbackParser {
                override suspend fun parse(title: String?, text: String?, bigText: String?, packageName: String) = null
            },
            timeProvider = timeProvider
        )

        // Real multi-currency repository
        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings
        )
    }

    @Test
    fun `greek bank notification parsed and creates expense in dashboard`() = runTest {
        seedCategories()

        // ── Greek bank notification (NBG format) ──
        val nbgNotification = RawNotification(
            packageName = "gr.nbg.mobilebanking",
            appName = "NBG Mobile",
            title = "Νέα συναλλαγή",
            text = "Αγορά 45,50 EUR στο ΣΚΛΑΒΕΝΙΤΗΣ",
            timestamp = fixedNow,
            capturedAt = fixedNow
        )

        // ── ACT: Parse with real parser ──
        val parseResult = parserRegistry.parse(
            title = nbgNotification.title,
            text = nbgNotification.text,
            bigText = nbgNotification.bigText,
            subText = nbgNotification.subText,
            packageName = nbgNotification.packageName
        )

        // ── Verify parse succeeded ──
        assertNotNull("Greek bank notification should parse", parseResult)

        // ── Insert raw notification first (FK constraint) ──
        val rawId = database.rawNotificationDao().insert(nbgNotification)

        // ── Insert parsed expense into DB (simulating what coordinator does) ──
        val expense = createPurchase(
            amount = parseResult!!.amount,
            currency = parseResult.currency,
            merchant = parseResult.merchant,
            categoryId = 1
        ).copy(
            dedupeKey = "${parseResult.amount}_${parseResult.merchant.lowercase()}_${fixedNow / 86400000}_${parseResult.currency}",
            rawNotificationId = rawId
        )
        val expenseId = database.expenseDao().insertAtomic(expense)

        // ── ACT: Try duplicate (same dedupeKey) ──
        val dupResult = database.expenseDao().insertAtomic(expense.copy(id = 0))

        // ── Revolut notification ──
        val revolutNotification = RawNotification(
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = "Paid",
            text = "💳 Paid €30.00 at Amazon",
            timestamp = fixedNow,
            capturedAt = fixedNow
        )

        val revolutParse = parserRegistry.parse(
            title = revolutNotification.title,
            text = revolutNotification.text,
            bigText = null,
            subText = null,
            packageName = revolutNotification.packageName
        )

        if (revolutParse != null) {
            val revExpense = createPurchase(
                amount = revolutParse.amount,
                currency = revolutParse.currency,
                merchant = revolutParse.merchant,
                categoryId = 3
            ).copy(
                dedupeKey = "${revolutParse.amount}_${revolutParse.merchant.lowercase()}_${fixedNow / 86400000}_${revolutParse.currency}"
            )
            database.expenseDao().insertAtomic(revExpense)
        }

        // ── QUERY: Dashboard total ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            // Parse results
            put("nbgParsed", true)
            put("nbgAmount", parseResult.amount)
            put("nbgCurrency", parseResult.currency)
            put("nbgMerchant", parseResult.merchant)

            put("revolutParsed", revolutParse != null)
            if (revolutParse != null) {
                put("revolutAmount", revolutParse.amount)
                put("revolutCurrency", revolutParse.currency)
                put("revolutMerchant", revolutParse.merchant)
            }

            // DB state
            put("expenseCreated", expenseId > 0)
            put("duplicateRejected", dupResult <= 0L)

            // Dashboard
            put("dashboardTotal", dashboardTotal.displayAmount)
            put("dashboardTransactionCount", dashboardTotal.totalTransactionCount)
            put("dashboardIsPartial", dashboardTotal.isPartial)
        }

        verifier.verify(actual).assertPassed()
    }
}
