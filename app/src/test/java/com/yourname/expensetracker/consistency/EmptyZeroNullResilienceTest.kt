package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.groups.MemberBalance
import com.yourname.expensetracker.domain.groups.Settlement
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmptyZeroNullResilienceTest : AnalyticsEngineTestBase() {

    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var financialHealthScoreV2: FinancialHealthScoreV2
    private lateinit var currencyConverter: CurrencyConverter
    private val settlementCalculator = SettlementCalculator(currencySettingsRepository = mockk(), writeBarrier = mockk(relaxed = true))

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var healthScoreHistoryDao: HealthScoreHistoryDao
    private lateinit var exchangeRateStore: ExchangeRateStore

    @Before
    override fun setUp() {
        super.setUp()

        budgetCalculator = BudgetCalculator(timeProvider)
        spendingPaceCalculator = SpendingPaceCalculator(timeProvider)

        budgetRepository = mockk()
        expenseRepository = mockk()
        savingsGoalRepository = mockk()
        recurringExpenseEngine = mockk()
        healthScoreHistoryDao = mockk(relaxed = true)
        exchangeRateStore = mockk(relaxed = true)
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery {
            recurringExpenseEngine.getPatterns(any<List<com.yourname.expensetracker.data.database.entity.Expense>>())
        } returns emptyList()
        coEvery { healthScoreHistoryDao.getMostRecent() } returns null
        coEvery { healthScoreHistoryDao.getHistoryForPeriod(any(), any()) } returns emptyList()
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null

        financialHealthScoreV2 = FinancialHealthScoreV2(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            healthScoreHistoryDao = healthScoreHistoryDao,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            cashFlowCalculator = mockk(),
            writeBarrier = mockk(relaxed = true),
        )

        currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider = mockk(relaxed = true))
    }

    @Test
    fun `all engines handle empty zero null style inputs with sensible finite defaults`() = runTest {
        val snapshot = computeEmptyInputSnapshot()

        // BudgetCalculator
        assertTrue(snapshot.emptyBudgetRanges.isEmpty())
        assertApproxEquals(0.0, snapshot.zeroAmountBudget.amount, 0.0)
        assertTrue(snapshot.zeroAmountBudgetRange.first < snapshot.zeroAmountBudgetRange.second)

        // SpendingPaceCalculator (zero-days-elapsed scenario is coerced safely)
        assertApproxEquals(0.0, snapshot.spendingPace.currentMonthSpent, 0.0)
        assertApproxEquals(0.0, snapshot.spendingPace.projectedTotal, 0.0)
        assertNull(snapshot.spendingPace.previousMonthTotal)
        assertTrue(snapshot.spendingPace.daysElapsed >= 1)
        assertEquals(PaceStatus.NO_BASELINE, snapshot.spendingPace.paceStatus)
        assertTrue(snapshot.spendingPace.pacePercentage.isFinite())

        // FinancialHealthScoreV2 (no income, no expenses)
        assertEquals(50, snapshot.healthResult.savingsRateScore)
        assertEquals(50, snapshot.healthResult.runwayScore)
        assertEquals(50, snapshot.healthResult.budgetAdherenceScore)
        assertEquals(75, snapshot.healthResult.billReliabilityScore)
        assertEquals(55, snapshot.healthResult.overallScore)

        // CurrencyConverter
        assertNull(snapshot.unknownCurrencyConversion)
        assertApproxEquals(0.0, snapshot.sameCurrencyZeroConversion.convertedAmount, 0.0)
        assertApproxEquals(1.0, snapshot.sameCurrencyZeroConversion.rateUsed, 0.0)
        assertApproxEquals(0.0, snapshot.multiConversionAggregate.total, 0.0)
        assertEquals(1, snapshot.multiConversionAggregate.failedConversions.size)
        assertTrue(snapshot.multiConversionAggregate.hasFailures)

        // SettlementCalculator
        assertTrue(snapshot.settlements.isEmpty())
        assertApproxEquals(0.0, settlementCalculator.getTotalSettlementAmount(snapshot.settlements), 0.0)
        assertEquals(0, settlementCalculator.getTransactionCount(snapshot.settlements))

        // SplitCalculator
        assertEquals(3, snapshot.splitAmounts.size)
        snapshot.splitAmounts.values.forEach { value ->
            assertApproxEquals(0.0, value, 0.0)
        }
        assertApproxEquals(0.0, snapshot.splitAmounts.values.sum(), 0.0)
        assertTrue(SplitCalculator.validateSplits(snapshot.splitAmounts, 0.0))

        // No NaN / Infinity checks
        assertFiniteDoubles(
            snapshot.spendingPace.currentMonthSpent,
            snapshot.spendingPace.projectedTotal,
            snapshot.sameCurrencyZeroConversion.convertedAmount,
            snapshot.sameCurrencyZeroConversion.rateUsed,
            snapshot.multiConversionAggregate.total,
            settlementCalculator.getTotalSettlementAmount(snapshot.settlements),
            snapshot.healthResult.factorContributions.sumOf { it.weight }
        )
        snapshot.splitAmounts.values.forEach { value ->
            assertFiniteDoubles(value)
        }
    }

    @Test
    fun `stateflow take one emissions for all engine outputs complete normally`() = runTest {
        val snapshot = computeEmptyInputSnapshot()

        val budgetEmissions = MutableStateFlow(snapshot.emptyBudgetRanges).take(1).toList()
        val paceEmissions = MutableStateFlow(snapshot.spendingPace).take(1).toList()
        val healthEmissions = MutableStateFlow(snapshot.healthResult).take(1).toList()
        val unknownCurrencyEmissions = MutableStateFlow(snapshot.unknownCurrencyConversion).take(1).toList()
        val aggregateEmissions = MutableStateFlow(snapshot.multiConversionAggregate).take(1).toList()
        val settlementEmissions = MutableStateFlow(snapshot.settlements).take(1).toList()
        val splitEmissions = MutableStateFlow(snapshot.splitAmounts).take(1).toList()

        assertEquals(1, budgetEmissions.size)
        assertEquals(1, paceEmissions.size)
        assertEquals(1, healthEmissions.size)
        assertEquals(1, unknownCurrencyEmissions.size)
        assertEquals(1, aggregateEmissions.size)
        assertEquals(1, settlementEmissions.size)
        assertEquals(1, splitEmissions.size)

        assertEquals(snapshot.emptyBudgetRanges, budgetEmissions.single())
        assertEquals(snapshot.spendingPace, paceEmissions.single())
        assertEquals(snapshot.healthResult, healthEmissions.single())
        assertEquals(snapshot.unknownCurrencyConversion, unknownCurrencyEmissions.single())
        assertEquals(snapshot.multiConversionAggregate, aggregateEmissions.single())
        assertEquals(snapshot.settlements, settlementEmissions.single())
        assertEquals(snapshot.splitAmounts, splitEmissions.single())
    }

    private suspend fun computeEmptyInputSnapshot(): EmptyInputSnapshot {
        // BudgetCalculator: empty budget list + zero amount budget
        val emptyBudgetRanges = emptyList<Budget>()
            .map { budget -> budgetCalculator.calculatePeriodRange(budget, fixedNow) }
        val zeroAmountBudget = Budget(
            categoryId = null,
            amount = 0.0,
            period = BudgetPeriod.MONTHLY,
            startDate = fixedNow
        )
        val zeroAmountBudgetRange = budgetCalculator.calculatePeriodRange(zeroAmountBudget, fixedNow)

        // SpendingPaceCalculator: empty expenses + zero-days-elapsed style (now before month start)
        val currentMonthStart = TimePeriodUtils.getStartOfMonth(fixedNow)
        val previousMonthStart = TimePeriodUtils.addMonths(currentMonthStart, -1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns (currentMonthStart - 1L)
        val spendingPace = spendingPaceCalculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = emptyList(),
            displayCurrency = "EUR"
        )

        // FinancialHealthScoreV2: no income + no expenses
        every { timeProvider.now() } returns fixedNow
        val healthResult = financialHealthScoreV2.calculateHealthScore()

        // CurrencyConverter: zero amount + unknown currency
        val unknownCurrencyConversion = currencyConverter.convert(0.0, "ZZZ", "XXX")
        val sameCurrencyZeroConversion = currencyConverter.convert(0.0, "EUR", "EUR")
            ?: error("Same-currency conversion should never be null")
        val multiConversionAggregate = currencyConverter.convertMultiple(
            amounts = listOf(0.0 to "ZZZ"),
            targetCurrency = "EUR"
        )

        // SettlementCalculator: all-zero balances
        val settlements = settlementCalculator.calculateSettlements(
            mapOf(
                1L to memberBalance(1L, "Alice", 0.0),
                2L to memberBalance(2L, "Bob", 0.0),
                3L to memberBalance(3L, "Carol", 0.0)
            )
        )

        // SplitCalculator: zero amount expense
        val splitMembers = listOf(
            GroupMember(id = 1L, groupId = 1L, name = "Alice"),
            GroupMember(id = 2L, groupId = 1L, name = "Bob"),
            GroupMember(id = 3L, groupId = 1L, name = "Carol")
        )
        val zeroExpense = GroupExpense(
            id = 1L,
            groupId = 1L,
            expenseId = null,
            paidById = 1L,
            date = fixedNow,
            description = "Zero Amount",
            totalAmount = 0.0,
            splitType = SplitType.EQUAL,
            customSplitsJson = null
        )
        val splitAmounts = SplitCalculator.calculateSplitAmounts(zeroExpense, splitMembers)

        return EmptyInputSnapshot(
            emptyBudgetRanges = emptyBudgetRanges,
            zeroAmountBudget = zeroAmountBudget,
            zeroAmountBudgetRange = zeroAmountBudgetRange,
            spendingPace = spendingPace,
            healthResult = healthResult,
            unknownCurrencyConversion = unknownCurrencyConversion,
            sameCurrencyZeroConversion = sameCurrencyZeroConversion,
            multiConversionAggregate = multiConversionAggregate,
            settlements = settlements,
            splitAmounts = splitAmounts
        )
    }

    private fun memberBalance(id: Long, name: String, net: Double): MemberBalance {
        val paid = if (net > 0.0) net else 0.0
        val shouldPay = if (net < 0.0) -net else 0.0
        return MemberBalance(
            memberId = id,
            memberName = name,
            paid = paid,
            shouldPay = shouldPay,
            netBalance = net,
            currency = "EUR",
        )
    }

    private fun assertFiniteDoubles(vararg values: Double) {
        values.forEach { value ->
            assertTrue("Expected finite double but got $value", value.isFinite())
        }
    }

    private data class EmptyInputSnapshot(
        val emptyBudgetRanges: List<Pair<Long, Long>>,
        val zeroAmountBudget: Budget,
        val zeroAmountBudgetRange: Pair<Long, Long>,
        val spendingPace: SpendingPace,
        val healthResult: FinancialHealthResult,
        val unknownCurrencyConversion: ConversionResult?,
        val sameCurrencyZeroConversion: ConversionResult,
        val multiConversionAggregate: MultiConversionAggregate,
        val settlements: List<Settlement>,
        val splitAmounts: Map<Long, Double>
    )
}