package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Analytics / Dashboard / Budget Parity
 *
 * Proves that for the same period and same expenses:
 * 1. MultiCurrencyRepository purchase total (dashboard path)
 * 2. Sum of category totals (analytics path)
 * 3. BudgetVsActualEngine total (budget path)
 * ALL produce the same number.
 *
 * Uses REAL Room DB + REAL CurrencyConverter + REAL MultiCurrencyRepository + REAL BudgetVsActualEngine.
 */
class AnalyticsDashboardBudgetParityGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var currencyConverter: CurrencyConverter
    private val budgetEngine = BudgetVsActualEngine()

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "analytics_dashboard_budget_parity",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings
        )
    }

    @Test
    fun `dashboard total equals analytics total equals budget spent`() = runTest {
        // ── SEED ──
        seedCategories()
        seedExchangeRates()
        val expenses = seedMixedExpenses()

        // ── ACT: Dashboard path (MultiCurrencyRepository) ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L

        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val categoryTotals = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(periodStart, periodEnd)
        val categoryTotalsSum = categoryTotals.values.sumOf { it.displayAmount }

        // ── ACT: Budget path (BudgetVsActualEngine) ──
        val normalizedExpenses = expenses.map { exp ->
            val converted = if (exp.currency == "EUR") exp.amount
            else exp.amount * 0.90 // USD rate
            NormalizedExpense(
                id = exp.id, originalAmount = exp.amount,
                originalEffectiveAmount = exp.amount,
                originalCurrency = exp.currency,
                normalizedAmount = converted, normalizedCurrency = "EUR",
                date = exp.date, merchant = exp.merchant,
                merchantKey = exp.merchantKey, categoryId = exp.categoryId,
                categoryNameSnapshot = null,
                transactionType = exp.transactionType.name,
                isNotMine = false, isSharedExpense = false,
                ownershipMode = null, source = null
            )
        }

        val analyticsInput = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = normalizedExpenses
        )

        val budgets = listOf(
            BudgetSnapshot(categoryId = 1L, amount = 200.0, currency = "EUR"),
            BudgetSnapshot(categoryId = 2L, amount = 100.0, currency = "EUR"),
            BudgetSnapshot(categoryId = 3L, amount = 150.0, currency = "EUR")
        )

        val budgetResult = budgetEngine.compute(analyticsInput, budgets, "EUR")

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("dashboardTotal", dashboardTotal.displayAmount)
            put("categoryTotalsSum", categoryTotalsSum)
            put("budgetActualTotal", budgetResult.totalActual)
            put("isPartial", dashboardTotal.isPartial)

            // Parity checks
            put("dashboardEqualsCategorySum", Math.abs(dashboardTotal.displayAmount - categoryTotalsSum) < 0.01)
            put("dashboardEqualsBudgetActual", Math.abs(dashboardTotal.displayAmount - budgetResult.totalActual) < 0.01)

            put("budgetItems", JSONArray().apply {
                budgetResult.items.forEach { item ->
                    put(JSONObject().apply {
                        put("categoryId", item.categoryId)
                        put("budgetLimit", item.budgetLimit)
                        put("actualSpent", item.actualSpent)
                        put("percentageUsed", item.percentageUsed)
                        put("isOverBudget", item.isOverBudget)
                    })
                }
            })

            put("transactionCount", dashboardTotal.totalTransactionCount)
        }

        // ── VERIFY ──
        verifier.verify(actual).assertPassed()
    }

    // ── Seed helpers ──

    private suspend fun seedExchangeRates() {
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR",
            rate = 0.90, validDate = fixedNow, lastUpdated = fixedNow
        ))
    }

    private suspend fun seedMixedExpenses(): List<Expense> {
        val expenses = listOf(
            // Food (cat 1): 80 EUR + 50 USD (= 45 EUR) = 125 EUR
            createPurchase(amount = 80.0, currency = "EUR", merchant = "Lidl", categoryId = 1),
            createPurchase(amount = 50.0, currency = "USD", merchant = "Costco", categoryId = 1),
            // Transport (cat 2): 35 EUR
            createPurchase(amount = 35.0, currency = "EUR", merchant = "Shell", categoryId = 2),
            // Shopping (cat 3): 60 EUR
            createPurchase(amount = 60.0, currency = "EUR", merchant = "Zara", categoryId = 3),
            // Deposit (EXCLUDED from all spending totals)
            Expense(
                amount = 2000.0, currency = "EUR", merchant = "Salary",
                merchantKey = "salary", date = fixedNow,
                transactionType = TransactionType.DEPOSIT, createdAt = fixedNow
            )
        )
        val inserted = mutableListOf<Expense>()
        expenses.forEach { exp ->
            val id = insertExpense(exp)
            inserted.add(exp.copy(id = id))
        }
        // Return only PURCHASE expenses for budget engine input
        return inserted.filter { it.transactionType == TransactionType.PURCHASE }
    }
}
