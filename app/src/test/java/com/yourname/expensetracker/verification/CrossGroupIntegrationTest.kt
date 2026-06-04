package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.generateInsights
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.testCurrencyConverter
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.dao.MonthlyTotal
import com.yourname.expensetracker.data.database.dao.WeeklyTotal
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
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
import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import com.yourname.expensetracker.domain.health.FinancialHealthCalculator
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.MoneyRadarData
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.UrgencyLevel
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@Suppress("DEPRECATION_ERROR")
class CrossGroupIntegrationTest : AnalyticsEngineTestBase() {

    private val database = mockk<AppDatabase>(relaxed = true)
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var totalsEngine: TotalsAggregationEngine
    private lateinit var advancedEngine: AdvancedAnalyticsEngine

    private val categories = listOf(
        Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF5733"),
        Category(id = 2L, name = "Transport", icon = "🚌", color = "#33AAFF"),
        Category(id = 3L, name = "Shopping", icon = "🛍️", color = "#FFAA00"),
        Category(id = 4L, name = "Housing", icon = "🏠", color = "#00AA88")
    )

    @Before
    override fun setUp() {
        super.setUp()
        mockCategories(categories)

        expenseRepository = ExpenseRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            database = database,
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            debugExpenseAuditWriter = mockk(relaxed = true)
        )

        val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        insightsEngine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = AnomalyDetector(timeProvider = mockk()),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        val mcRepository = com.yourname.expensetracker.data.repository.MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = testCurrencyConverter(),
            timeProvider = timeProvider,
            currencySettingsRepository = TestCurrencySettingsRepository(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            normalizationEngine = com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine(testCurrencyConverter())
        )
        totalsEngine = TotalsAggregationEngine(expenseRepository, timeProvider, mcRepository, mockk(relaxed = true), Dispatchers.Unconfined)
        val currencySettingsRepository = TestCurrencySettingsRepository()
        val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer(testCurrencyConverter())
        advancedEngine = AdvancedAnalyticsEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            timeProvider = timeProvider,
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun `synthesis engine feeds correctly into dashboard widgets`() = runTest {
        val now = ms(2026, 3, 20)
        every { timeProvider.now() } returns now

        val expenses = listOf(
            purchase(1, "2026-02-10", 1, 100.0),
            purchase(2, "2026-02-18", 2, 80.0),
            purchase(3, "2026-03-05", 1, 90.0),
            purchase(4, "2026-03-12", 2, 110.0),
            purchase(5, "2026-03-18", 3, 70.0)
        )
        mockAnalyticsDaoByRange(expenses)
        every { expenseDao.getAllFlow(any()) } returns flowOf(expenses)

        val overallBudget = Budget(categoryId = null, amount = 800.0, period = BudgetPeriod.MONTHLY, startDate = ms(2026, 3, 1), createdAt = System.currentTimeMillis())
        val budgetStatuses = listOf(
            BudgetStatus(
                budget = overallBudget,
                category = null,
                spentAmount = 270.0,
                remainingAmount = 530.0,
                percentUsed = 33.75f,
                healthStatus = BudgetHealthStatus.ON_TRACK,
                periodStart = ms(2026, 3, 1),
                periodEnd = ms(2026, 4, 1),
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
        val forecastInputAssembler = ForecastInputAssembler(timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk(), currencyConverter = mockk(), recurringLifecycleCoordinator = mockk(), recurringOccurrenceDao = mockk(), databaseReadBarrier = mockk(relaxed = true))
        val mergedRecurringPatternsProvider = MergedRecurringPatternsProvider(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = mockk(relaxed = true),
            recurringExpenseEngine = recurringExpenseEngine,
            forecastInputAssembler = forecastInputAssembler,
            timeProvider = timeProvider
        )

        val synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
        val weatherRepository = FinancialWeatherRepository(
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            mergedRecurringPatternsProvider = mergedRecurringPatternsProvider,
            plannedExpenseRepository = plannedExpenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            forecastInputAssembler = forecastInputAssembler,
            synthesisEngine = synthesisEngine,
            narrativeGenerator = NarrativeGenerator(),
            analyticsRepository = mockk<AnalyticsRepository>(relaxed = true),
            currencySettingsRepository = TestCurrencySettingsRepository(),
            timeProvider = timeProvider
        )

        val weather = weatherRepository.getFinancialWeather().first()

        val healthScoreV2 = mockk<com.yourname.expensetracker.domain.health.FinancialHealthScoreV2>(relaxed = true)
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
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
        val stressForecastEngine = mockk<com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine>(relaxed = true)
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)

        val localCurrencySettingsRepository = TestCurrencySettingsRepository()
        val localAnalyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer(testCurrencyConverter())

        val useCase = ComputeDashboardWidgetsUseCase(
            insightsEngine = insightsEngine,
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = mockk(relaxed = true),
            timeProvider = timeProvider,
            multiCurrencyRepository = mockk(relaxed = true),
            healthCalculator = FinancialHealthCalculator(timeProvider, localAnalyticsCurrencyNormalizer, localCurrencySettingsRepository),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = mockk {
                coEvery { assemble(any(), any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
            },
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true)
        )

        val monthSpent = expenses.filter { it.transactionType == TransactionType.PURCHASE && it.date in ms(2026, 3, 1) until ms(2026, 4, 1) }
            .sumOf { it.effectiveAmount }
        val dashboardBudgetStatuses = budgetStatuses.map {
            BudgetStatusSnapshot(
                budgetCategoryId = it.budget.categoryId,
                budgetAmount = it.budget.amount,
                categoryName = it.category?.name,
                spentAmount = it.spentAmount,
                remainingAmount = it.remainingAmount,
                percentUsed = it.percentUsed.toDouble(),
                healthStatus = it.healthStatus,
                periodStart = it.periodStart,
                periodEnd = it.periodEnd
            )
        }

        val compiled = useCase.compute(
            ProcessedDashboardData(
                data = DashboardData(
                    expenses = expenses.map { it.toDashboardExpense() },
                    categories = categories.map { it.toDashboardCategory() },
                    budgetStatuses = dashboardBudgetStatuses,
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

        val weatherWidget = compiled.allWidgets.filterIsInstance<DashboardWidget.FinancialWeatherWidget>().single()

        assertEquals(weather.projectedSpendingPoints, weatherWidget.weather.projectedSpendingPoints)
        assertEquals(weather.projectedSpendingPoints, weather.projectedSpendingPoints)
    }

    @Test
    fun `anomaly detection uses same transaction data as insights engine`() = runTest {
        every { timeProvider.now() } returns ms(2026, 3, 30)

        val expenses = listOf(
            purchase(1, "2026-03-01", 1, 20.0),
            purchase(2, "2026-03-04", 1, 24.0),
            purchase(3, "2026-03-08", 1, 22.0),
            purchase(4, "2026-03-12", 1, 25.0),
            purchase(5, "2026-03-15", 1, 23.0),
            purchase(6, "2026-03-20", 1, 300.0)
        )
        mockAnalyticsDaoByRange(expenses)

        val snapshot = insightsEngine.generateInsights(categories, expenses)
        val datasetIds = expenses.map { it.id }.toSet()

        assertTrue(snapshot.anomalies.isNotEmpty())
        assertTrue(snapshot.anomalies.all { it.expense.id in datasetIds })
    }

    @Test
    fun `carbon footprint by category matches category spending totals`() = runTest {
        val expenses = listOf(
            purchase(1, "2026-03-10", 2, 10.0, merchant = "SHELL"),
            purchase(2, "2026-03-11", 3, 20.0, merchant = "ZARA"),
            purchase(3, "2026-03-12", 1, 40.0, merchant = "Local Bakery")
        )
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns expenses

        val calculator = CarbonFootprintCalculator(expenseDao, timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk())
        val report = calculator.calculateCarbonFootprint(ms(2026, 3, 1), ms(2026, 4, 1))

        val expectedTotal = 10.0 * 2.3 + 20.0 * 0.55 + 40.0 * 0.25
        val categorySum = report.categoryBreakdown.sumOf { it.emissionsKg }

        assertApproxEquals(expectedTotal, report.totalEmissionsKg, 0.0001)
        assertApproxEquals(report.totalEmissionsKg, categorySum, 0.0001)
    }

    @Test
    fun `lifestyle inflation correlates with spending pace changes`() = runTest {
        val lifestyleData = listOf(
            income("2026-01-05", 1000.0), purchase(1, "2026-01-10", 1, 500.0, merchant = "Grocer"),
            income("2026-02-05", 1100.0), purchase(2, "2026-02-10", 1, 650.0, merchant = "Restaurant"),
            income("2026-03-05", 1200.0), purchase(3, "2026-03-10", 1, 900.0, merchant = "Restaurant")
        )
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(lifestyleData)

        val detector = LifestyleInflationDetector(expenseDao, timeProvider)
        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        every { timeProvider.now() } returns ms(2026, 3, 20)
        val pace = spendingPaceCalculator.calculate(
            currentMonthStart = ms(2026, 3, 1),
            previousMonthStart = ms(2026, 2, 1),
            previousMonthEnd = ms(2026, 3, 1),
            allExpenses = lifestyleData.toExpenseSnapshots()
        )

        assertTrue(report.lifestyleCreepDetected)
        assertTrue(pace.pacePercentage > 100f)
        assertTrue(pace.paceStatus == PaceStatus.ON_PACE || pace.paceStatus == PaceStatus.OVER_PACE)
    }

    @Test
    fun `shared expenses counted correctly in monthly totals`() = runTest {
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
                date = ms(2026, 3, 5),
                description = "Rent",
                totalAmount = 120.0,
                currency = "EUR",
                splitType = GroupSplitType.EQUAL,
            )
        )

        val myShare = manager.calculateBalances(1L).getValue(1L).shouldPay
        val sharedExpense = purchase(
            id = 101,
            isoDate = "2026-03-05",
            categoryId = 4,
            amount = 120.0,
            isSharedExpense = true,
            myShareAmount = myShare,
            merchant = "Rent Payment"
        )

        mockAnalyticsDaoByRange(listOf(sharedExpense))
        val total = totalsEngine.getDailyTotalsForRange(ms(2026, 3, 1), ms(2026, 4, 1)).first().sumOf { it.totalAmount }

        assertApproxEquals(40.0, myShare, 0.0001)
        assertApproxEquals(40.0, total, 0.0001)
        assertTrue(total < sharedExpense.amount)
    }

    @Test
    fun `complete month analysis produces consistent results across all engines`() = runTest {
        every { timeProvider.now() } returns ms(2026, 3, 30)

        val expenses = listOf(
            purchase(1, "2026-02-04", 1, 40.0),
            purchase(2, "2026-02-12", 2, 50.0),
            purchase(3, "2026-03-02", 1, 20.0),
            purchase(4, "2026-03-06", 1, 24.0),
            purchase(5, "2026-03-10", 1, 22.0),
            purchase(6, "2026-03-14", 1, 25.0),
            purchase(7, "2026-03-18", 1, 23.0),
            purchase(8, "2026-03-22", 1, 300.0),
            purchase(9, "2026-03-26", 2, 60.0)
        )
        mockAnalyticsDaoByRange(expenses)

        val marchStart = ms(2026, 3, 1)
        val aprilStart = ms(2026, 4, 1)

        val insights = insightsEngine.generateInsights(categories, expenses)
        val advancedTotal = advancedEngine.getCategoryAnalytics(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, marchStart, aprilStart, "Mar 2026", null),
            displayCurrency = "EUR"
        ).first.sumOf { it.totalSpent }
        val totalsTotal = totalsEngine.getDailyTotalsForRange(marchStart, aprilStart).first().sumOf { it.totalAmount }
        val categorySum = totalsEngine.getCategoryBreakdown(marchStart, aprilStart, "Mar 2026").first().sumOf { it.totalAmount }
        val pace = spendingPaceCalculator.calculate(
            currentMonthStart = marchStart,
            previousMonthStart = ms(2026, 2, 1),
            previousMonthEnd = marchStart,
            allExpenses = expenses.toExpenseSnapshots()
        )
        val ids = expenses.map { it.id }.toSet()

        assertApproxEquals(insights.monthlyComparison.currentTotal, advancedTotal, 0.01)
        assertApproxEquals(insights.monthlyComparison.currentTotal, totalsTotal, 0.01)
        assertApproxEquals(totalsTotal, categorySum, 0.01)
        assertApproxEquals(totalsTotal, pace.currentMonthSpent, 0.01)
        assertTrue(insights.anomalies.all { it.expense.id in ids })
    }

    @Test
    fun `budget forecast uses correct historical data and produces realistic prediction`() = runTest {
        val now = ms(2026, 3, 30)
        every { timeProvider.now() } returns now

        val history = listOf(
            purchase(1, "2026-01-10", 1, 300.0),
            purchase(2, "2026-02-10", 1, 350.0),
            purchase(3, "2026-03-10", 1, 400.0)
        )
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns history
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 200.0

        // A.9 Batch 3: BudgetForecastingEngine now uses aggregate SQL for
        // historical spending data instead of fetching raw expense rows.
        // Mock the monthly spending totals that the engine now queries.
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal(monthKey = "2026-01", total = 300.0, txCount = 1),
            MonthlySpendingTotal(monthKey = "2026-02", total = 350.0, txCount = 1),
            MonthlySpendingTotal(monthKey = "2026-03", total = 400.0, txCount = 1)
        )

        val engine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = mockk(relaxed = true),
            budgetForecastDao = mockk {
                coEvery { insert(any()) } returns 1L
                coEvery { insertWithDeactivation(any()) } returns 1L
            },
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = mockk(relaxed = true),
            expenseRepository = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            currencyConverter = mockk(relaxed = true),
            writeBarrier = mockk(relaxed = true)
        )

        val forecast = engine.generateForecast(
            budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = ms(2026, 3, 1), createdAt = System.currentTimeMillis()),
            forecastPeriodDays = 30
        )

        assertTrue(forecast.predictedSpending > 0.0)
        assertTrue(forecast.predictedSpending.isFinite())
        assertTrue(forecast.confidenceScore in 0.0..1.0)
        assertTrue(forecast.overspendProbability in 0.0..1.0)
        assertTrue(forecast.riskLevel.name.isNotBlank())
    }

    @Test
    fun `all integration paths handle empty dataset gracefully`() = runTest {
        every { timeProvider.now() } returns ms(2026, 3, 30)
        mockAnalyticsDaoByRange(emptyList())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(emptyList())

        val insights = insightsEngine.generateInsights(categories, emptyList<Expense>())
        val advancedStats = advancedEngine.getStatisticalInsights(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, ms(2026, 3, 1), ms(2026, 4, 1), "Mar", null),
            displayCurrency = "EUR"
        ).first
        val dailyTotals = totalsEngine.getDailyTotalsForRange(ms(2026, 3, 1), ms(2026, 4, 1))
        val carbon = CarbonFootprintCalculator(expenseDao, timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk()).calculateCarbonFootprint(ms(2026, 3, 1), ms(2026, 4, 1))
        val lifestyle = LifestyleInflationDetector(expenseDao, timeProvider).analyzeLifestyleInflation(6)

        val sharedManager = SharedExpenseManager(
            sharedExpenseDataPort = mockk {
                coEvery { getGroupMembersOnce(any()) } returns emptyList<SharedExpenseMember>()
                coEvery { getGroupExpensesOnce(any()) } returns emptyList<SharedGroupExpense>()
                coEvery { getGroupOnce(any()) } returns mockk(relaxed = true)
            },
            timeProvider = timeProvider,
            currencySettingsRepository = mockk(),
            ioDispatcher = Dispatchers.Unconfined
        )
        val balances = sharedManager.calculateBalances(1L)

        assertApproxEquals(0.0, insights.monthlyComparison.currentTotal, 0.0)
        assertTrue(insights.anomalies.isEmpty())
        assertApproxEquals(0.0, advancedStats.averageDailySpend, 0.0)
        assertEquals(0, advancedStats.daysWithSpending)
        assertTrue(dailyTotals.first().isEmpty())
        assertApproxEquals(0.0, carbon.totalEmissionsKg, 0.0)
        assertTrue(carbon.categoryBreakdown.isEmpty())
        assertTrue(!lifestyle.lifestyleCreepDetected)
        assertTrue(lifestyle.monthlyData.isEmpty())
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `half_open_interval_enforced_at_all_analytics_entry_points`() = runTest {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        every { timeProvider.now() } returns ms(2026, 3, 20)

        val expenses = listOf(
            purchase(1, "2026-03-01", 1, 100.0), // included (start boundary)
            purchase(2, "2026-03-15", 2, 50.0),  // included
            purchase(3, "2026-04-01", 1, 900.0)  // excluded (end boundary)
        )
        mockAnalyticsDaoByRange(expenses)

        val insights = insightsEngine.generateInsights(categories, expenses)
        val totals = totalsEngine.getDailyTotalsForRange(start, end).first().sumOf { it.totalAmount }
        val advanced = advancedEngine.getCategoryAnalytics(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, start, end, "Mar 2026", null),
            displayCurrency = "EUR"
        ).first.sumOf { it.totalSpent }

        val dashboard = AdvancedAnalyticsDashboard(
            expenseDao = expenseDao,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            currencySettingsRepository = TestCurrencySettingsRepository(),
            analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer(testCurrencyConverter()),
            timeProvider = timeProvider
        ).generateDashboardData(start, end).totalSpent

        val expected = 150.0
        assertApproxEquals(expected, insights.monthlyComparison.currentTotal, 0.0001)
        assertApproxEquals(expected, totals, 0.0001)
        assertApproxEquals(expected, advanced, 0.0001)
        // Dashboard uses raw amount, but period boundaries must still be [start,end)
        assertApproxEquals(expected, dashboard, 0.0001)
    }

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
        date = ms(isoDate),
        categoryId = categoryId,
        createdAt = System.currentTimeMillis(),
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount
    )

    private fun income(isoDate: String, amount: Double): Expense = Expense(
        id = amount.toLong(),
        amount = amount,
        merchant = "Employer",
        transactionType = TransactionType.DEPOSIT,
        date = ms(isoDate),
        createdAt = System.currentTimeMillis()
    )

    private fun ms(isoDate: String): Long =
        LocalDate.parse(isoDate)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

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

    private fun Category.toDashboardCategory(): DashboardCategory = DashboardCategory(
        id = id,
        name = name,
        icon = icon,
        color = color
    )

    private fun mockAnalyticsDaoByRange(expenses: List<Expense>) {
        fun inRange(start: Long, end: Long): List<Expense> = expenses.filter { it.date in start until end }

        fun purchasesMine(start: Long, end: Long): List<Expense> =
            inRange(start, end).filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }

        fun categoryTotals(start: Long, end: Long): List<CategoryTotal> =
            purchasesMine(start, end)
                .filter { it.categoryId != null }
                .groupBy { it.categoryId!! }
                .map { (categoryId, rows) ->
                    CategoryTotal(categoryId = categoryId, total = rows.sumOf { it.effectiveAmount }, txCount = rows.size)
                }

        fun categoryBreakdown(start: Long, end: Long): List<CategoryTotalResult> =
            purchasesMine(start, end)
                .filter { it.categoryId != null }
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

        fun dailyTotals(start: Long, end: Long): List<DailyTotal> =
            purchasesMine(start, end)
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
            purchasesMine(start, end)
                .groupBy {
                    val y = TimePeriodUtils.getYear(it.date)
                    val m = TimePeriodUtils.getMonth(it.date) + 1
                    "%04d-%02d".format(y, m)
                }
                .map { (monthKey, rows) ->
                    val first = rows.minOf { it.date }
                    val monthStart = TimePeriodUtils.getStartOfMonth(first)
                    MonthlyTotal(
                        monthKey = monthKey,
                        startDate = monthStart,
                        endDate = TimePeriodUtils.getEndOfMonth(monthStart),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }

        fun weeklyTotals(start: Long, end: Long): List<WeeklyTotal> =
            purchasesMine(start, end)
                .groupBy {
                    val weekStart = TimePeriodUtils.getStartOfWeek(it.date)
                    "${TimePeriodUtils.getYear(weekStart)}-W${TimePeriodUtils.getWeekOfYear(weekStart)}"
                }
                .map { (weekKey, rows) ->
                    val first = rows.minOf { it.date }
                    val weekStart = TimePeriodUtils.getStartOfWeek(first)
                    WeeklyTotal(
                        weekKey = weekKey,
                        startDate = weekStart,
                        endDate = TimePeriodUtils.addDays(weekStart, 7),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }

        coEvery { expenseDao.getExpensesBetween(any(), any()) } answers { inRange(firstArg(), secondArg()) }
        // A.9: ExpenseRepository.getExpensesBetween() now delegates to the uncapped DAO variant
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } answers { inRange(firstArg(), secondArg()) }
        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), TransactionType.PURCHASE.name) } answers {
            purchasesMine(firstArg(), secondArg())
        }
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } answers { flowOf(inRange(firstArg(), secondArg())) }
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } answers { flowOf(inRange(firstArg(), secondArg())) }
        every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), TransactionType.PURCHASE.name) } answers {
            flowOf(purchasesMine(firstArg(), secondArg()))
        }
        // A.9: ExpenseRepository.getAllExpenses() now delegates to getAllFlowUncapped()
        every { expenseDao.getAllFlowUncapped() } answers { flowOf(expenses) }
        every { expenseDao.getAllFlow() } answers { flowOf(expenses) }

        coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
            purchasesMine(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCountForPeriod(any(), any()) } answers {
            purchasesMine(firstArg(), secondArg()).size
        }
        coEvery { expenseDao.getCategoryTotalsForPeriod(any(), any()) } answers {
            categoryTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getCategoryBreakdown(any(), any()) } answers {
            categoryBreakdown(firstArg(), secondArg())
        }
        coEvery { expenseDao.getDailyTotalsWithDatesForPeriod(any(), any()) } answers {
            dailyTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getDailyTotalsForPeriod(any(), any()) } answers {
            dailyTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getWeeklyTotalsForPeriod(any(), any()) } answers {
            weeklyTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getMonthlyTotalsForPeriod(any(), any()) } answers {
            monthlyTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getAverageDailySpend(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            val days = ((end - start) / TimePeriodUtils.DAY_IN_MILLIS).toInt().coerceAtLeast(1)
            purchasesMine(start, end).sumOf { it.effectiveAmount } / days
        }
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } answers {
            purchasesMine(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCategoryTotalsBetween(any(), any()) } answers {
            categoryTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getAll() } returns expenses
        coEvery { expenseDao.getPurchaseCount() } returns expenses.count { it.transactionType == TransactionType.PURCHASE }
        coEvery { expenseDao.getOldestExpenseDate() } returns expenses.minOfOrNull { it.date }
        every { expenseDao.getTotalSpentFlow() } returns flowOf(
            expenses.filter { it.transactionType == TransactionType.PURCHASE }.sumOf { it.effectiveAmount }
        )
        every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(
            CurrencyTotal("EUR", 
                expenses.filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
                    .sumOf { it.effectiveAmount }, 1)
        )
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            purchasesMine(start, end)
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
        coEvery { expenseDao.getExpensesSince(any()) } answers { expenses.filter { it.date >= firstArg<Long>() } }
    }
}