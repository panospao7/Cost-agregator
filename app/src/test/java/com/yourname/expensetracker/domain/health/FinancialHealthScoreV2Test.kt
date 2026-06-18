package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.HealthScoreHistory
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class FinancialHealthScoreV2Test : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var healthScoreHistoryDao: HealthScoreHistoryDao

    private lateinit var calculator: FinancialHealthScoreV2

    private val now = millis(2026, Calendar.APRIL, 15)
    private val dayMs = 24L * 60L * 60L * 1000L

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk()
        expenseRepository = mockk()
        savingsGoalRepository = mockk()
        recurringExpenseEngine = mockk()
        healthScoreHistoryDao = mockk(relaxed = true)
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        every { timeProvider.now() } returns now
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns emptyList()
        coEvery { savingsGoalRepository.getSavingsGoals() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()
        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns null
        coEvery { healthScoreHistoryDao.getHistoryForPeriod(any(), any()) } returns emptyList()

        // Mock normalizer to pass through expenses with proper conversion
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val exps = firstArg<List<com.yourname.expensetracker.data.database.entity.Expense>>()
            val homeCurrency = secondArg<String>()
            val snapshots = exps.map { it.toTestExpenseSnapshot() }
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = snapshots.map {
                    NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = snapshots,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = exps.size
            )
        }

        calculator = FinancialHealthScoreV2(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            healthScoreHistoryDao = healthScoreHistoryDao,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true),
            writeBarrier = mockk(relaxed = true)
        )
    }

    @Test
    fun `calculateHealthScore applies weighted formula thirty twentyfive twentyfive twenty`() = runTest {
        // Savings component: income 1000, expenses 900 => rate 10% => score 50
        // Runway (stabilized): day-15 projection monthlyExpenses ~= 1800 => 0.5 month => score 8
        // Budget adherence: one budget 1000 spent 1100 => overspend ratio 0.1 => score 90
        // Bills: no patterns => default 75
        // Overall = 0.30*50 + 0.25*8 + 0.25*90 + 0.20*75 = 54.5 -> 54
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1000.0, TransactionType.DEPOSIT, now - 10 * dayMs),
            expense(2L, 900.0, TransactionType.PURCHASE, now - 9 * dayMs)
        )
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns listOf(
            budgetStatus(amount = 1000.0, spent = 1100.0)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 3000.0, current = 900.0))

        val result = calculator.calculateHealthScore()

        assertEquals(50, result.savingsRateScore)
        assertEquals(8, result.runwayScore)
        assertEquals(90, result.budgetAdherenceScore)
        assertEquals(75, result.billReliabilityScore)
        assertEquals(54, result.overallScore)
    }

    @Test
    fun `calculateHealthScore runway uses savings goals not monthly budget surplus`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1000.0, TransactionType.DEPOSIT, now - 10 * dayMs),
            expense(2L, 500.0, TransactionType.PURCHASE, now - 9 * dayMs)
        )
        // Large budget headroom should NOT inflate runway score.
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns listOf(
            budgetStatus(amount = 5000.0, spent = 500.0)
        )
        // Savings goals total currentAmount = 1000, stabilized monthly burn on day-15 ~= 1000 => 1 month => score 16
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(
            goal(1L, target = 10_000.0, current = 700.0),
            goal(2L, target = 5_000.0, current = 300.0)
        )

        val result = calculator.calculateHealthScore()

        assertEquals(16, result.runwayScore)
    }

    @Test
    fun `calculateHealthScore runway uses baseline blend for early month stability`() = runTest {
        val earlyNow = millis(2026, Calendar.APRIL, 2)
        every { timeProvider.now() } returns earlyNow

        val periodStart = TimePeriodUtils.getStartOfMonth(earlyNow)
        val periodEnd = TimePeriodUtils.getEndOfMonth(earlyNow)

        val currentPurchases = listOf(
            expense(100L, 50.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 1))
        )
        val historicalPurchases = listOf(
            expense(200L, 900.0, TransactionType.PURCHASE, millis(2026, Calendar.JANUARY, 15)),
            expense(201L, 900.0, TransactionType.PURCHASE, millis(2026, Calendar.FEBRUARY, 15)),
            expense(202L, 900.0, TransactionType.PURCHASE, millis(2026, Calendar.MARCH, 15))
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = invocation.args[0] as Long
            val end = invocation.args[1] as Long
            if (start == periodStart && end == periodEnd) currentPurchases else historicalPurchases
        }

        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 5000.0, current = 900.0))

        val result = calculator.calculateHealthScore(periodStart, periodEnd)

        // Early month day-2 with history should stay near 1 month runway
        // instead of inflating from sparse MTD data.
        assertEquals(16, result.runwayScore)
    }

    @Test
    fun `calculateHealthScore runway returns neutral with very low coverage and no baseline`() = runTest {
        val firstDayNow = millis(2026, Calendar.APRIL, 1)
        every { timeProvider.now() } returns firstDayNow

        val periodStart = TimePeriodUtils.getStartOfMonth(firstDayNow)
        val periodEnd = TimePeriodUtils.getEndOfMonth(firstDayNow)

        val currentPurchases = listOf(
            expense(300L, 20.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 1))
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = invocation.args[0] as Long
            val end = invocation.args[1] as Long
            if (start == periodStart && end == periodEnd) currentPurchases else emptyList()
        }
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 5000.0, current = 1000.0))

        val result = calculator.calculateHealthScore(periodStart, periodEnd)

        assertEquals(50, result.runwayScore)
    }

    @Test
    fun `calculateHealthScore upserts history by updating existing period record`() = runTest {
        val periodStart = now - 30 * dayMs
        val periodEnd = now

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1000.0, TransactionType.DEPOSIT, now - 5 * dayMs),
            expense(2L, 800.0, TransactionType.PURCHASE, now - 4 * dayMs)
        )
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns listOf(
            budgetStatus(amount = 1000.0, spent = 800.0)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 5000.0, current = 1200.0))

        coEvery { healthScoreHistoryDao.getHistoryForPeriod(periodStart, periodEnd) } returns listOf(
            HealthScoreHistory(
                id = 99L,
                overallScore = 10,
                savingsRateScore = 10,
                runwayScore = 10,
                budgetAdherenceScore = 10,
                billReliabilityScore = 10,
                periodStart = periodStart,
                periodEnd = periodEnd,
                trend = HealthTrend.STABLE.name
            )
        )

        calculator.calculateHealthScore(periodStart, periodEnd)

        coVerify(exactly = 1) { healthScoreHistoryDao.update(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
    }

    @Test
    fun `calculateHealthScore determines trend improving stable declining by five point threshold`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1000.0, TransactionType.DEPOSIT, now - 5 * dayMs),
            expense(2L, 100.0, TransactionType.PURCHASE, now - 4 * dayMs)
        )
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns listOf(
            budgetStatus(amount = 1000.0, spent = 100.0)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 5000.0, current = 2000.0))

        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns HealthScoreHistory(
            id = 1L,
            overallScore = 40,
            savingsRateScore = 40,
            runwayScore = 40,
            budgetAdherenceScore = 40,
            billReliabilityScore = 40,
            periodStart = now - 60 * dayMs,
            periodEnd = now - 31 * dayMs,
            trend = HealthTrend.STABLE.name
        )

        val improving = calculator.calculateHealthScore()
        assertEquals(HealthTrend.IMPROVING, improving.trend)

        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns improving.toHistorySnapshot(overall = improving.overallScore - 3)
        val stable = calculator.calculateHealthScore()
        assertEquals(HealthTrend.STABLE, stable.trend)

        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns improving.toHistorySnapshot(overall = improving.overallScore + 6)
        val declining = calculator.calculateHealthScore()
        assertEquals(HealthTrend.DECLINING, declining.trend)
    }

    @Test
    fun `calculateHealthScore edge case zero income gives neutral savings score`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 300.0, TransactionType.PURCHASE, now - 2 * dayMs)
        )

        val result = calculator.calculateHealthScore()

        assertEquals(50, result.savingsRateScore)
    }

    @Test
    fun `calculateHealthScore edge case zero expenses gives neutral runway score`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1500.0, TransactionType.DEPOSIT, now - 2 * dayMs)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 1000.0, current = 500.0))

        val result = calculator.calculateHealthScore()

        assertEquals(50, result.runwayScore)
    }

    @Test
    fun `calculateHealthScore edge case missing data uses neutral defaults`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns emptyList()
        coEvery { savingsGoalRepository.getSavingsGoals() } returns emptyList()
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        val result = calculator.calculateHealthScore()

        assertEquals(50, result.savingsRateScore)
        assertEquals(50, result.runwayScore)
        assertEquals(50, result.budgetAdherenceScore)
        assertEquals(75, result.billReliabilityScore)
        assertTrue(result.overallScore in 0..100)
    }

    @Test
    fun `calculateHealthScore uses historical budget statuses for requested period end`() = runTest {
        val periodStart = millis(2026, Calendar.FEBRUARY, 1)
        val periodEnd = millis(2026, Calendar.FEBRUARY, 28)
        val expectedEvaluationTime = periodEnd - 1

        coEvery { budgetRepository.getBudgetStatusesAt(expectedEvaluationTime) } returns listOf(
            budgetStatus(amount = 1000.0, spent = 1200.0)
        )

        val result = calculator.calculateHealthScore(periodStart, periodEnd)

        assertEquals(80, result.budgetAdherenceScore)
        coVerify(exactly = 1) { budgetRepository.getBudgetStatusesAt(expectedEvaluationTime) }
        coVerify(exactly = 0) { budgetRepository.getBudgetStatusesAt(now) }
    }

    private fun FinancialHealthResult.toHistorySnapshot(overall: Int): HealthScoreHistory {
        return HealthScoreHistory(
            id = 999L,
            overallScore = overall,
            savingsRateScore = savingsRateScore,
            runwayScore = runwayScore,
            budgetAdherenceScore = budgetAdherenceScore,
            billReliabilityScore = billReliabilityScore,
            periodStart = now - 30 * dayMs,
            periodEnd = now,
            trend = trend.name
        )
    }

    private fun budgetStatus(amount: Double, spent: Double): BudgetStatus {
        val budget = Budget(
            id = 1L,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 20 * dayMs
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = (amount - spent).coerceAtLeast(0.0),
            percentUsed = if (amount > 0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now - 20 * dayMs,
            periodEnd = now + 10 * dayMs,
            effectiveLimit = amount
        )
    }

    private fun goal(id: Long, target: Double, current: Double): SavingsGoal {
        return SavingsGoal(
            id = id,
            name = "G$id",
            targetAmount = target,
            currentAmount = current,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            createdAt = now - dayMs
        )
    }

    private fun expense(
        id: Long,
        amount: Double,
        type: TransactionType,
        date: Long
    ): com.yourname.expensetracker.data.database.entity.Expense {
        return com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = amount,
            merchant = "M$id",
            transactionType = type,
            date = date,
            isNotMine = false
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Converts a data-layer [Expense] into a domain [ExpenseSnapshot], mirroring
     * the logic in [FinancialHealthScoreV2.toExpenseSnapshot].
     */
    private fun com.yourname.expensetracker.data.database.entity.Expense.toTestExpenseSnapshot(): ExpenseSnapshot =
        ExpenseSnapshot(
            id = id,
            amount = effectiveAmount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = transferDirection?.let { d ->
                when (d) {
                    com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                    com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                }
            },
            notes = notes
        )
}
