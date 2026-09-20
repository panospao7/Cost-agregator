package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Calendar

class FinancialHealthScoreV2Test : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var healthScoreHistoryDao: HealthScoreHistoryDao
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var cashFlowCalculator: CashFlowCalculator
    private lateinit var writeBarrier: DatabaseWriteBarrier

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
        analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)
        writeBarrier = mockk(relaxed = true)

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
            val snapshots = exps.map { it.toExpenseSnapshot() }
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
            cashFlowCalculator = cashFlowCalculator,
            writeBarrier = writeBarrier
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

        val result = calculator.availableResult()

        assertEquals(50, result.savingsRateScore)
        assertEquals(8, result.runwayScore)
        assertEquals(90, result.budgetAdherenceScore)
        assertEquals(75, result.billReliabilityScore)
        assertEquals(54, result.overallScore)
    }

    @Test
    fun `calculateHealthScore timing diagnostic uses injected fake clock not wall clock`() = runTest {
        // Same deterministic scenario as the weighted-formula test, but driven by
        // the real FakeTimeProvider (fixed at `now`) so the reported duration is
        // provably derived from the injected reads (fixed -> fixed = 0ms), never
        // the real wall clock. A wall-clock regression would make this flaky
        // (non-deterministic positive elapsed time) instead of exactly 0ms.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1000.0, TransactionType.DEPOSIT, now - 10 * dayMs),
            expense(2L, 900.0, TransactionType.PURCHASE, now - 9 * dayMs)
        )
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns listOf(
            budgetStatus(amount = 1000.0, spent = 1100.0)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 3000.0, current = 900.0))

        val captured = StringBuilder()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.startsWith("FinancialHealthScoreV2 calculated in")) {
                    captured.append(message)
                }
            }
        }
        Timber.plant(tree)
        try {
            val fakeTime = FakeTimeProvider(now)
            val fakeCalculator = FinancialHealthScoreV2(
                budgetRepository = budgetRepository,
                expenseRepository = expenseRepository,
                savingsGoalRepository = savingsGoalRepository,
                recurringExpenseEngine = recurringExpenseEngine,
                healthScoreHistoryDao = healthScoreHistoryDao,
                timeProvider = fakeTime,
                analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
                currencySettingsRepository = currencySettingsRepository,
                cashFlowCalculator = cashFlowCalculator,
                writeBarrier = writeBarrier
            )

            val result = fakeCalculator.availableResult()

            assertTrue(
                "Timing diagnostic must use the injected clock, got: $captured",
                captured.contains("calculated in 0ms")
            )
            // Score semantics are preserved under the fake clock.
            assertEquals(54, result.overallScore)
            assertEquals(50, result.savingsRateScore)
            assertEquals(8, result.runwayScore)
        } finally {
            Timber.uproot(tree)
        }
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

        val result = calculator.availableResult()

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

        val result = calculator.availableResult(periodStart, periodEnd)

        // Early month day-2 with history should stay near 1 month runway
        // instead of inflating from sparse MTD data.
        assertEquals(16, result.runwayScore)
    }

    @Test
    fun `baseline normalization failure returns unavailable normalization failed`() = runTest {
        // Mirror of the baseline-blend fixture: early month (day 2), sparse
        // current-month data, non-empty historical purchases — so the runway
        // path MUST reach calculateHistoricalMonthlyBaseline and normalize the
        // historical rows. P5-008: the historical (baseline) normalization is
        // the SECOND raw-fallback site removed; a failure there must surface
        // as the typed Unavailable(NORMALIZATION_FAILED) — not a silent raw
        // effectiveAmount sum, not a fabricated score, not persistence.
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

        // The CURRENT-month normalization succeeds (empty-but-valid is legal);
        // only the BASELINE normalization fails — proving the typed reason
        // propagates from calculateHistoricalMonthlyBaseline, not the main path.
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val exps = firstArg<List<com.yourname.expensetracker.data.database.entity.Expense>>()
            if (exps.any { it.id == 200L }) {
                throw RuntimeException("simulated baseline normalization failure")
            } else {
                val homeCurrency = secondArg<String>()
                val snapshots = exps.map { it.toExpenseSnapshot() }
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
        }

        val outcome = calculator.calculateHealthScore(periodStart, periodEnd)

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.NORMALIZATION_FAILED,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        // Unavailable never persists.
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
    }

    @Test
    fun `baseline row missing from normalized association returns unavailable normalization failed`() = runTest {
        // Association-miss pin (P5-008): the baseline normalization "succeeds"
        // but one historical row is absent from the normalized association map
        // (modeled by normalizing only the first two of three rows with
        // totalInputCount = 2 — excludedCount stays 0, so the gate passes and
        // the per-row `?: throw` association lookup is what must fire).
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

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val exps = firstArg<List<com.yourname.expensetracker.data.database.entity.Expense>>()
            if (exps.any { it.id == 200L }) {
                // Baseline call: drop the LAST row from the normalized output so
                // totalInputCount(2) == normalizedExpenses.size(2) → excludedCount 0,
                // but historical row 202 has no association entry.
                val homeCurrency = secondArg<String>()
                val present = exps.filter { it.id != 202L }.map { it.toExpenseSnapshot() }
                AnalyticsNormalizationResult(
                    homeCurrency = homeCurrency,
                    normalizedExpenses = present.map {
                        NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                    },
                    includedExpenses = present,
                    warnings = emptyList(),
                    latestRateTimestamp = null,
                    totalInputCount = present.size
                )
            } else {
                val homeCurrency = secondArg<String>()
                val snapshots = exps.map { it.toExpenseSnapshot() }
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
        }

        val outcome = calculator.calculateHealthScore(periodStart, periodEnd)

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.NORMALIZATION_FAILED,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
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

        val result = calculator.availableResult(periodStart, periodEnd)

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

        val improving = calculator.availableResult()
        assertEquals(HealthTrend.IMPROVING, improving.trend)

        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns improving.toHistorySnapshot(overall = improving.overallScore - 3)
        val stable = calculator.availableResult()
        assertEquals(HealthTrend.STABLE, stable.trend)

        coEvery { healthScoreHistoryDao.getMostRecentBefore(any(), any()) } returns improving.toHistorySnapshot(overall = improving.overallScore + 6)
        val declining = calculator.availableResult()
        assertEquals(HealthTrend.DECLINING, declining.trend)
    }

    @Test
    fun `calculateHealthScore edge case zero income gives neutral savings score`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 300.0, TransactionType.PURCHASE, now - 2 * dayMs)
        )

        val result = calculator.availableResult()

        assertEquals(50, result.savingsRateScore)
    }

    @Test
    fun `calculateHealthScore edge case zero expenses gives neutral runway score`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 1500.0, TransactionType.DEPOSIT, now - 2 * dayMs)
        )
        coEvery { savingsGoalRepository.getSavingsGoals() } returns listOf(goal(1L, target = 1000.0, current = 500.0))

        val result = calculator.availableResult()

        assertEquals(50, result.runwayScore)
    }

    @Test
    fun `calculateHealthScore edge case missing data uses neutral defaults`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { budgetRepository.getBudgetStatusesAt(any()) } returns emptyList()
        coEvery { savingsGoalRepository.getSavingsGoals() } returns emptyList()
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        val result = calculator.availableResult()

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

        val result = calculator.availableResult(periodStart, periodEnd)

        assertEquals(80, result.budgetAdherenceScore)
        coVerify(exactly = 1) { budgetRepository.getBudgetStatusesAt(expectedEvaluationTime) }
        coVerify(exactly = 0) { budgetRepository.getBudgetStatusesAt(now) }
    }

    // ── P5-008: typed Unavailable outcomes ────────────────────────────────

    @Test
    fun `normalization failure returns unavailable normalization failed and does not save history`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } throws
            RuntimeException("simulated normalization failure")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 100.0, TransactionType.PURCHASE, now - dayMs)
        )

        val outcome = calculator.calculateHealthScore()

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.NORMALIZATION_FAILED,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
    }

    @Test
    fun `normalization excluding any row returns unavailable normalization failed`() = runTest {
        // One input row but the normalizer excludes it → excludedCount > 0.
        // AnalyticsNormalizationResult computes excludedCount as
        // totalInputCount - normalizedExpenses.size, so an empty normalizedExpenses
        // list with a non-zero totalInputCount models exactly that exclusion.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1L, 100.0, TransactionType.PURCHASE, now - dayMs)
        )
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val exps = firstArg<List<com.yourname.expensetracker.data.database.entity.Expense>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = exps.size
            )
        }

        val outcome = calculator.calculateHealthScore()

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.NORMALIZATION_FAILED,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
    }

    @Test
    fun `unavailable outcome never persists placeholder or fabricated row`() = runTest {
        // Broad-catch path: unexpected repository failure must yield
        // Unavailable(DATA_LOAD_FAILED) with zero history writes — and no
        // retention cleanup either: deleteOlderThan is a saveToHistory
        // side-effect and must only run after a real persisted success.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } throws
            RuntimeException("simulated data load failure")

        val outcome = calculator.calculateHealthScore()

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.DATA_LOAD_FAILED,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.deleteOlderThan(any()) }
    }

    @Test
    fun `home currency failure returns unavailable home currency unavailable`() = runTest {
        coEvery { currencySettingsRepository.homeCurrency() } throws
            RuntimeException("simulated home currency read failure")

        val outcome = calculator.calculateHealthScore()

        assertTrue(outcome is HealthScoreOutcome.Unavailable)
        assertEquals(
            HealthScoreUnavailableReason.HOME_CURRENCY_UNAVAILABLE,
            (outcome as HealthScoreOutcome.Unavailable).reason
        )
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
    }

    @Test
    fun `cancellation exception propagates and is not converted to unavailable`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } throws
            CancellationException("simulated caller cancellation")

        val thrown = runCatching { calculator.calculateHealthScore() }
            .exceptionOrNull()

        assertTrue("Expected CancellationException to propagate", thrown is CancellationException)
        coVerify(exactly = 0) { healthScoreHistoryDao.insert(any()) }
        coVerify(exactly = 0) { healthScoreHistoryDao.update(any()) }
    }

    /** P5-008: unwrap [HealthScoreOutcome.Available]; an unexpected Unavailable fails the test loudly. */
    private suspend fun FinancialHealthScoreV2.availableResult(
        periodStart: Long = TimePeriodUtils.getStartOfMonth(timeProvider.now()),
        periodEnd: Long = TimePeriodUtils.getEndOfMonth(timeProvider.now())
    ): FinancialHealthResult {
        return when (val outcome = calculateHealthScore(periodStart, periodEnd)) {
            is HealthScoreOutcome.Available -> outcome.result
            is HealthScoreOutcome.Unavailable ->
                error("Expected Available but got Unavailable(${outcome.reason.name})")
        }
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

}
