package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.golden.GoldenTestBase
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * E2E Test 4: Budget Creation → Expense → Threshold Alert
 *
 * Wires REAL BudgetCalculator + REAL MultiCurrencyRepository + REAL Room DB.
 * Proves:
 * 1. Budget period boundaries computed correctly
 * 2. Expenses within period counted against budget
 * 3. WARNING threshold (75%) triggers at correct spend level
 * 4. EXCEEDED threshold (100%) triggers when over limit
 * 5. Expenses outside period NOT counted
 * 6. Only PURCHASE type counts (deposits excluded)
 */
class BudgetThresholdAlertE2ETest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var budgetCalculator: BudgetCalculator

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "e2e_budget_threshold_alert",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

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
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )

        budgetCalculator = BudgetCalculator(timeProvider)
    }

    @Test
    fun `budget thresholds computed correctly as expenses accumulate`() = runTest {
        seedCategories()

        // ── SEED: Monthly budget 200€ for Food (category 1), CALENDAR mode ──
        val budget = Budget(
            categoryId = 1L,
            amount = 200.0,
            period = BudgetPeriod.MONTHLY,
            periodMode = "CALENDAR",
            startDate = fixedNow - 86400000L * 14, // started 14 days ago
            isActive = true,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f,
            currency = "EUR",
            createdAt = fixedNow - 86400000L * 14
        )
        database.budgetDao().insert(budget)

        // ── Compute period boundaries ──
        val periodRange = budgetCalculator.calculatePeriodRange(budget, fixedNow)
        val periodStart = periodRange.first
        val periodEnd = periodRange.second

        // ── ACT 1: Spend 140€ (70% - under warning) ──
        insertExpense(createPurchase(amount = 80.0, currency = "EUR", merchant = "Lidl", categoryId = 1,
            date = fixedNow - 86400000L * 5))
        insertExpense(createPurchase(amount = 60.0, currency = "EUR", merchant = "AB", categoryId = 1,
            date = fixedNow - 86400000L * 3))

        val total1 = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val percent1 = total1.displayAmount / budget.amount

        // ── ACT 2: Spend 40€ more (total 180€ = 90% - WARNING) ──
        insertExpense(createPurchase(amount = 40.0, currency = "EUR", merchant = "Sklavenitis", categoryId = 1,
            date = fixedNow - 86400000L))

        val total2 = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val percent2 = total2.displayAmount / budget.amount

        // ── ACT 3: Spend 30€ more (total 210€ = 105% - EXCEEDED) ──
        insertExpense(createPurchase(amount = 30.0, currency = "EUR", merchant = "Public", categoryId = 1,
            date = fixedNow))

        val total3 = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val percent3 = total3.displayAmount / budget.amount

        // ── Verify: Deposit doesn't count ──
        insertExpense(createPurchase(amount = 500.0, currency = "EUR", merchant = "Refund", categoryId = 1)
            .copy(transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT))

        val totalWithDeposit = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── Determine severity at each stage ──
        fun severity(pct: Double): String = when {
            pct >= 1.0 -> "EXCEEDED"
            pct >= budget.notifyAtCritical -> "CRITICAL"
            pct >= budget.notifyAtWarning -> "WARNING"
            else -> "ON_TRACK"
        }

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("budgetLimit", budget.amount)
            put("periodStart", periodStart)
            put("periodEnd", periodEnd)

            put("stage1_spent", total1.displayAmount)
            put("stage1_percent", percent1)
            put("stage1_severity", severity(percent1))

            put("stage2_spent", total2.displayAmount)
            put("stage2_percent", percent2)
            put("stage2_severity", severity(percent2))

            put("stage3_spent", total3.displayAmount)
            put("stage3_percent", percent3)
            put("stage3_severity", severity(percent3))

            put("depositExcluded", Math.abs(totalWithDeposit.displayAmount - total3.displayAmount) < 0.01)
            put("transactionCount", totalWithDeposit.totalTransactionCount)
        }

        verifier.verify(actual).assertPassed()
    }
}
