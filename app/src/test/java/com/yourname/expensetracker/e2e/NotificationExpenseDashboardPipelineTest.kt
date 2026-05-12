package com.yourname.expensetracker.e2e

import android.content.Context
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.AnomalyAlertDao
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.PromptStateDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.data.repository.DeleteGroupMemberResult
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupDetailsAggregate
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.data.repository.PromptStateRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.ContextualInferenceEngine
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.categorization.SemanticKeywordMatcher
import com.yourname.expensetracker.domain.forecasting.DataQualityAssessor
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.HistoricalSpendingDistribution
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseGroup
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import com.yourname.expensetracker.domain.health.FinancialHealthCalculator
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.intelligence.ml.ExpenseCategoryClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary as DashboardSpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRepository
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.inject.Provider

/**
 * End-to-end tests for the notification → expense → dashboard pipeline.
 *
 * ## Test gaps (not yet covered):
 * - Notification deduplication: test that consecutive identical notifications
 *   (same merchant, amount, text) produce only one expense entry.
 * - Dashboard reflection after notification capture: verify that a newly captured
 *   notification expense immediately appears in dashboard totals and category
 *   breakdowns without manual refresh.
 * - Currency parsing from notification text: test that the notification parser
 *   correctly extracts non-EUR currencies (USD, GBP, etc.) from raw notification
 *   text and sets the expense currency accordingly.
 * - Error handling for malformed notifications: test that a notification with
 *   missing amount, malformed date, or empty merchant text is handled gracefully
 *   (skipped or flagged for review, not crashed).
 */
class NotificationExpenseDashboardPipelineTest : AnalyticsEngineTestBase() {

    private lateinit var parserRegistry: AppParserRegistry
    private lateinit var classifier: HybridExpenseClassifier
    private lateinit var dashboardUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var insightsEngine: InsightsEngine

    @Before
    override fun setUp() {
        super.setUp()

        val currencyNormalizer = CurrencyNormalizer()
        val merchantCleaner = MerchantCleaner()
        val directionDetector = TransferDirectionDetector()

        parserRegistry = AppParserRegistry(
            greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR"),
            revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
            smsParser = SmsParser(currencyNormalizer, merchantCleaner),
            googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
            genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider = mockk()),
            aiFallbackParser = object : NotificationFallbackParser {
                override suspend fun parse(
                    title: String?,
                    text: String?,
                    bigText: String?,
                    packageName: String
                ) = null
            },
            timeProvider = mockk()
        )

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))

        val merchantNormalizationDao = mockk<MerchantNormalizationDao>(relaxed = true)
        coEvery { merchantNormalizationDao.getAliasByNormalizedKey(any()) } returns null
        coEvery { merchantNormalizationDao.getCanonicalBySearchKey(any()) } returns null
        coEvery { merchantNormalizationDao.getTopMerchants(any()) } returns emptyList()

        val merchantCategoryDao = mockk<MerchantCategoryDao>(relaxed = true)
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory(merchantPattern = "lidl", categoryId = 2L)
        )

        val merchantNormalizationRepository = MerchantNormalizationRepository(mockk(relaxed = true), merchantNormalizationDao)
        val greeklishNormalizer = GreeklishNormalizer()
        val merchantNormalizer = MerchantNormalizer(
            repository = merchantNormalizationRepository,
            merchantRules = MerchantRulesRepository(),
            greeklishNormalizer = greeklishNormalizer,
            context = context,
            timeProvider = timeProvider
        )

        val categoryRepositoryProvider = object : Provider<CategoryRepository> {
            override fun get(): CategoryRepository = categoryRepository
        }

        lateinit var categorizationEngineRef: CategorizationEngine
        val categorizationEngineProvider = object : Provider<CategorizationEngine> {
            override fun get(): CategorizationEngine = categorizationEngineRef
        }

        val merchantCategoryRepository = MerchantCategoryRepository(
            writeBarrier = mockk(relaxed = true),
            dao = merchantCategoryDao,
            categorizationEngineProvider = categorizationEngineProvider
        )

        categorizationEngineRef = CategorizationEngine(
            merchantCategoryRepository = merchantCategoryRepository,
            merchantNormalizer = merchantNormalizer,
            categoryRepositoryProvider = categoryRepositoryProvider,
            canonicalizer = MerchantCanonicalizer(),
            greeklishNormalizer = greeklishNormalizer,
            semanticMatcher = SemanticKeywordMatcher(greeklishNormalizer, timeProvider = timeProvider),
            contextEngine = ContextualInferenceEngine(),
            timeProvider = timeProvider
        )

        classifier = HybridExpenseClassifier(
            context = context,
            categoryRepository = categoryRepository,
            categorizationEngine = categorizationEngineRef,
            nbClassifier = ExpenseCategoryClassifier(context, atRestEncryptionService = mockk()),
            timeProvider = timeProvider
        )

        val expenseRepository = ExpenseRepository(
            writeBarrier = mockk(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true),
            expenseDao = expenseDao,
            userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true),
            pendingReviewDao = mockk<PendingReviewDao>(relaxed = true),
            merchantCategoryRepository = merchantCategoryRepository,
            merchantNormalizer = merchantNormalizer,
            transferDirectionAnalytics = TransferDirectionAnalytics(),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        )

        val recurringExpenseDao = mockk<ManualRecurringExpenseDao>(relaxed = true)
        coEvery { recurringExpenseDao.getAll() } returns emptyList()
        every { recurringExpenseDao.getAllFlow() } returns flowOf(emptyList())

        val recurringExpenseEngine = RecurringExpenseEngine(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = RecurringExpenseRepository(
                writeBarrier = mockk(relaxed = true),
                dao = recurringExpenseDao,
                lifecycleEventDao = mockk(relaxed = true),
                timeProvider = timeProvider
            ),
            timeProvider = timeProvider
        )

        insightsEngine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = SpendingPaceCalculator(timeProvider),
            anomalyDetector = AnomalyDetector(timeProvider = mockk()),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        val budgetDao = mockk<BudgetDao>(relaxed = true)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(emptyList())
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()

        val categoryDao = mockk<CategoryDao>(relaxed = true)
        every { categoryDao.getAllFlow() } returns flowOf(testCategories)
        coEvery { categoryDao.getAll() } returns testCategories

        val sharedExpenseDataPort = object : SharedExpenseDataPort {
            override suspend fun createGroupWithMembers(
                group: SharedExpenseGroup,
                members: List<SharedExpenseMember>
            ): Long = 1L

            override suspend fun addMember(member: SharedExpenseMember): Long = 1L

            override suspend fun removeMember(member: SharedExpenseMember) = Unit

            override suspend fun addExpense(expense: SharedGroupExpense): Long = 1L

            override fun getAllGroups(): Flow<List<SharedExpenseGroup>> = flowOf(emptyList())

            override fun getActiveGroups(): Flow<List<SharedExpenseGroup>> = flowOf(emptyList())

            override fun getGroup(groupId: Long): Flow<SharedExpenseGroup?> = flowOf(null)

            override suspend fun getGroupOnce(groupId: Long): SharedExpenseGroup? = null

            override fun getGroupMembers(groupId: Long): Flow<List<SharedExpenseMember>> = flowOf(emptyList())

            override fun getGroupExpenses(groupId: Long): Flow<List<SharedGroupExpense>> = flowOf(emptyList())

            override suspend fun getGroupMembersOnce(groupId: Long): List<SharedExpenseMember> = emptyList()

            override suspend fun getGroupExpensesOnce(groupId: Long): List<SharedGroupExpense> = emptyList()

            override suspend fun archiveGroup(groupId: Long) = Unit

            override suspend fun restoreGroup(groupId: Long) = Unit

            override suspend fun deleteGroup(group: SharedExpenseGroup) = Unit
        }

        val groupsRepository = object : GroupsRepository {
            override suspend fun getActiveGroupsWithDetails(): List<GroupDetailsAggregate> = emptyList()
            override suspend fun getGroupById(groupId: Long) = null
            override suspend fun getMemberById(memberId: Long) = null
            override suspend fun createGroup(
                name: String,
                description: String?,
                currency: String,
                currentUserName: String
            ): GroupCreationResult = GroupCreationResult.Error("Not needed in this test")

            override suspend fun addMember(
                groupId: Long,
                name: String,
                email: String?,
                isCurrentUser: Boolean
            ): com.yourname.expensetracker.domain.groups.Result<Unit, com.yourname.expensetracker.domain.groups.GroupValidationError> =
                com.yourname.expensetracker.domain.groups.Result.Success(Unit)

            override suspend fun addExpenseWithLink(
                groupId: Long,
                systemExpenseId: Long,
                description: String,
                amount: Double,
                paidById: Long,
                splitType: com.yourname.expensetracker.data.database.entity.SplitType,
                customSplitsJson: String?,
                date: Long
            ): GroupExpenseCreationResult = GroupExpenseCreationResult.Error("Not needed in this test")

            override suspend fun deleteGroup(groupId: Long): Boolean = false

            override suspend fun deleteMember(groupId: Long, memberId: Long): DeleteGroupMemberResult =
                DeleteGroupMemberResult.Error("Not needed in this test")

            override suspend fun createSystemExpenseAndLinkToGroup(
                groupId: Long,
                description: String,
                amount: Double,
                paidById: Long,
                currency: String,
                splitType: com.yourname.expensetracker.data.database.entity.SplitType,
                customSplitsJson: String?,
                date: Long,
                transactionType: com.yourname.expensetracker.data.database.entity.TransactionType,
                notes: String?
            ): GroupExpenseCreationResult = GroupExpenseCreationResult.Error("Not needed in this test")
        }

        val sharedExpenseManager = SharedExpenseManager(sharedExpenseDataPort, timeProvider, mockk(), ioDispatcher = testDispatcher)

        val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)

        val budgetRepository = BudgetRepository(
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            expenseDao = expenseDao,
            budgetCalculator = com.yourname.expensetracker.domain.budget.BudgetCalculator(timeProvider),
            timeProvider = timeProvider,
            offsetEngine = com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine(
                groupsRepository = object : dagger.Lazy<GroupsRepository> {
                    override fun get(): GroupsRepository = groupsRepository
                },
                expenseRepository = expenseRepository,
                currencyConverter = currencyConverter,
                currencySettingsRepository = currencySettingsRepository,
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            timeBoundaryTicker = com.yourname.expensetracker.domain.util.TimeBoundaryTicker(timeProvider),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = mockk(),
            writeBarrier = mockk(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true),
            budgetForecastDao = mockk(relaxed = true)
        )

        val savingsGoalDao = mockk<SavingsGoalDao>(relaxed = true)
        every { savingsGoalDao.getAllGoals() } returns flowOf(emptyList())
        val savingsGoalRepository = SavingsGoalRepository(mockk(relaxed = true), savingsGoalDao)

        val promptStateDao = mockk<PromptStateDao>(relaxed = true)
        coEvery { promptStateDao.countPromptsSince(any(), any()) } returns 0
        coEvery { promptStateDao.getPromptsSince(any(), any()) } returns emptyList()
        val promptStateRepository = PromptStateRepository(mockk(relaxed = true), promptStateDao, timeProvider)

        val lifestyleSavingsPromptUseCase = LifestyleSavingsPromptUseCase(
            lifestyleInflationDetector = LifestyleInflationDetector(expenseDao, timeProvider = mockk()),
            savingsGoalRepository = savingsGoalRepository,
            promptStateRepository = promptStateRepository,
            timeProvider = timeProvider,
        )

        val historicalDistribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk())
        val monteCarloSimulator = MonteCarloSpendingSimulator(
            historicalDistribution = historicalDistribution,
            dataQualityAssessor = DataQualityAssessor(),
            timeProvider = timeProvider,
        )

        val computeMoneyRadarUseCase = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = mockk<MergedRecurringPatternsProvider>(relaxed = true),
            anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true),
            getMonteCarloBudgetImpact = GetMonteCarloBudgetImpactUseCase(),
            monteCarloSimulator = monteCarloSimulator,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider,
        )

        val synthesisEngine = SynthesisEngine(timeProvider)
        val stressForecastEngine = FinancialStressForecastEngine(
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            recurringPatternsProvider = mockk(),
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = mockk(),
            recurringLifecycleCoordinator = mockk(),
            recurringOccurrenceDao = mockk(),
            currencyConverter = currencyConverter,
            accountBalanceProvider = mockk(relaxed = true)
        )

        val healthScoreV2 = FinancialHealthScoreV2(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            healthScoreHistoryDao = mockk<HealthScoreHistoryDao>(relaxed = true),
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            cashFlowCalculator = mockk(),
        )

        dashboardUseCase = ComputeDashboardWidgetsUseCase(
            insightsEngine = insightsEngine,
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            multiCurrencyRepository = mockk(),
            healthCalculator = FinancialHealthCalculator(timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = mockk(),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
        )
    }

    @Test
    fun `raw notification parsed and included in dashboard total`() = runTest {
        val parsed = parserRegistry.parse(
            title = "Paid €45.30 at Lidl",
            text = "Paid €45.30 at Lidl",
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )

        assertNotNull(parsed)
        assertApproxEquals(45.30, parsed?.amount ?: 0.0, 0.01)

        val classification = classifier.classify(
            merchantName = parsed!!.merchant,
            amount = parsed.amount,
            notificationTitle = "Paid €45.30 at Lidl",
            notificationText = "Paid €45.30 at Lidl",
            packageName = "com.revolut.revolut"
        )
        assertEquals(2L, classification.categoryId)
        assertEquals(MatchType.RULE_MATCH, classification.matchType)

        val baseWithoutParsed = goldenMarchExpenses().filterNot {
            it.merchant == "Lidl" && kotlin.math.abs(it.amount - 45.30) < 0.0001
        }
        val parsedExpense = createExpense(
            date = "2026-03-02",
            amount = parsed.amount,
            merchant = parsed.merchant,
            category = "groceries",
            id = 2002L
        )

        val allExpenses = baseWithoutParsed + parsedExpense
        mockExpenses(allExpenses)

        val compiled = dashboardUseCase.compute(createProcessedData(allExpenses))
        assertApproxEquals(1283.59, compiled.totalSpent, 0.01)
    }

    @Test
    fun `parse failure keeps dashboard total at baseline`() = runTest {
        val parsed = parserRegistry.parse(
            title = "Special Offer",
            text = "Save up to 50% with promo code 123456",
            bigText = null,
            subText = null,
            packageName = "com.marketing.app"
        )
        assertNull(parsed)

        val baseline = goldenMarchExpenses().filterNot {
            it.merchant == "Lidl" && kotlin.math.abs(it.amount - 45.30) < 0.0001
        }
        mockExpenses(baseline)

        val compiled = dashboardUseCase.compute(createProcessedData(baseline))
        assertApproxEquals(1238.29, compiled.totalSpent, 0.01)
    }

    @Test
    fun `empty notification ignored and dashboard total remains golden`() = runTest {
        val parsed = parserRegistry.parse(
            title = null,
            text = "",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNull(parsed)

        val golden = goldenMarchExpenses()
        mockExpenses(golden)

        val compiled = dashboardUseCase.compute(createProcessedData(golden))
        assertApproxEquals(1283.59, compiled.totalSpent, 0.01)
    }

    private fun createProcessedData(expenses: List<Expense>): ProcessedDashboardData {
        val monthSpent = expenses
            .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
            .sumOf { it.effectiveAmount }

        val dashboardData = DashboardData(
            expenses = expenses.map { it.toDashboardExpense() },
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 0,
            weather = FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )

        val summary = DashboardSpendingSummary(
            totalSpent = monthSpent,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.count { it.transactionType == TransactionType.PURCHASE }
        )

        return ProcessedDashboardData(
            data = dashboardData,
            summary = summary,
            categoryBreakdown = emptyList()
        )
    }

    private fun Expense.toDashboardExpense(): DashboardExpense {
        return DashboardExpense(
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

    private fun goldenMarchExpenses() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
        createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
        createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
        createExpense("2026-03-20", 89.90, merchant = "Zara", id = 10L),
        createExpense("2026-03-22", 12.30, merchant = "Pharmacy", id = 11L),
        createExpense(
            date = "2026-03-25",
            amount = 35.00,
            effectiveAmount = 17.50,
            merchant = "Friend Lunch",
            category = "dining",
            id = 12L,
            isSharedExpense = true,
            mySharePercentage = 50
        ),
        createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L)
    )
}