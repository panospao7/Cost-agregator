package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * S4-008: Home Dashboard Financial Invariant Test
 *
 * Proves that for the same period and seed data:
 * - MultiCurrencyRepository purchase total (dashboard path)
 * - Sum of category totals (analytics path)
 * - BudgetVsActualEngine total (budget path)
 * ALL agree within tolerance.
 *
 * This is the ViewModel-level parity test complementing the DB-level
 * AnalyticsDashboardBudgetParityGoldenTest.
 */
class HomeDashboardFinancialInvariantTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private val budgetEngine = BudgetVsActualEngine()

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "home_dashboard_financial_invariant",
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
    fun `dashboard total equals category sum equals budget spent`() = runTest {
        seedCategories()
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR",
            rate = 0.90, validDate = fixedNow, lastUpdated = fixedNow
        ))

        // Seed: 3 categories, 2 currencies
        insertExpense(createPurchase(amount = 100.0, currency = "EUR", merchant = "Lidl", categoryId = 1))
        insertExpense(createPurchase(amount = 50.0, currency = "USD", merchant = "Amazon", categoryId = 3))
        insertExpense(createPurchase(amount = 75.0, currency = "EUR", merchant = "Shell", categoryId = 2))

        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L

        // Dashboard path
        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val categoryTotals = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(periodStart, periodEnd)
        val categorySum = categoryTotals.values.sumOf { it.displayAmount }

        // Budget path (using normalized expenses)
        val normalizedExpenses = listOf(
            NormalizedExpense(id = 1, originalAmount = 100.0, originalEffectiveAmount = 100.0,
                originalCurrency = "EUR", normalizedAmount = 100.0, normalizedCurrency = "EUR",
                date = fixedNow, merchant = "Lidl", merchantKey = "lidl", categoryId = 1,
                categoryNameSnapshot = "Food", transactionType = "PURCHASE",
                isNotMine = false, isSharedExpense = false, ownershipMode = null, source = null),
            NormalizedExpense(id = 2, originalAmount = 50.0, originalEffectiveAmount = 50.0,
                originalCurrency = "USD", normalizedAmount = 45.0, normalizedCurrency = "EUR",
                date = fixedNow, merchant = "Amazon", merchantKey = "amazon", categoryId = 3,
                categoryNameSnapshot = "Shopping", transactionType = "PURCHASE",
                isNotMine = false, isSharedExpense = false, ownershipMode = null, source = null),
            NormalizedExpense(id = 3, originalAmount = 75.0, originalEffectiveAmount = 75.0,
                originalCurrency = "EUR", normalizedAmount = 75.0, normalizedCurrency = "EUR",
                date = fixedNow, merchant = "Shell", merchantKey = "shell", categoryId = 2,
                categoryNameSnapshot = "Transport", transactionType = "PURCHASE",
                isNotMine = false, isSharedExpense = false, ownershipMode = null, source = null)
        )
        val budgetResult = budgetEngine.compute(
            NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = normalizedExpenses),
            listOf(
                BudgetSnapshot(categoryId = 1L, amount = 200.0, currency = "EUR"),
                BudgetSnapshot(categoryId = 2L, amount = 100.0, currency = "EUR"),
                BudgetSnapshot(categoryId = 3L, amount = 150.0, currency = "EUR")
            ),
            "EUR"
        )

        val actual = JSONObject().apply {
            put("dashboardTotal", dashboardTotal.displayAmount)
            put("categorySum", categorySum)
            put("budgetActualTotal", budgetResult.totalActual)
            put("isPartial", dashboardTotal.isPartial)
            put("dashboardEqualsCategorySum",
                Math.abs(dashboardTotal.displayAmount - categorySum) < 0.01)
            put("dashboardEqualsBudgetActual",
                Math.abs(dashboardTotal.displayAmount - budgetResult.totalActual) < 0.01)
        }

        verifier.verify(actual).assertPassed()
    }
}
