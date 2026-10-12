package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.forecasting.HistoricalMoneyQuality
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SmartSavingsEngineTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var cashFlowCalculator: CashFlowCalculator
    private lateinit var spendingThresholdCalculator: SpendingThresholdCalculator
    private lateinit var engine: SmartSavingsEngine

    private val now = LocalDate.of(2026, 4, 15)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        budgetCalculator = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>()
            val homeCurrency = secondArg<String>()
            com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = expenses.map {
                    com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot(
                        snapshot = it,
                        originalCurrency = it.currency,
                        originalEffectiveAmount = it.effectiveAmount,
                        normalizedEffectiveAmount = it.effectiveAmount
                    )
                },
                includedExpenses = expenses,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }

        io.mockk.every { timeProvider.now() } returns now

        cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)
        spendingThresholdCalculator = mockk<SpendingThresholdCalculator>(relaxed = true)
        coEvery { spendingThresholdCalculator.getThreshold() } returns 500.0

        engine = SmartSavingsEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            budgetCalculator = budgetCalculator,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            cashFlowCalculator = cashFlowCalculator,
            spendingThresholdCalculator = spendingThresholdCalculator
        )
    }

    @Test
    fun `cross currency goals are declined rather than relabeled`() = runTest {
        val goal = SavingsGoal(1L, "Trip", 1000.0, 0.0, null, GoalProtectionLevel.WARNING, currency = "USD", currencyAssumption = "EXPLICIT", createdAt = now)
        assertTrue(engine.calculatePortfolioRecommendationsResult(listOf(goal), homeCurrency = "EUR")
            is SmartSavingsComputationResult.NoRecommendation)
    }

    @Test
    fun `excluded spending cannot create a savings recommendation`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult(
                "EUR", emptyList(), emptyList(), emptyList(), null, totalInputCount = 2
            )
        val goal = SavingsGoal(1L, "Trip", 1000.0, 0.0, null, GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
        val result = engine.calculatePortfolioRecommendationsResult(listOf(goal), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertEquals(2, result.metadata.excludedInputCount)
        assertEquals(2, result.metadata.totalInputCount)
        assertEquals(0, result.metadata.includedInputCount)
        assertEquals(ConversionQuality.UNAVAILABLE, result.metadata.conversionQuality)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun invalidTargetRemainsUnavailableWithNoGoals() = runTest {
        val result = engine.calculatePortfolioRecommendationsResult(emptyList(), homeCurrency = "SECRET currency")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("INVALID_TARGET_CURRENCY", result.reasonCode)
        org.junit.Assert.assertNull(result.metadata.currency)
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) }
    }

    @Test
    fun partialBudgetIsUnavailableNotAnEmptyPortfolio() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(300.0).copy(isPartial = true, conversionWarning = "SECRET budget"))
        )
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertTrue(!result.toString().contains("SECRET"))
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun staleNormalizationRetainsControlledQualityAndTimestamp() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns AnalyticsNormalizationResult(
            "EUR", emptyList(), emptyList(), listOf(
                AnalyticsConversionWarning(AnalyticsConversionWarningType.STALE_EXCHANGE_RATE, "SECRET row", 3)
            ), 123L, totalInputCount = 3
        )
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals(3, result.metadata.excludedInputCount)
        assertEquals(123L, result.metadata.latestRateTimestamp)
        assertTrue("STALE_EXCHANGE_RATE" in result.metadata.warningCodes)
        assertEquals(setOf(RateBasis.TRANSACTION_DATE), result.metadata.requestedRateBases)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun availablePortfolioRetainsHistoricalMoneyQualitySeparatelyFromConfidence() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(300.0)))
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            monteCarloResult(0.0).let { result ->
                result.copy(metadata = result.metadata.copy(historicalMoneyQuality = HistoricalMoneyQuality(
                    totalInputCount = 4, includedInputCount = 4, latestRateTimestamp = 456L,
                    actualRateBasis = RateBasis.IDENTITY
                )))
            }
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Available
        assertApproxEquals(60.0, result.recommendations.single().recommendation.safeAmount)
        assertEquals(ConversionQuality.COMPLETE, result.metadata.conversionQuality)
        assertEquals(4, result.metadata.totalInputCount)
        assertEquals(4, result.metadata.includedInputCount)
        assertEquals(456L, result.metadata.latestRateTimestamp)
        assertTrue(result.metadata.inputs.any { it.stage == SavingsInputStage.SIMULATOR_HISTORY })
    }

    @Test
    fun partialHistoricalForecastQualityRetainsCountsAndFailsClosed() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(300.0)))
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            monteCarloResult(0.0).let { result ->
                result.copy(metadata = result.metadata.copy(historicalMoneyQuality = HistoricalMoneyQuality(
                    quality = ConversionQuality.PARTIAL, totalInputCount = 4, includedInputCount = 3,
                    excludedInputCount = 1, latestRateTimestamp = 456L
                )))
            }
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertEquals(4, result.metadata.totalInputCount)
        assertEquals(3, result.metadata.includedInputCount)
        assertEquals(1, result.metadata.excludedInputCount)
        assertEquals(456L, result.metadata.latestRateTimestamp)
    }

    @Test
    fun unavailableForecastCannotBecomeZeroRiskSavings() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(300.0)))
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            MonteCarloResult.unavailable("SECRET forecast")
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun zeroSafePoolIsValidNoRecommendationWithProvenance() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.NoRecommendation
        assertEquals("EUR", result.metadata.currency)
        assertEquals(ConversionQuality.COMPLETE, result.metadata.conversionQuality)
        assertTrue(result.metadata.inputs.isNotEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class IdentityCancellation : kotlinx.coroutines.CancellationException("SECRET cancellation"),
        kotlinx.coroutines.CopyableThrowable<IdentityCancellation> {
        override fun createCopy(): IdentityCancellation? = null
    }

    @Test
    fun typedBoundaryPreservesCancellationIdentityAndSanitizesFailure() = runTest {
        val cancelled = IdentityCancellation()
        io.mockk.every { budgetRepository.getBudgetStatuses() } throws cancelled
        org.junit.Assert.assertSame(cancelled, runCatching {
            engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
        }.exceptionOrNull())
        io.mockk.every { budgetRepository.getBudgetStatuses() } throws IllegalStateException("SECRET /private/db")
        val result = engine.calculatePortfolioRecommendationsResult(listOf(typedGoal()), homeCurrency = "EUR")
            as SmartSavingsComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun unknownActiveDenominationFailsBeforeEmptyBudgetOrForeignGoalShortcuts() = runTest {
        for (currency in listOf("EUR", "USD", "GBP")) {
            for (provenance in listOf("LEGACY_DEFAULT", "UNKNOWN", "", "ASSUMED_HOME_CURRENCY", "SECRET unknown")) {
                val goal = typedGoal().copy(currency = currency, currencyAssumption = provenance)
                val result = engine.calculatePortfolioRecommendationsResult(listOf(goal), homeCurrency = "EUR")
                    as SmartSavingsComputationResult.Unavailable
                assertEquals("INVALID_SOURCE_CURRENCY", result.reasonCode)
                assertEquals(ConversionQuality.UNAVAILABLE, result.metadata.conversionQuality)
                assertEquals(listOf("INVALID_SOURCE_CURRENCY"), result.metadata.warningCodes)
                assertTrue(!result.toString().contains("SECRET"))
                assertTrue(!engine.calculateSafeToSaveAmount(goal, homeCurrency = "EUR").isAvailable)
            }
        }
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun mixedKnownAndLegacyActivePortfolioIsUnavailableNotPartialAllocation() = runTest {
        val known = typedGoal()
        for (balance in listOf(0.0, 50.0)) {
            val legacy = known.copy(id = 2L, currentAmount = balance, currencyAssumption = "LEGACY_DEFAULT")
            val result = engine.calculatePortfolioRecommendationsResult(listOf(known, legacy), homeCurrency = "EUR")
                as SmartSavingsComputationResult.Unavailable
            assertEquals("INVALID_SOURCE_CURRENCY", result.reasonCode)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun explicitEurUsdGbpPreserveSameUnitAllocationArithmetic() = runTest {
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        for (currency in listOf("EUR", "USD", "GBP")) {
            val status = budgetStatus(300.0)
            io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(
                listOf(status.copy(currency = currency, budget = status.budget.copy(currency = currency)))
            )
            coEvery { monteCarloSimulator.simulate(any(), any(), any(), currency, any()) } returns
                monteCarloResult(0.0).copy(displayCurrency = currency)
            val goal = typedGoal().copy(currency = currency, currencyAssumption = "EXPLICIT_HOME_CURRENCY")
            val result = engine.calculatePortfolioRecommendationsResult(listOf(goal), homeCurrency = currency)
                as SmartSavingsComputationResult.Available
            assertApproxEquals(60.0, result.recommendations.single().recommendation.safeAmount)
            assertEquals(currency, result.metadata.currency)
            assertEquals(currency, result.recommendations.single().recommendation.currency)
            assertEquals(goal, result.recommendations.single().goal)
        }
    }

    private fun typedGoal() = SavingsGoal(
        1L, "Trip", 1000.0, 0.0, null, GoalProtectionLevel.WARNING,
        currency = "EUR", currencyAssumption = "EXPLICIT", createdAt = now
    )

    @Test
    fun `safeToSaveAmount combines weighted surplus pace and monteCarlo`() = runTest {
        val budgetStatuses = listOf(
            budgetStatus(remaining = 100.0),
            budgetStatus(remaining = 200.0),
            budgetStatus(remaining = -20.0)
        )
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(budgetStatuses)

        val monthExpenses = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(id = 1001L,
                amount = 50.0,
                merchant = "A",
                transactionType = TransactionType.PURCHASE,
                date = now
            ),
            com.yourname.expensetracker.data.database.entity.Expense(id = 1002L,
                amount = 100.0,
                merchant = "B",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        val historicalExpenses = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(id = 1003L, amount = 600.0, merchant = "H1", categoryId = 1L, transactionType = TransactionType.PURCHASE, date = now),
            com.yourname.expensetracker.data.database.entity.Expense(id = 1004L, amount = 600.0, merchant = "H2", categoryId = 1L, transactionType = TransactionType.PURCHASE, date = now),
            com.yourname.expensetracker.data.database.entity.Expense(id = 1005L, amount = 600.0, merchant = "H3", categoryId = 1L, transactionType = TransactionType.PURCHASE, date = now)
        )
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Entertainment", icon = "🎬", color = "#FF0000")
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            if (start < now - (60L * 24 * 60 * 60 * 1000)) historicalExpenses else monthExpenses
        }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            if (start < now - (60L * 24 * 60 * 60 * 1000))
                historicalExpenses.map { it.toExpenseSnapshot() }
            else
                monthExpenses.map { it.toExpenseSnapshot() }
        }

        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult(400.0)

        val goal = SavingsGoal(id = 0L, name = "Emergency", targetAmount = 5000.0, currentAmount = 1000.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
        val result = engine.calculateSafeToSaveAmount(goal, homeCurrency = "EUR")

        // surplus = (100 + 200) * 0.5 = 150
        // pace: totalSpent=150, day=15, projected=300, avgMonthly=1800/3=600 => (600-300)*0.3 = 90
        // mc: monthly discretionary from 3-month history = 1800/3 = 600
        //     (600 - 400*0.3) * 0.2 = 96
        // safe = 150*0.4 + 90*0.3 + 96*0.3 = 115.8
        assertApproxEquals(115.8, result.safeAmount, 0.01)
        assertApproxEquals(0.95, result.confidence, 0.001)
    }

    @Test
    fun `monte carlo discretionary baseline excludes essential categories`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        // Month-to-date and historical requests both return this list in this test.
        val mixedExpenses = listOf(
            // Essential spending (should be excluded from discretionary baseline)
            com.yourname.expensetracker.data.database.entity.Expense(id = 1006L,
                amount = 300.0,
                merchant = "Supermarket",
                categoryId = 1L,
                transactionType = TransactionType.PURCHASE,
                date = now
            ),
            // Discretionary spending (should be included)
            com.yourname.expensetracker.data.database.entity.Expense(id = 1007L,
                amount = 90.0,
                merchant = "Cinema",
                categoryId = 2L,
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )

        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "🛒", color = "#00FF00"),
            Category(id = 2L, name = "Entertainment", icon = "🎬", color = "#FF0000")
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns mixedExpenses
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns mixedExpenses.map { it.toExpenseSnapshot() }
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult(400.0)

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(id = 0L, name = "Trip", targetAmount = 2000.0, currentAmount = 200.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
            , homeCurrency = "EUR"
        )

        // monthly discretionary baseline uses only discretionary purchases: 90 / 3 = 30
        assertTrue(result.isAvailable)
        // monte-carlo component => remaining = 30 - (400 * 0.3) < 0 => 0
        // pace also 0 (projected above baseline), budget surplus 0 => safe amount 0
        assertApproxEquals(0.0, result.safeAmount)
    }

    @Test
    fun `no budgets and no spending history return zero safe amount`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(id = 0L, name = "Trip", targetAmount = 1000.0, currentAmount = 0.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
            , homeCurrency = "EUR"
        )

        assertApproxEquals(0.0, result.safeAmount)
        assertApproxEquals(0.40, result.confidence, 0.001)
    }

    @Test
    fun `very high spending is clamped and never returns negative savings`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(remaining = -100.0)))
        val veryHigh = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(id = 1008L,
                amount = 2000.0,
                merchant = "Big",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns veryHigh
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns veryHigh.map { it.toExpenseSnapshot() }
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult(5000.0)

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(id = 0L, name = "Car", targetAmount = 7000.0, currentAmount = 1500.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
            , homeCurrency = "EUR"
        )

        assertEquals(0.0, result.safeAmount, 0.0)
        assertTrue(result.safeAmount >= 0.0)
    }

    @Test
    fun `portfolio recommendations allocate one safe amount across multiple goals`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(remaining = 300.0))
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val goals = listOf(
            SavingsGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 900.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now),
            SavingsGoal(id = 2L, name = "Vacation", targetAmount = 1000.0, currentAmount = 200.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
        )

        val recommendations = engine.calculatePortfolioRecommendations(goals, homeCurrency = "EUR")

        assertEquals(2, recommendations.size)
        assertApproxEquals(60.0, recommendations.sumOf { it.recommendation.safeAmount }, 0.01)
        // Allocation is proportional to each goal's remaining gap: 100 : 800.
        assertApproxEquals(6.666, recommendations.first { it.goal.id == 1L }.recommendation.safeAmount, 0.01)
        assertApproxEquals(53.333, recommendations.first { it.goal.id == 2L }.recommendation.safeAmount, 0.01)
    }

    @Test
    fun `portfolio recommendations cap allocation at remaining gap`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(remaining = 2000.0))
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val goals = listOf(
            SavingsGoal(id = 1L, name = "Laptop", targetAmount = 1000.0, currentAmount = 990.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now),
            SavingsGoal(id = 2L, name = "Trip", targetAmount = 1000.0, currentAmount = 995.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
        )

        val recommendations = engine.calculatePortfolioRecommendations(goals, homeCurrency = "EUR")

        assertApproxEquals(10.0, recommendations.first { it.goal.id == 1L }.recommendation.safeAmount, 0.01)
        assertApproxEquals(5.0, recommendations.first { it.goal.id == 2L }.recommendation.safeAmount, 0.01)
        assertApproxEquals(15.0, recommendations.sumOf { it.recommendation.safeAmount }, 0.01)
    }

    @Test
    fun `budget surplus uses overall budget without stacking category budgets`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(remaining = 400.0, categoryId = null),
                budgetStatus(remaining = 150.0, categoryId = 1L),
                budgetStatus(remaining = 100.0, categoryId = 2L)
            )
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(id = 0L, name = "Emergency", targetAmount = 5000.0, currentAmount = 1000.0, targetDate = null, protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT", createdAt = now)
            , homeCurrency = "EUR"
        )

        assertApproxEquals(80.0, result.safeAmount, 0.01)
    }

    private fun budgetStatus(remaining: Double, categoryId: Long? = null): BudgetStatus {
        val budgetAmount = if (remaining > 0) remaining + 100 else 100.0
        return BudgetStatus(
            budget = Budget(categoryId = categoryId, amount = budgetAmount, period = BudgetPeriod.MONTHLY, startDate = now),
            category = null,
            spentAmount = (budgetAmount - remaining).coerceAtLeast(0.0),
            remainingAmount = remaining,
            percentUsed = 0.0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now,
            periodEnd = now,
            effectiveLimit = budgetAmount
        )
    }

    private fun monteCarloResult(p50: Double) = MonteCarloResult(
        percentile10 = p50,
        percentile25 = p50,
        percentile50 = p50,
        percentile75 = p50,
        percentile90 = p50,
        probabilityUnderBudget = null,
        budgetAmount = null,
        spentToDate = 0.0,
        knownUpcoming = 0.0,
        confidence = SimulationConfidence(0.8, ConfidenceLevel.HIGH, "test"),
        metadata = SimulationMetadata(0, 0, 0, 0.0, 0.0, 0, now),
        displayCurrency = "EUR"
    )
}
