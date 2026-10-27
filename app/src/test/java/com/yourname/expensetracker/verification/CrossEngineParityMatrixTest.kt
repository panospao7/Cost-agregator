package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.generateInsights
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.testCurrencyConverter
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.MonthlyTotal
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.WeeklyTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriodRange
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.analytics.fixtures.GoldenDataSets
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import com.yourname.expensetracker.domain.health.FinancialHealthCalculator
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.SmartSavingsEngine
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.MoneyRadarData
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.UrgencyLevel
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * B6-25: one cross-engine parity matrix replacing CrossSourceVerificationTest,
 * GoldenMasterVerificationTest and CrossGroupIntegrationTest.
 *
 * Every table row names its fixture, clock and engines; every engine value is compared with a
 * hard-coded oracle (derived by hand from the fixture, never from production) and with its
 * sibling engines. The class pins the JVM default zone to UTC (production time utilities read
 * the default zone) and builds all timestamps in explicit UTC; all timestamps are built inside
 * instance code so none is captured before the zone rule runs.
 */
@Suppress("DEPRECATION_ERROR")
class CrossEngineParityMatrixTest : AnalyticsEngineTestBase() {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private const val CREATED_AT = 1_700_000_000_000L
    }

    private val database = mockk<AppDatabase>(relaxed = true)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cancelRepositoryScope() {
        repositoryScope.cancel()
    }

    // ------------------------------------------------------------------------------------
    // Fixtures and rig
    // ------------------------------------------------------------------------------------

    private class Scenario(
        val name: String,
        val categories: List<Category>,
        val expenses: List<Expense>,
        val nowMs: Long
    )

    private class Rig(
        val repository: ExpenseRepository,
        val insights: InsightsEngine,
        val pace: SpendingPaceCalculator,
        val advanced: AdvancedAnalyticsEngine,
        val totals: TotalsAggregationEngine,
        val dashboard: AdvancedAnalyticsDashboard,
        val budgetRepository: BudgetRepository,
        val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
        val currencyConverter: CurrencyConverter,
        val currencySettingsRepository: TestCurrencySettingsRepository
    )

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun purchase(
        id: Long,
        isoDate: String,
        categoryId: Long,
        amount: Double,
        merchant: String = "Merchant $id",
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        categoryId = categoryId,
        createdAt = CREATED_AT,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount
    )

    private fun income(isoDate: String, amount: Double): Expense = Expense(
        id = amount.toLong(),
        amount = amount,
        merchant = "Employer",
        transactionType = TransactionType.DEPOSIT,
        date = LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        createdAt = CREATED_AT
    )

    /** Categories of the former GoldenMaster contract fixture. */
    private fun contractCategories() = listOf(
        Category(id = 1L, name = "Food", icon = "F", color = "#FF5733"),
        Category(id = 2L, name = "Transport", icon = "T", color = "#33AAFF"),
        Category(id = 3L, name = "Entertainment", icon = "E", color = "#AA55FF"),
        Category(id = 4L, name = "Housing", icon = "H", color = "#00AA88"),
        Category(id = 5L, name = "Shopping", icon = "S", color = "#FFAA00"),
        Category(id = 6L, name = "Health", icon = "+", color = "#00CC66")
    )

    /** Categories of the former CrossGroupIntegration fixture. */
    private fun groupCategories() = listOf(
        Category(id = 1L, name = "Food", icon = "F", color = "#FF5733"),
        Category(id = 2L, name = "Transport", icon = "T", color = "#33AAFF"),
        Category(id = 3L, name = "Shopping", icon = "S", color = "#FFAA00"),
        Category(id = 4L, name = "Housing", icon = "H", color = "#00AA88")
    )

    private fun simpleMonthExpenses(): List<Expense> = listOf(
        purchase(1L, "2026-03-05", 1L, 10.0, "Coffee Shop"),
        purchase(2L, "2026-03-15", 2L, 20.0, "Grocery Store"),
        purchase(3L, "2026-03-25", 3L, 30.0, "Restaurant")
    )

    private fun weekSpreadExpenses(): List<Expense> = listOf(
        purchase(19L, "2026-03-02", 1L, 10.0, "Monday Shop"),
        purchase(20L, "2026-03-03", 2L, 20.0, "Tuesday Store"),
        purchase(21L, "2026-03-04", 2L, 30.0, "Wednesday Mart"),
        purchase(22L, "2026-03-05", 3L, 40.0, "Thursday Place"),
        purchase(23L, "2026-03-06", 3L, 50.0, "Friday Venue"),
        purchase(24L, "2026-03-07", 4L, 60.0, "Saturday Spot"),
        purchase(25L, "2026-03-08", 4L, 70.0, "Sunday Location")
    )

    private fun goldenTx(
        id: Long,
        date: String,
        merchant: String,
        categoryId: Long?,
        amount: Double,
        type: TransactionType = TransactionType.PURCHASE,
        isShared: Boolean = false,
        myShareAmount: Double? = null
    ): Expense = GoldenDataSets.createExpense(
        date = date,
        amount = amount,
        effectiveAmount = myShareAmount ?: amount,
        type = type,
        merchant = merchant,
        category = "Food & Dining",
        isSharedExpense = isShared,
        myShareAmount = myShareAmount
    ).copy(id = id, categoryId = categoryId)

    private fun goldenMasterExpenses(): List<Expense> = listOf(
        // March 2026
        goldenTx(1L, "2026-03-05", "Starbucks", 1L, 5.00),
        goldenTx(2L, "2026-03-08", "Uber", 2L, 15.00),
        goldenTx(3L, "2026-03-10", "Netflix", 3L, 12.99),
        goldenTx(4L, "2026-03-12", "Whole Foods", 1L, 85.50),
        goldenTx(5L, "2026-03-15", "Rent Payment", 4L, 800.00, isShared = true, myShareAmount = 400.0),
        goldenTx(6L, "2026-03-18", "Amazon", 5L, 45.00),
        goldenTx(7L, "2026-03-20", "Gas Station", 2L, 55.00),
        goldenTx(8L, "2026-03-22", "Restaurant", 1L, 60.00, isShared = true, myShareAmount = 30.0),
        goldenTx(9L, "2026-03-25", "Gym", 6L, 30.00),
        goldenTx(10L, "2026-03-28", "Salary", null, 3000.00, type = TransactionType.DEPOSIT),
        goldenTx(11L, "2026-03-30", "Utilities", 4L, 120.00, isShared = true, myShareAmount = 60.0),
        // February 2026 baseline
        goldenTx(12L, "2026-02-05", "Starbucks", 1L, 5.00),
        goldenTx(13L, "2026-02-10", "Netflix", 3L, 12.99),
        goldenTx(14L, "2026-02-12", "Whole Foods", 1L, 90.00),
        goldenTx(15L, "2026-02-15", "Rent Payment", 4L, 800.00, isShared = true, myShareAmount = 400.0),
        goldenTx(16L, "2026-02-18", "Amazon", 5L, 50.00),
        goldenTx(17L, "2026-02-20", "Gas Station", 2L, 50.00),
        goldenTx(18L, "2026-02-22", "Restaurant", 1L, 45.00),
        goldenTx(19L, "2026-02-25", "Gym", 6L, 30.00),
        goldenTx(20L, "2026-02-28", "Salary", null, 3000.00, type = TransactionType.DEPOSIT)
    )

    private fun simpleMonth(nowMs: Long) = Scenario("simple-month", testCategories, simpleMonthExpenses(), nowMs)

    private fun goldenMaster(nowMs: Long, expenses: List<Expense> = goldenMasterExpenses(), name: String = "golden-master") =
        Scenario(name, contractCategories(), expenses, nowMs)

    private fun rigFor(scenario: Scenario): Rig {
        mockCategories(scenario.categories)
        every { timeProvider.now() } returns scenario.nowMs
        stubDao(scenario.expenses, scenario.categories)

        val repository = ExpenseRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            database = database,
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            debugExpenseAuditWriter = mockk(relaxed = true)
        )
        val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        val currencySettingsRepository = TestCurrencySettingsRepository()
        val currencyConverter = testCurrencyConverter()
        val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer(currencyConverter)

        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns emptyList()

        val pace = SpendingPaceCalculator(timeProvider)
        val insights = InsightsEngine(
            expenseRepository = repository,
            recurringExpenseEngine = recurringExpenseEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = pace,
            anomalyDetector = AnomalyDetector(timeProvider = timeProvider),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer(),
            installmentEligibility = com.yourname.expensetracker.domain.analytics.noPlansInstallmentEligibility()
        )
        val advanced = AdvancedAnalyticsEngine(
            expenseRepository = repository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            timeProvider = timeProvider,
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )
        val multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository,
            applicationScope = repositoryScope
        )
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(null, any(), any(), any())
        } coAnswers {
            BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
                aggregate = multiCurrencyRepository.getHomeCurrencyPurchaseTotalAsOf(
                    secondArg(), arg<Long>(3), thirdArg()
                ),
                rateAsOfMillis = thirdArg()
            )
        }
        val totals = TotalsAggregationEngine(
            expenseRepository = repository,
            timeProvider = timeProvider,
            multiCurrencyRepository = multiCurrencyRepository,
            categoryRepository = categoryRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val dashboard = AdvancedAnalyticsDashboard(
            expenseDao = expenseDao,
            expenseRepository = repository,
            categoryRepository = categoryRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            timeProvider = timeProvider
        )
        return Rig(
            repository = repository,
            insights = insights,
            pace = pace,
            advanced = advanced,
            totals = totals,
            dashboard = dashboard,
            budgetRepository = budgetRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    private fun forecastEngineFor(
        rig: Rig,
        budgetRepository: BudgetRepository = rig.budgetRepository
    ): BudgetForecastingEngine {
        val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
        coEvery { budgetForecastDao.insert(any()) } returns 1L
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L
        return BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = rig.analyticsCurrencyNormalizer,
            expenseRepository = rig.repository,
            currencySettingsRepository = rig.currencySettingsRepository,
            currencyConverter = rig.currencyConverter,
            writeBarrier = mockk(relaxed = true)
        )
    }

    /**
     * Range-aware DAO fake. It models ONLY these parts of the real contract: range raw reads
     * return the owned (not isNotMine) rows of every type in `[start, end)` ordered by date then
     * id descending; the RANGE aggregate reads (getTotalForPeriod, getCountForPeriod,
     * getCategoryTotals*, getDaily/Weekly/Monthly totals, getTotalSpentBetween) cover owned
     * purchases only and use `effectiveAmount`.
     *
     * Known divergences from the real SQL (deliberately NOT modelled; do not rely on them):
     *  - getPurchaseCount, getOldestExpenseDate and getTotalSpentFlow ignore the real
     *    `isNotMine = 0` filter (they see every row in the fixture).
     *  - getCategoryBreakdown is an inner join on known categories: the real query LEFT JOINs
     *    categories (so a NULL / unknown-category bucket exists) and applies `LIMIT 10`.
     *  - The by-currency aggregates group on the stored currency; the real SQL groups on
     *    UPPER(currency) (fixtures use upper-case ISO codes only, so the two coincide here).
     *  - getMerchantStats / getTopMerchants* / largest-expense reads return empty / null.
     */
    private fun stubDao(expenses: List<Expense>, categories: List<Category>) {
        fun owned(start: Long, end: Long): List<Expense> = expenses
            .filter { it.date in start until end && !it.isNotMine }
            .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })

        fun purchases(start: Long, end: Long): List<Expense> =
            owned(start, end).filter { it.transactionType == TransactionType.PURCHASE }

        fun categoryTotals(start: Long, end: Long): List<CategoryTotal> =
            purchases(start, end)
                .filter { it.categoryId != null }
                .groupBy { it.categoryId!! }
                .map { (categoryId, rows) ->
                    CategoryTotal(categoryId = categoryId, total = rows.sumOf { it.effectiveAmount }, txCount = rows.size)
                }
                .sortedByDescending { it.total }

        fun categoryBreakdown(start: Long, end: Long): List<CategoryTotalResult> =
            purchases(start, end)
                .filter { row -> row.categoryId != null && categories.any { it.id == row.categoryId } }
                .groupBy { it.categoryId!! }
                .map { (categoryId, rows) ->
                    val cat = categories.first { it.id == categoryId }
                    CategoryTotalResult(
                        id = categoryId,
                        name = cat.name,
                        icon = cat.icon,
                        color = cat.color,
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
                .sortedByDescending { it.total }

        fun dailyTotals(start: Long, end: Long): List<DailyTotal> =
            purchases(start, end)
                .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
                .toSortedMap()
                .map { (dayStart, rows) ->
                    DailyTotal(
                        dayEpoch = dayStart,
                        startDate = dayStart,
                        endDate = TimePeriodUtils.addDays(dayStart, 1),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }

        fun monthlyTotals(start: Long, end: Long): List<MonthlyTotal> =
            purchases(start, end)
                .groupBy {
                    val y = TimePeriodUtils.getYear(it.date)
                    val m = TimePeriodUtils.getMonth(it.date) + 1
                    "%04d-%02d".format(y, m)
                }
                .map { (monthKey, rows) ->
                    val monthStart = TimePeriodUtils.getStartOfMonth(rows.minOf { it.date })
                    MonthlyTotal(
                        monthKey = monthKey,
                        startDate = monthStart,
                        endDate = TimePeriodUtils.getEndOfMonth(monthStart),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }

        fun weeklyTotals(start: Long, end: Long): List<WeeklyTotal> =
            purchases(start, end)
                .groupBy { TimePeriodUtils.getAppCalendarWeekKey(TimePeriodUtils.getStartOfWeek(it.date)) }
                .map { (weekKey, rows) ->
                    val weekStart = TimePeriodUtils.getStartOfWeek(rows.minOf { it.date })
                    WeeklyTotal(
                        weekKey = weekKey,
                        startDate = weekStart,
                        endDate = TimePeriodUtils.addDays(weekStart, 7),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }

        coEvery { expenseDao.getExpensesBetween(any(), any()) } answers { owned(firstArg(), secondArg()) }
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } answers { owned(firstArg(), secondArg()) }
        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), TransactionType.PURCHASE.name) } answers {
            purchases(firstArg(), secondArg())
        }
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } answers { flowOf(owned(firstArg(), secondArg())) }
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } answers { flowOf(owned(firstArg(), secondArg())) }
        every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), TransactionType.PURCHASE.name) } answers {
            flowOf(purchases(firstArg(), secondArg()))
        }
        every { expenseDao.getAllFlowUncapped() } answers { flowOf(expenses) }
        every { expenseDao.getAllFlow(any()) } answers { flowOf(expenses) }

        coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
            purchases(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCountForPeriod(any(), any()) } answers { purchases(firstArg(), secondArg()).size }
        coEvery { expenseDao.getCategoryTotalsForPeriod(any(), any()) } answers { categoryTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getCategoryBreakdown(any(), any()) } answers { categoryBreakdown(firstArg(), secondArg()) }
        coEvery { expenseDao.getDailyTotalsWithDatesForPeriod(any(), any()) } answers { dailyTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getDailyTotalsForPeriod(any(), any()) } answers { dailyTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getWeeklyTotalsForPeriod(any(), any()) } answers { weeklyTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getMonthlyTotalsForPeriod(any(), any()) } answers { monthlyTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getAverageDailySpend(any(), any()) } answers {
            dailyTotals(firstArg(), secondArg()).map { it.total }.average().takeIf { !it.isNaN() }
        }
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } answers {
            purchases(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCategoryTotalsBetween(any(), any()) } answers { categoryTotals(firstArg(), secondArg()) }
        coEvery { expenseDao.getAll() } returns expenses
        coEvery { expenseDao.getPurchaseCount() } returns expenses.count { it.transactionType == TransactionType.PURCHASE }
        coEvery { expenseDao.getOldestExpenseDate() } returns expenses.minOfOrNull { it.date }
        every { expenseDao.getTotalSpentFlow() } returns flowOf(
            expenses.filter { it.transactionType == TransactionType.PURCHASE }.sumOf { it.effectiveAmount }
        )
        every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } answers {
            purchases(firstArg(), secondArg())
                .groupBy { it.currency }
                .map { (currency, rows) -> CurrencyTotal(currency, rows.sumOf { it.effectiveAmount }, rows.size) }
        }
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } answers {
            purchases(firstArg(), secondArg())
                .filter { it.categoryId != null }
                .groupBy { it.categoryId!! }
                .flatMap { (catId, rows) ->
                    rows.groupBy { it.currency.ifEmpty { "EUR" } }.map { (currency, currRows) ->
                        CategoryCurrencyTotal(
                            categoryId = catId,
                            currency = currency,
                            total = currRows.sumOf { it.effectiveAmount },
                            txCount = currRows.size
                        )
                    }
                }
        }
        coEvery { expenseDao.getMerchantStats() } returns emptyList()
        coEvery { expenseDao.getAllMerchantStats() } returns emptyList()
        coEvery { expenseDao.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseDao.getLargestExpenseForMerchant(any(), any(), any()) } returns null
        coEvery { expenseDao.getLargestExpenseForPeriod(any(), any()) } returns null
        coEvery { expenseDao.getExpensesSince(any()) } answers {
            expenses.filter { it.date >= firstArg<Long>() && !it.isNotMine }
        }
    }

    // ------------------------------------------------------------------------------------
    // Matrix 1: monthly total
    // ------------------------------------------------------------------------------------

    private enum class MonthlySource {
        REPOSITORY, INSIGHTS, ADVANCED_CATEGORY_SUM, TOTALS_DAILY_SUM, TOTALS_CATEGORY_SUM, DASHBOARD, PACE_CURRENT_MONTH
    }

    private class MonthlyRow(
        val name: String,
        val scenario: Scenario,
        val startMs: Long,
        val endMs: Long,
        val expected: Double,
        val sources: List<MonthlySource>
    )

    private suspend fun monthlyValue(rig: Rig, row: MonthlyRow, source: MonthlySource): Double = when (source) {
        MonthlySource.REPOSITORY -> requireNotNull(rig.repository.getTotalForPeriod(row.startMs, row.endMs))
        MonthlySource.INSIGHTS -> requireNotNull(
            rig.insights.generateInsights(row.scenario.categories, row.scenario.expenses, "EUR")
                .monthlyComparison.currentTotal
        )
        MonthlySource.ADVANCED_CATEGORY_SUM -> rig.advanced.getCategoryAnalytics(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, row.startMs, row.endMs, row.name, null), "EUR"
        ).first.sumOf { it.totalSpent }
        MonthlySource.TOTALS_DAILY_SUM ->
            rig.totals.getDailyTotalsForRange(row.startMs, row.endMs).first().sumOf { it.totalAmount }
        MonthlySource.TOTALS_CATEGORY_SUM ->
            rig.totals.getCategoryBreakdown(row.startMs, row.endMs, row.name).first().sumOf { it.totalAmount }
        MonthlySource.DASHBOARD -> rig.dashboard.generateDashboardData(row.startMs, row.endMs).totalSpent
        MonthlySource.PACE_CURRENT_MONTH -> requireNotNull(
            rig.pace.calculate(
                currentMonthStart = row.startMs,
                previousMonthStart = utcMs(2026, 2, 1),
                previousMonthEnd = row.startMs,
                allExpenses = row.scenario.expenses.toExpenseSnapshots(),
                displayCurrency = "EUR"
            ).currentMonthSpent
        )
    }

    @Test
    fun `monthly total parity matrix - every engine equals the hand-computed oracle and its siblings`() = runTest {
        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)
        val golden = goldenMasterExpenses()

        // Fixture sanity (hand arithmetic on the fixture, not production): the deposit and the
        // raw shared-expense amounts exist, so a parity pass cannot be an artefact of them being absent.
        assertMoneyEquals(
            3000.0,
            golden.filter { it.transactionType == TransactionType.DEPOSIT && it.date in march1 until april1 }.sumOf { it.amount },
            "golden-master fixture: March deposit negative control"
        )
        assertMoneyEquals(
            1228.49,
            golden.filter { it.transactionType == TransactionType.PURCHASE && it.date in march1 until april1 }.sumOf { it.amount },
            "golden-master fixture: raw March purchase amount (effective total must be lower)"
        )

        val rows = listOf(
            MonthlyRow(
                "simple-month Mar 15", simpleMonth(utcMs(2026, 3, 15)), march1, april1, 60.0,
                listOf(MonthlySource.REPOSITORY, MonthlySource.INSIGHTS, MonthlySource.ADVANCED_CATEGORY_SUM, MonthlySource.DASHBOARD)
            ),
            MonthlyRow(
                "golden-master Mar 30 (effective amounts, deposit excluded)", goldenMaster(utcMs(2026, 3, 30)), march1, april1, 738.49,
                listOf(MonthlySource.INSIGHTS, MonthlySource.ADVANCED_CATEGORY_SUM, MonthlySource.TOTALS_DAILY_SUM)
            ),
            MonthlyRow(
                "golden-master Apr 1 (dashboard uses effective amount)", goldenMaster(utcMs(2026, 4, 1)), march1, april1, 738.49,
                listOf(MonthlySource.ADVANCED_CATEGORY_SUM, MonthlySource.DASHBOARD)
            ),
            MonthlyRow(
                "half-open boundary Mar 20 (start included, end excluded)",
                Scenario(
                    "half-open", groupCategories(),
                    listOf(
                        purchase(1, "2026-03-01", 1, 100.0), // included (start boundary)
                        purchase(2, "2026-03-15", 2, 50.0), // included
                        purchase(3, "2026-04-01", 1, 900.0) // excluded (end boundary)
                    ),
                    utcMs(2026, 3, 20)
                ),
                march1, april1, 150.0,
                listOf(MonthlySource.INSIGHTS, MonthlySource.TOTALS_DAILY_SUM, MonthlySource.ADVANCED_CATEGORY_SUM, MonthlySource.DASHBOARD)
            ),
            MonthlyRow(
                "complete-month Mar 30 (Feb rows excluded)",
                Scenario(
                    "complete-month", groupCategories(),
                    listOf(
                        purchase(1, "2026-02-04", 1, 40.0),
                        purchase(2, "2026-02-12", 2, 50.0),
                        purchase(3, "2026-03-02", 1, 20.0),
                        purchase(4, "2026-03-06", 1, 24.0),
                        purchase(5, "2026-03-10", 1, 22.0),
                        purchase(6, "2026-03-14", 1, 25.0),
                        purchase(7, "2026-03-18", 1, 23.0),
                        purchase(8, "2026-03-22", 1, 300.0),
                        purchase(9, "2026-03-26", 2, 60.0)
                    ),
                    utcMs(2026, 3, 30)
                ),
                march1, april1, 474.0,
                listOf(
                    MonthlySource.INSIGHTS, MonthlySource.ADVANCED_CATEGORY_SUM, MonthlySource.TOTALS_DAILY_SUM,
                    MonthlySource.TOTALS_CATEGORY_SUM, MonthlySource.PACE_CURRENT_MONTH
                )
            )
        )
        assertEquals("monthly matrix must keep all 5 rows", 5, rows.size)

        for (row in rows) {
            val rig = rigFor(row.scenario)
            val values = row.sources.associateWith { monthlyValue(rig, row, it) }
            assertEquals("${row.name}: every declared source must produce a value", row.sources.size, values.size)
            values.forEach { (source, value) ->
                assertMoneyEquals(row.expected, value, "${row.name} [$source] vs oracle")
            }
            val reference = values.entries.first()
            values.entries.drop(1).forEach { (source, value) ->
                assertApproxEquals(reference.value, value, 0.01, "${row.name}: ${reference.key} vs $source: ")
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Matrix 2: daily average and statistical insights
    // ------------------------------------------------------------------------------------

    private enum class AverageSource { MANUAL_REPOSITORY_OVER_DAYS, ADVANCED_STATISTICAL_INSIGHTS, TOTALS_DAILY_SUM_OVER_DAYS }

    private class AverageRow(
        val name: String,
        val scenario: Scenario,
        val period: AnalyticsPeriod,
        val startMs: Long,
        val endMs: Long,
        val periodDays: Int,
        val expectedAverage: Double,
        val sources: List<AverageSource>
    )

    @Test
    fun `daily average and statistics parity matrix - period-day denominator across engines`() = runTest {
        val rows = listOf(
            AverageRow(
                "week-spread Mar 2-8 (7 days)", Scenario("week-spread", testCategories, weekSpreadExpenses(), utcMs(2026, 4, 1)),
                AnalyticsPeriod.WEEK, utcMs(2026, 3, 2), utcMs(2026, 3, 9), 7, 40.0,
                listOf(
                    AverageSource.MANUAL_REPOSITORY_OVER_DAYS, AverageSource.ADVANCED_STATISTICAL_INSIGHTS,
                    AverageSource.TOTALS_DAILY_SUM_OVER_DAYS
                )
            ),
            AverageRow(
                "golden-master Mar 1-30 (30 days)", goldenMaster(utcMs(2026, 4, 1)),
                AnalyticsPeriod.CUSTOM, utcMs(2026, 3, 1), utcMs(2026, 3, 31), 30, 24.62,
                listOf(
                    AverageSource.MANUAL_REPOSITORY_OVER_DAYS, AverageSource.ADVANCED_STATISTICAL_INSIGHTS,
                    AverageSource.TOTALS_DAILY_SUM_OVER_DAYS
                )
            )
        )
        assertEquals("average matrix must keep both rows", 2, rows.size)

        for (row in rows) {
            val startDate = Instant.ofEpochMilli(row.startMs).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(row.endMs).atZone(ZoneOffset.UTC).toLocalDate()
            assertEquals("${row.name}: hard-coded denominator must match the calendar span", row.periodDays.toLong(), ChronoUnit.DAYS.between(startDate, endDate))

            val rig = rigFor(row.scenario)
            val values = row.sources.associateWith { source ->
                when (source) {
                    AverageSource.MANUAL_REPOSITORY_OVER_DAYS ->
                        requireNotNull(rig.repository.getTotalForPeriod(row.startMs, row.endMs)) / row.periodDays
                    AverageSource.ADVANCED_STATISTICAL_INSIGHTS -> rig.advanced.getStatisticalInsights(
                        AnalyticsPeriodRange(row.period, row.startMs, row.endMs, row.name, null), "EUR"
                    ).first.averageDailySpend
                    AverageSource.TOTALS_DAILY_SUM_OVER_DAYS ->
                        rig.totals.getDailyTotalsForRange(row.startMs, row.endMs).first().sumOf { it.totalAmount } / row.periodDays
                }
            }
            values.forEach { (source, value) ->
                assertMoneyEquals(row.expectedAverage, value, "${row.name} [$source] vs oracle")
            }
            val manual = values.getValue(AverageSource.MANUAL_REPOSITORY_OVER_DAYS)
            values.forEach { (source, value) ->
                assertApproxEquals(manual, value, 0.01, "${row.name}: manual vs $source: ")
            }
        }

        // Single transaction: the denominator is the period (30 days), not the one spending day.
        // (Merges GoldenMaster "single transaction honors period-day denominator" and
        // "statistical insights handle single transaction"; max/days assertions were shared.)
        val single = Scenario(
            "single-transaction", contractCategories(),
            listOf(
                GoldenDataSets.createExpense(
                    date = "2026-03-15", amount = 100.0, effectiveAmount = 100.0, merchant = "Single", category = "Food & Dining"
                ).copy(id = 999L, categoryId = 1L)
            ),
            utcMs(2026, 4, 1)
        )
        val stats = rigFor(single).advanced.getStatisticalInsights(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, utcMs(2026, 3, 1), utcMs(2026, 3, 31), "Mar 1-30", null), "EUR"
        ).first
        assertMoneyEquals(100.0, stats.maxDailySpend, "single-transaction: max daily spend")
        assertMoneyEquals(3.33, stats.averageDailySpend, "single-transaction: 100.00 / 30 days")
        assertMoneyEquals(100.0, stats.meanTransaction, "single-transaction: mean transaction")
        assertMoneyEquals(100.0, stats.medianTransaction, "single-transaction: median transaction")
        assertEquals("single-transaction: days with spending", 1, stats.daysWithSpending)
    }

    // ------------------------------------------------------------------------------------
    // Matrix 3: category totals
    // ------------------------------------------------------------------------------------

    @Test
    fun `category total parity matrix - repository insights dashboard and totals engine agree with the oracle`() = runTest {
        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)

        // Row 1: simple-month, keyed by category id; repository / insights / dashboard.
        val simple = simpleMonth(utcMs(2026, 3, 15))
        val simpleRig = rigFor(simple)
        val expectedById = mapOf(1L to 10.0, 2L to 20.0, 3L to 30.0)
        assertEquals("simple-month category oracle must keep 3 rows", 3, expectedById.size)

        val repoTotals = simpleRig.repository.getCategoryTotalsForPeriod(march1, april1).associate { it.categoryId to it.total }
        val insightTotals = simpleRig.insights.generateInsights(simple.categories, simple.expenses, "EUR")
            .categoryInsights.associate { it.category.id to it.currentTotal }
        val dashboardTotals = simpleRig.dashboard.generateDashboardData(march1, april1)
            .topCategories.associate { it.categoryId to it.amount }

        assertEquals("simple-month: repository category keys", expectedById.keys, repoTotals.keys)
        assertEquals("simple-month: insights category keys", expectedById.keys, insightTotals.keys)
        assertEquals("simple-month: dashboard category keys", expectedById.keys, dashboardTotals.keys)
        expectedById.forEach { (categoryId, amount) ->
            assertMoneyExact(amount, repoTotals[categoryId], "simple-month category=$categoryId [repository pass-through]")
            assertMoneyEquals(amount, insightTotals[categoryId], "simple-month category=$categoryId [insights]")
            assertMoneyEquals(amount, dashboardTotals[categoryId], "simple-month category=$categoryId [dashboard]")
        }

        // Row 2: golden-master, keyed by category name; insights + totals engine + percentage conservation.
        val golden = goldenMaster(utcMs(2026, 3, 30))
        val goldenRig = rigFor(golden)
        val insightMap = goldenRig.insights.generateInsights(golden.categories, golden.expenses, "EUR")
            .categoryInsights.associateBy { it.category.name }
        val totalsMap = goldenRig.totals.getCategoryBreakdown(march1, april1, "Mar 2026").first().associateBy { it.category.name }

        val expectedByName = listOf(
            "Food" to 120.50, // 5.00 + 85.50 + 30.00 (shared share)
            "Transport" to 70.00, // 15.00 + 55.00
            "Entertainment" to 12.99,
            "Housing" to 460.00, // 400.00 + 60.00 (shared shares)
            "Shopping" to 45.00,
            "Health" to 30.00
        )
        assertEquals("golden-master category oracle must keep 6 rows", 6, expectedByName.size)
        expectedByName.forEach { (name, amount) ->
            assertMoneyEquals(amount, insightMap.getValue(name).currentTotal, "golden-master category=$name [insights]")
        }
        assertMoneyEquals(460.00, totalsMap.getValue("Housing").totalAmount, "golden-master category=Housing [totals engine]")
        assertApproxEquals(
            100.0, insightMap.values.sumOf { requireNotNull(it.percentageOfTotal).toDouble() }, 0.1,
            "golden-master insights percentages must sum to 100: "
        )
        assertApproxEquals(
            100.0, totalsMap.values.sumOf { it.percentageOfTotal.toDouble() }, 0.1,
            "golden-master totals-engine percentages must sum to 100: "
        )
    }

    // ------------------------------------------------------------------------------------
    // Matrix 4: spending pace
    // ------------------------------------------------------------------------------------

    private class PaceRow(
        val name: String,
        val scenario: Scenario,
        val expectedPace: Float,
        val paceTolerance: Float,
        val expectedStatus: PaceStatus?,
        val expectedSpent: Double?,
        val expectedDaysElapsed: Int?,
        val expectedPreviousTotal: Double?,
        val expectedProjection: Double?
    )

    @Test
    fun `spending pace parity matrix - insights and calculator agree with canonical oracle and sentinel`() = runTest {
        val march1 = utcMs(2026, 3, 1)
        val feb1 = utcMs(2026, 2, 1)
        val golden = goldenMasterExpenses()

        val rows = listOf(
            // Canonical: pace% = (currentDailyRate / baselineDailyRate) * 100; only Mar 5 and Mar 15 have
            // occurred (Mar 25 is future-dated). (30 / 15) / (40 / 28) * 100 = 140 exactly.
            PaceRow(
                "two-month Mar 15 noon",
                Scenario("two-month", testCategories, simpleMonthExpenses() + listOf(
                    purchase(9L, "2026-02-05", 1L, 15.0, "Coffee Shop"),
                    purchase(10L, "2026-02-15", 2L, 25.0, "Grocery Store")
                ), utcMs(2026, 3, 15, 12)),
                140.0f, 0.01f, null, 30.0, 15, 40.0, null
            ),
            // Hand arithmetic: current-month spend consistent with the oracle is 678.49 (738.49 less the
            // 60.00 Utilities share dated at the clock instant), over 30 days elapsed:
            // (678.49 / 30) / (682.99 / 28) * 100 = 92.72 and 678.49 * 31 / 30 = 701.11.
            PaceRow(
                "golden-master Mar 30", goldenMaster(utcMs(2026, 3, 30), golden),
                92.72f, 0.1f, PaceStatus.ON_PACE, null, null, null, 701.11
            ),
            PaceRow(
                "golden-master March-only (no February baseline)",
                goldenMaster(utcMs(2026, 3, 30), golden.filter { it.date in march1 until utcMs(2026, 4, 1) }, "golden-master-march-only"),
                -1f, 0f, PaceStatus.NO_BASELINE, null, null, null, null
            ),
            PaceRow(
                "simple-month Mar 15 (no February baseline)", simpleMonth(utcMs(2026, 3, 15)),
                -1f, 0f, PaceStatus.NO_BASELINE, null, null, null, null
            )
        )
        assertEquals("pace matrix must keep all 4 rows", 4, rows.size)

        for (row in rows) {
            val rig = rigFor(row.scenario)
            val insightsPace = rig.insights.generateInsights(row.scenario.categories, row.scenario.expenses, "EUR").spendingPace
            val calculatorPace = rig.pace.calculate(
                currentMonthStart = march1,
                previousMonthStart = feb1,
                previousMonthEnd = march1,
                allExpenses = row.scenario.expenses.toExpenseSnapshots(),
                displayCurrency = "EUR"
            )
            for ((label, pace) in listOf("insights" to insightsPace, "calculator" to calculatorPace)) {
                val rowLabel = "${row.name} [$label]"
                if (row.expectedPace == -1f) {
                    // NEW-P6-013: canonical -1f sentinel; 0f is indistinguishable from a true 0% pace.
                    assertEquals("$rowLabel pace sentinel", -1f, pace.pacePercentage, 0f)
                } else {
                    assertApproxEquals(row.expectedPace, pace.pacePercentage, row.paceTolerance, "$rowLabel pace%: ")
                }
                row.expectedStatus?.let { assertEquals("$rowLabel pace status", it, pace.paceStatus) }
                row.expectedSpent?.let { assertMoneyEquals(it, pace.currentMonthSpent, "$rowLabel current month spent") }
                row.expectedDaysElapsed?.let { assertEquals("$rowLabel days elapsed", it, pace.daysElapsed) }
                row.expectedPreviousTotal?.let { assertMoneyEquals(it, pace.previousMonthTotal, "$rowLabel previous month total") }
                row.expectedProjection?.let { assertMoneyEquals(it, pace.projectedTotal, "$rowLabel linear projection") }
            }
            assertApproxEquals(
                insightsPace.pacePercentage, calculatorPace.pacePercentage, maxOf(row.paceTolerance, 0.01f),
                "${row.name}: insights vs calculator pace%: "
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Matrix 5: transaction count and anomaly detection
    // ------------------------------------------------------------------------------------

    private class AnomalyRow(
        val name: String,
        val scenario: Scenario,
        val requireAnomalyAbove: Double?,
        val requireIdsInDataset: Boolean
    )

    @Test
    fun `transaction count and anomaly matrix`() = runTest {
        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)

        // Count: repository rows == advanced histogram == totals-engine daily counts == 3.
        val rig = rigFor(simpleMonth(utcMs(2026, 4, 1)))
        val repoCount = rig.repository.getExpensesBetween(march1, april1).size
        val advancedCount = rig.advanced.getStatisticalInsights(
            AnalyticsPeriodRange(AnalyticsPeriod.MONTH, march1, april1, "Mar 2026", null), "EUR"
        ).first.histogramBins.sumOf { it.count }
        val totalsCount = rig.totals.getDailyTotalsForRange(march1, april1).first().sumOf { it.transactionCount }
        assertEquals("simple-month: repository row count", 3, repoCount)
        assertEquals("simple-month: advanced histogram count", 3, advancedCount)
        assertEquals("simple-month: totals-engine transaction count", 3, totalsCount)

        // Anomalies. Fixtures for the outlier rows.
        val golden = goldenMasterExpenses()
        val extraFood = listOf(
            GoldenDataSets.createExpense(date = "2026-03-02", amount = 8.0, effectiveAmount = 8.0, merchant = "Extra Food 1", category = "Food")
                .copy(id = 100L, categoryId = 1L),
            GoldenDataSets.createExpense(date = "2026-03-07", amount = 12.0, effectiveAmount = 12.0, merchant = "Extra Food 2", category = "Food")
                .copy(id = 101L, categoryId = 1L)
        )
        val luxury = GoldenDataSets.createExpense(
            date = "2026-03-16", amount = 5000.0, effectiveAmount = 5000.0, merchant = "Luxury Purchase", category = "Food"
        ).copy(id = 999L, categoryId = 1L)

        val rows = listOf(
            AnomalyRow("golden-master baseline", goldenMaster(utcMs(2026, 3, 30)), null, false),
            // Food now has 6 samples (3 + 2 extra + outlier) >= MIN_SAMPLES_GLOBAL (5), so IQR detection applies.
            AnomalyRow("golden-master + 5000.00 Food outlier", goldenMaster(utcMs(2026, 3, 30), golden + extraFood + luxury), 1000.0, true),
            AnomalyRow(
                "tight cluster + 300.00 outlier",
                Scenario(
                    "cluster", groupCategories(),
                    listOf(
                        purchase(1, "2026-03-01", 1, 20.0),
                        purchase(2, "2026-03-04", 1, 24.0),
                        purchase(3, "2026-03-08", 1, 22.0),
                        purchase(4, "2026-03-12", 1, 25.0),
                        purchase(5, "2026-03-15", 1, 23.0),
                        purchase(6, "2026-03-20", 1, 300.0)
                    ),
                    utcMs(2026, 3, 30)
                ),
                // Pinned to the 300.00 outlier: every other row is <= 25.00, so only the outlier
                // can exceed 250.0 (a vacuous > 0.0 would pass for any anomaly at all).
                250.0, true
            ),
            AnomalyRow(
                "complete-month dataset",
                Scenario(
                    "complete-month", groupCategories(),
                    listOf(
                        purchase(1, "2026-02-04", 1, 40.0),
                        purchase(2, "2026-02-12", 2, 50.0),
                        purchase(3, "2026-03-02", 1, 20.0),
                        purchase(4, "2026-03-06", 1, 24.0),
                        purchase(5, "2026-03-10", 1, 22.0),
                        purchase(6, "2026-03-14", 1, 25.0),
                        purchase(7, "2026-03-18", 1, 23.0),
                        purchase(8, "2026-03-22", 1, 300.0),
                        purchase(9, "2026-03-26", 2, 60.0)
                    ),
                    utcMs(2026, 3, 30)
                ),
                null, true
            )
        )
        assertEquals("anomaly matrix must keep all 4 rows", 4, rows.size)

        for (row in rows) {
            val anomalies = rigFor(row.scenario).insights
                .generateInsights(row.scenario.categories, row.scenario.expenses, "EUR").anomalies
            anomalies.forEach {
                assertTrue("${row.name}: anomaly must carry a positive effective amount", it.expense.effectiveAmount > 0)
            }
            row.requireAnomalyAbove?.let { threshold ->
                assertTrue(
                    "${row.name}: an anomaly above $threshold must be detected",
                    anomalies.any { it.expense.effectiveAmount > threshold }
                )
            }
            if (row.requireIdsInDataset) {
                val ids = row.scenario.expenses.map { it.id }.toSet()
                assertTrue("${row.name}: anomalies must come from the supplied dataset", anomalies.all { it.expense.id in ids })
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Matrix 6: forecasts and savings
    // ------------------------------------------------------------------------------------

    @Test
    fun `forecast and savings matrix - trend diverges from linear and savings follows weighted components`() = runTest {
        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)
        val now = utcMs(2026, 3, 30)

        // Row 1: trend forecast vs linear pace projection (different contracts must not coincide).
        // Merges GoldenMaster "DIVERGENCE - linear pace projection differs from trend forecast" and
        // "DIVERGENCE - trend-adjusted forecast differs from linear projection" (survivor: the first,
        // which also carried the 701.11 oracle; that oracle now lives in the pace matrix).
        val golden = goldenMaster(now)
        val goldenRig = rigFor(golden)
        val linearProjection = requireNotNull(
            goldenRig.insights.generateInsights(golden.categories, golden.expenses, "EUR").spendingPace.projectedTotal
        )
        val trendProjection = forecastEngineFor(goldenRig).generateForecast(
            budget = Budget(categoryId = null, amount = 5000.0, period = BudgetPeriod.MONTHLY, startDate = march1),
            forecastPeriodDays = 30
        ).predictedSpending
        assertTrue("Trend forecast must use non-empty spending history, not a zero mock default", trendProjection > 0.0)
        assertTrue(
            "Trend forecast should diverge from linear projection (different contract). linear=$linearProjection trend=$trendProjection",
            abs(trendProjection - linearProjection) > 0.01
        )

        // Row 2: forecast over a rising 3-month history is realistic and bounded.
        val history = Scenario(
            "rising-history", groupCategories(),
            listOf(
                purchase(1, "2026-01-10", 1, 300.0),
                purchase(2, "2026-02-10", 1, 350.0),
                purchase(3, "2026-03-10", 1, 400.0)
            ),
            now
        )
        val historyRig = rigFor(history)
        // STRICT budget repository (not relaxed): the only call the forecast may make is the
        // period-end spend aggregate, delegated to the rig's real-aggregate stub. Any other
        // BudgetRepository call throws instead of silently returning a relaxed default.
        val strictBudgetRepository = mockk<BudgetRepository>()
        coEvery {
            strictBudgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        } coAnswers {
            historyRig.budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(
                firstArg(), secondArg(), thirdArg(), arg(3)
            )
        }
        val forecast = forecastEngineFor(historyRig, strictBudgetRepository).generateForecast(
            budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = march1, createdAt = now),
            forecastPeriodDays = 30
        )
        assertTrue("rising-history: predicted spending must be positive", forecast.predictedSpending > 0.0)
        assertTrue("rising-history: predicted spending must be finite", forecast.predictedSpending.isFinite())
        assertTrue("rising-history: confidence in 0..1", forecast.confidenceScore in 0.0..1.0)
        assertTrue("rising-history: overspend probability in 0..1", forecast.overspendProbability in 0.0..1.0)

        // Row 3: savings recommendation follows the deterministic weighted components.
        // Budget surplus 61.51 (800.00 - 738.49) * 0.5 conservative = 30.755; weighted (pace=0, MC=0) 30.755 * 0.4 = 12.302.
        val savingsRig = rigFor(golden)
        val budgetStatus = BudgetStatus(
            budget = Budget(categoryId = null, amount = 800.00, period = BudgetPeriod.MONTHLY, startDate = march1),
            category = null,
            spentAmount = 738.49,
            remainingAmount = 61.51,
            percentUsed = ((738.49 / 800.00) * 100.0).toFloat(),
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = march1,
            periodEnd = april1,
            effectiveLimit = 0.0,
        )
        every { savingsRig.budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus))
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns MonteCarloResult(
            percentile10 = 2000.0,
            percentile25 = 2000.0,
            percentile50 = 2000.0,
            percentile75 = 2000.0,
            percentile90 = 2000.0,
            probabilityUnderBudget = null,
            budgetAmount = null,
            spentToDate = 738.49,
            knownUpcoming = 0.0,
            confidence = SimulationConfidence(0.8, ConfidenceLevel.HIGH, "deterministic"),
            metadata = SimulationMetadata(0, 0, 1000, 0.0, 0.0, 1, now),
            displayCurrency = "EUR"
        )
        val smartSavingsCategoryRepository = mockk<com.yourname.expensetracker.data.repository.CategoryRepository>(relaxed = true)
        coEvery { smartSavingsCategoryRepository.getAll() } returns golden.categories
        val smartSavingsEngine = SmartSavingsEngine(
            expenseRepository = savingsRig.repository,
            categoryRepository = smartSavingsCategoryRepository,
            budgetRepository = savingsRig.budgetRepository,
            budgetCalculator = mockk<BudgetCalculator>(relaxed = true),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = savingsRig.analyticsCurrencyNormalizer,
            cashFlowCalculator = mockk(relaxed = true),
            spendingThresholdCalculator = mockk(relaxed = true)
        )
        val recommendation = smartSavingsEngine.calculateSafeToSaveAmount(
            SavingsGoal(
                id = 1L,
                name = "Emergency",
                targetAmount = 5000.0,
                currentAmount = 1000.0,
                targetDate = null,
                protectionLevel = GoalProtectionLevel.STRICT,
                currencyAssumption = "EXPLICIT",
                createdAt = now
            ),
            homeCurrency = "EUR"
        )
        assertMoneyEquals(12.30, recommendation.safeAmount, "savings recommendation: 61.51 * 0.5 * 0.4")
        assertApproxEquals(0.60, recommendation.confidence, 0.001, "savings recommendation confidence: ")
    }

    // ------------------------------------------------------------------------------------
    // Matrix 7: cross-domain integration (shared, carbon, lifestyle, empty)
    // ------------------------------------------------------------------------------------

    @Test
    fun `cross domain integration matrix - shared share carbon totals lifestyle pace and empty paths`() = runTest {
        assertSharedShareFlowsIntoMonthlyTotals()
        assertCarbonByCategoryMatchesFactorOracle()
        assertLifestyleCreepAgreesWithPaceOverBaseline()
        assertEmptyDatasetZeroesEveryIntegrationPath()
    }

    private suspend fun assertSharedShareFlowsIntoMonthlyTotals() {
        val sharedExpenseDataPort = mockk<SharedExpenseDataPort>(relaxed = true)
        coEvery { sharedExpenseDataPort.getGroupOnce(any()) } returns mockk(relaxed = true)
        val manager = SharedExpenseManager(sharedExpenseDataPort, timeProvider, currencySettingsRepository = mockk(), ioDispatcher = Dispatchers.Unconfined)
        coEvery { sharedExpenseDataPort.getGroupMembersOnce(1L) } returns listOf(
            SharedExpenseMember(id = 1L, groupId = 1L, name = "Me"),
            SharedExpenseMember(id = 2L, groupId = 1L, name = "Bob"),
            SharedExpenseMember(id = 3L, groupId = 1L, name = "Carol")
        )
        coEvery { sharedExpenseDataPort.getGroupExpensesOnce(1L) } returns listOf(
            SharedGroupExpense(
                id = 1L,
                groupId = 1L,
                expenseId = 101L,
                paidById = 1L,
                date = utcMs(2026, 3, 5),
                description = "Rent",
                totalAmount = 120.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )
        val myShare = manager.calculateBalances(1L).getValue(1L).shouldPay
        val sharedExpense = purchase(
            id = 101, isoDate = "2026-03-05", categoryId = 4, amount = 120.0,
            isSharedExpense = true, myShareAmount = myShare, merchant = "Rent Payment"
        )

        val rig = rigFor(Scenario("shared-share", groupCategories(), listOf(sharedExpense), utcMs(2026, 4, 1)))
        val total = rig.totals.getDailyTotalsForRange(utcMs(2026, 3, 1), utcMs(2026, 4, 1)).first().sumOf { it.totalAmount }

        // 120.00 split three equal ways: my share 40.00 is what the monthly total counts (not the 120.00 bill).
        assertMoneyEquals(40.0, myShare, "shared expense: my equal share of 120.00 / 3")
        assertMoneyEquals(40.0, total, "shared expense: monthly total counts my share only")
    }

    private suspend fun assertCarbonByCategoryMatchesFactorOracle() {
        val expenses = listOf(
            purchase(1, "2026-03-10", 2, 10.0, merchant = "SHELL"),
            purchase(2, "2026-03-11", 3, 20.0, merchant = "ZARA"),
            purchase(3, "2026-03-12", 1, 40.0, merchant = "Local Bakery")
        )
        rigFor(Scenario("carbon", groupCategories(), expenses, utcMs(2026, 4, 1)))
        val calculator = CarbonFootprintCalculator(expenseDao, timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk())
        val report = calculator.calculateCarbonFootprint(utcMs(2026, 3, 1), utcMs(2026, 4, 1))

        // Emission factors: fuel 2.3, clothing 0.55, food 0.25 kg per currency unit -> 23.00 + 11.00 + 10.00 kg.
        assertApproxEquals(44.0, report.totalEmissionsKg, 0.0001, "carbon total kg vs hand-computed 23 + 11 + 10: ")
        assertApproxEquals(
            report.totalEmissionsKg, report.categoryBreakdown.sumOf { it.emissionsKg }, 0.0001,
            "carbon total vs sum of category breakdown: "
        )
    }

    private suspend fun assertLifestyleCreepAgreesWithPaceOverBaseline() {
        val lifestyleData = listOf(
            income("2026-01-05", 1000.0), purchase(1, "2026-01-10", 1, 500.0, merchant = "Grocer"),
            income("2026-02-05", 1100.0), purchase(2, "2026-02-10", 1, 650.0, merchant = "Restaurant"),
            income("2026-03-05", 1200.0), purchase(3, "2026-03-10", 1, 900.0, merchant = "Restaurant")
        )
        val rig = rigFor(Scenario("lifestyle", groupCategories(), lifestyleData, utcMs(2026, 4, 1)))
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(lifestyleData)

        val report = LifestyleInflationDetector(expenseDao, timeProvider).analyzeLifestyleInflation(monthsToAnalyze = 6)

        every { timeProvider.now() } returns utcMs(2026, 3, 20)
        val pace = rig.pace.calculate(
            currentMonthStart = utcMs(2026, 3, 1),
            previousMonthStart = utcMs(2026, 2, 1),
            previousMonthEnd = utcMs(2026, 3, 1),
            allExpenses = lifestyleData.toExpenseSnapshots()
        )

        assertTrue("lifestyle: creep must be detected for 500 -> 650 -> 900 spending", report.lifestyleCreepDetected)
        assertTrue("lifestyle: pace over the February baseline must exceed 100%", pace.pacePercentage > 100f)
        assertTrue(
            "lifestyle: pace status must be ON_PACE or OVER_PACE, was ${pace.paceStatus}",
            pace.paceStatus == PaceStatus.ON_PACE || pace.paceStatus == PaceStatus.OVER_PACE
        )
    }

    private suspend fun assertEmptyDatasetZeroesEveryIntegrationPath() {
        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)
        val rig = rigFor(Scenario("empty", groupCategories(), emptyList(), utcMs(2026, 3, 30)))

        val insights = rig.insights.generateInsights(groupCategories(), emptyList<Expense>(), "EUR")
        val advancedStats = rig.advanced.getStatisticalInsights(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, march1, april1, "Mar", null), displayCurrency = "EUR"
        ).first
        val dailyTotals = rig.totals.getDailyTotalsForRange(march1, april1)
        val carbon = CarbonFootprintCalculator(expenseDao, timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk())
            .calculateCarbonFootprint(march1, april1)
        val lifestyle = LifestyleInflationDetector(expenseDao, timeProvider).analyzeLifestyleInflation(6)
        val sharedManager = SharedExpenseManager(
            sharedExpenseDataPort = mockk {
                coEvery { getGroupMembersOnce(any()) } returns emptyList<SharedExpenseMember>()
                coEvery { getGroupExpensesOnce(any()) } returns emptyList<SharedGroupExpense>()
                coEvery { getGroupSettlementsOnce(any()) } returns emptyList()
                coEvery { getGroupOnce(any()) } returns mockk(relaxed = true)
            },
            timeProvider = timeProvider,
            currencySettingsRepository = mockk(),
            ioDispatcher = Dispatchers.Unconfined
        )
        val balances = sharedManager.calculateBalances(1L)

        // Zero-fill must be exact, and "available zero" must stay distinguishable from "unavailable".
        assertMoneyExact(0.0, insights.monthlyComparison.currentTotal, "empty: insights monthly total")
        assertTrue(
            "empty: insights monthly total must be an Available zero, not unavailable",
            insights.monthlyComparison.currentMoneyResult is MoneyAggregateResult.Available
        )
        assertTrue("empty: no anomalies", insights.anomalies.isEmpty())
        assertMoneyExact(0.0, advancedStats.averageDailySpend, "empty: advanced average daily spend")
        assertEquals("empty: advanced days with spending", 0, advancedStats.daysWithSpending)
        assertTrue("empty: totals engine daily rows", dailyTotals.first().isEmpty())
        assertEquals("empty: carbon total kg", 0.0, carbon.totalEmissionsKg, 0.0)
        assertTrue("empty: carbon category breakdown", carbon.categoryBreakdown.isEmpty())
        assertTrue("empty: lifestyle creep must not be detected", !lifestyle.lifestyleCreepDetected)
        assertTrue("empty: lifestyle monthly data", lifestyle.monthlyData.isEmpty())
        assertTrue("empty: shared balances", balances.isEmpty())
    }

    // ------------------------------------------------------------------------------------
    // Weather widget wiring (synthesis -> dashboard widgets)
    // ------------------------------------------------------------------------------------

    @Test
    fun `weather widget carries the synthesized forecast and month to date spend`() = runTest {
        val now = utcMs(2026, 3, 20)
        val categories = groupCategories()
        val expenses = listOf(
            purchase(1, "2026-02-10", 1, 100.0),
            purchase(2, "2026-02-18", 2, 80.0),
            purchase(3, "2026-03-05", 1, 90.0),
            purchase(4, "2026-03-12", 2, 110.0),
            purchase(5, "2026-03-18", 3, 70.0)
        )
        val rig = rigFor(Scenario("weather", categories, expenses, now))

        val overallBudget = Budget(categoryId = null, amount = 800.0, period = BudgetPeriod.MONTHLY, startDate = utcMs(2026, 3, 1), createdAt = CREATED_AT)
        val budgetStatuses = listOf(
            BudgetStatus(
                budget = overallBudget,
                category = null,
                spentAmount = 270.0,
                remainingAmount = 530.0,
                percentUsed = 33.75f,
                healthStatus = BudgetHealthStatus.ON_TRACK,
                periodStart = utcMs(2026, 3, 1),
                periodEnd = utcMs(2026, 4, 1),
                effectiveLimit = 0.0
            )
        )
        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(budgetStatuses)
        val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        coEvery { savingsGoalRepository.getSavingsGoals() } returns emptyList()
        val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        // A wired assembler (real currency settings and normalizer, relaxed occurrence stores):
        // the former strict-mock assembler made the weather repository emit its UNKNOWN fallback.
        val forecastInputAssembler = ForecastInputAssembler(
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = rig.analyticsCurrencyNormalizer,
            currencySettingsRepository = rig.currencySettingsRepository,
            currencyConverter = rig.currencyConverter,
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
            databaseReadBarrier = mockk(relaxed = true)
        )
        val mergedRecurringPatternsProvider = MergedRecurringPatternsProvider(
            expenseRepository = rig.repository,
            recurringExpenseRepository = mockk(relaxed = true),
            recurringExpenseEngine = recurringExpenseEngine,
            forecastInputAssembler = forecastInputAssembler,
            timeProvider = timeProvider
        )
        val synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
        val weatherRepository = FinancialWeatherRepository(
            expenseRepository = rig.repository,
            budgetRepository = budgetRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            mergedRecurringPatternsProvider = mergedRecurringPatternsProvider,
            plannedExpenseRepository = plannedExpenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            forecastInputAssembler = forecastInputAssembler,
            synthesisEngine = synthesisEngine,
            narrativeGenerator = NarrativeGenerator(),
            analyticsRepository = mockk<AnalyticsRepository>(relaxed = true),
            currencySettingsRepository = rig.currencySettingsRepository,
            timeProvider = timeProvider
        )
        val weather = weatherRepository.getFinancialWeather().first()

        // RP-06 6c (D2): sealed HealthScoreOutcome cannot be fabricated by a relaxed mock;
        // an explicit Available stub keeps the compute path deterministic.
        val healthScoreV2 = mockk<com.yourname.expensetracker.domain.health.FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns
            com.yourname.expensetracker.domain.health.HealthScoreOutcome.Available(
                com.yourname.expensetracker.domain.health.FinancialHealthResult(
                    overallScore = 50,
                    savingsRateScore = 50,
                    runwayScore = 50,
                    budgetAdherenceScore = 50,
                    billReliabilityScore = 50,
                    factorContributions = emptyList(),
                    trend = com.yourname.expensetracker.domain.health.HealthTrend.STABLE,
                    recommendation = null
                )
            )
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
            urgencyScore = 0,
            urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
        val useCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = rig.insights,
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = mockk(relaxed = true),
            timeProvider = timeProvider,
            multiCurrencyRepository = mockk(relaxed = true),
            healthCalculator = FinancialHealthCalculator(timeProvider, rig.analyticsCurrencyNormalizer, rig.currencySettingsRepository),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true),
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true),
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = mockk<com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine>(relaxed = true),
            forecastInputAssembler = mockk {
                coEvery { assemble(any(), any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
                // ComputeDashboardWidgetsUseCase migrated to the normalized entry point.
                coEvery { assembleNormalized(any()) } returns mockk(relaxed = true)
            },
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = rig.currencySettingsRepository,
            // RP-06 6a: real calculator + the test's fake TimeProvider so pace math is real.
            spendingPaceCalculator = rig.pace
        )

        val march1 = utcMs(2026, 3, 1)
        val april1 = utcMs(2026, 4, 1)
        val monthSpent = expenses
            .filter { it.transactionType == TransactionType.PURCHASE && it.date in march1 until april1 }
            .sumOf { it.effectiveAmount }
        val compiled = useCase.compute(
            ProcessedDashboardData(
                data = DashboardData(
                    expenses = expenses.map { it.toDashboardExpense() },
                    categories = categories.map { DashboardCategory(id = it.id, name = it.name, icon = it.icon, color = it.color) },
                    budgetStatuses = budgetStatuses.map {
                        BudgetStatusSnapshot(
                            budgetCategoryId = it.budget.categoryId,
                            budgetAmount = it.budget.amount,
                            categoryName = it.category?.name,
                            spentAmount = it.spentAmount,
                            remainingAmount = it.remainingAmount,
                            percentUsed = it.percentUsed.toDouble(),
                            healthStatus = it.healthStatus,
                            periodStart = it.periodStart,
                            periodEnd = it.periodEnd,
                            currency = it.currency, currencyAssumption = it.currencyAssumption,
                            isPartial = it.isPartial, conversionWarning = it.conversionWarning, percentKnown = it.percentKnown
                        )
                    },
                    pendingCount = 0,
                    weather = weather,
                    recurringPatterns = emptyList(),
                    plannedExpenses = emptyList(),
                    goals = emptyList()
                ),
                summary = SpendingSummary(
                    totalSpent = monthSpent,
                    previousTotalSpent = null,
                    changePercent = null,
                    dailyHistory = emptyList<Double>(),
                    previousDailyHistory = emptyList<Double>(),
                    transactionCount = expenses.count { it.transactionType == TransactionType.PURCHASE }
                ),
                categoryBreakdown = listOf(
                    DashboardCategoryBreakdown(
                        categoryId = categories[0].id,
                        categoryName = categories[0].name,
                        categoryIcon = categories[0].icon,
                        categoryColor = categories[0].color,
                        amount = monthSpent,
                        percentage = 100.0,
                        changeFromLastPeriod = 0.0
                    )
                )
            )
        )
        val widgetWeather = compiled.allWidgets.filterIsInstance<DashboardWidget.FinancialWeatherWidget>().single().weather

        // Old: assertEquals(weather.projectedSpendingPoints, weather.projectedSpendingPoints) compared a
        // value with itself. New: the synthesis must have really run (not the UNKNOWN fallback), its
        // month-to-date point must equal the independently computed repository month total, and the
        // widget must carry both series unchanged.
        assertNotEquals("weather must be a real forecast, not the UNKNOWN fallback", WeatherState.UNKNOWN, weather.state)
        assertTrue("weather: past spending series must not be empty", weather.pastSpendingPoints.isNotEmpty())
        assertTrue("weather: projected spending series must not be empty", weather.projectedSpendingPoints.isNotEmpty())
        val repositoryMonthTotal = requireNotNull(rig.repository.getTotalForPeriod(march1, april1))
        assertMoneyEquals(270.0, weather.pastSpendingPoints.last(), "weather: month-to-date point = 90.00 + 110.00 + 70.00")
        assertApproxEquals(repositoryMonthTotal, weather.pastSpendingPoints.last(), 0.01, "weather month-to-date vs repository month total: ")
        assertEquals("widget past series", weather.pastSpendingPoints, widgetWeather.pastSpendingPoints)
        assertEquals("widget projected series", weather.projectedSpendingPoints, widgetWeather.projectedSpendingPoints)
    }

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry
    )
}
