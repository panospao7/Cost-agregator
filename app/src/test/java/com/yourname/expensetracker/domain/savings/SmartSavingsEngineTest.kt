package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.dao.ExpenseDao
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
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
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
import io.mockk.*
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
        every {
            spendingThresholdCalculator.calculateHighAmountThresholdFromNormalizedAmounts(any())
        } returns 500.0

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
        // discretionary budget (30) is compared against projected DISCRETIONARY spending (total 400 scaled by historical share 90 / 390 = 3/13 => 92.3077), not total spending:
        // remaining = 30 - (92.3077 * 0.30) = 2.3077 => monte-carlo component = 2.3077 * 0.20 = 0.4615 => weighted (0.30) = 0.13846
        // pace also 0 (projected above baseline), budget surplus 0 => safe amount 0.13846
        assertApproxEquals(0.1384615384615386, result.safeAmount, 0.01)
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

    @Test
    fun adaptiveCapUsesRealNormalizedMixedCurrencyPercentileAndSparseMinimum() = runTest {
        for (homeCurrency in listOf("EUR", "USD", "GBP")) {
            for (sampleCount in listOf(1, 10)) {
                val dao = mockk<ExpenseDao>()
                val converter = mockk<CurrencyConverter>()
                coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
                    val amount = firstArg<Double>()
                    val source = secondArg<String>()
                    val target = thirdArg<String>()
                    ConversionOutcome.Converted(
                        originalAmount = amount, originalCurrency = CurrencyCode.parse(source)!!,
                        convertedAmount = amount / 100.0, targetCurrency = CurrencyCode.parse(target)!!,
                        rateUsed = 0.01, rateBasis = RateBasis.TRANSACTION_DATE,
                        rateValidDate = arg<Long?>(4), rateLastUpdated = now,
                        rateSource = "test", conversionPath = ConversionPath.DIRECT
                    )
                }
                val history = (1..sampleCount).map { index ->
                    com.yourname.expensetracker.data.database.entity.Expense(
                        id = index.toLong(), amount = if (index % 2 == 1) 10000.0 else 100.0,
                        currency = if (index % 2 == 1) "JPY" else homeCurrency,
                        merchant = "Fixture", transactionType = TransactionType.PURCHASE,
                        date = now - 60L * 24 * 60 * 60 * 1000
                    ).toExpenseSnapshot()
                }
                coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
                    val start = firstArg<Long>()
                    val end = secondArg<Long>()
                    history.filter { it.date >= start && it.date < end }
                }
                val status = budgetStatus(10000.0)
                every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
                    status.copy(currency = homeCurrency, budget = status.budget.copy(currency = homeCurrency))
                ))
                coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
                val result = realCapEngine(converter, dao).calculatePortfolioRecommendationsResult(
                    listOf(typedGoal().copy(currency = homeCurrency)),
                    timeHorizon = SmartSavingsEngine.TimeHorizon.WEEK, homeCurrency = homeCurrency
                ) as SmartSavingsComputationResult.Available

                assertApproxEquals(if (sampleCount == 1) 10.0 else 15.0,
                    result.recommendations.single().recommendation.safeAmount)
                val capQuality = result.metadata.inputs.single { it.stage == SavingsInputStage.SAVINGS_CAP_HISTORY }
                assertEquals(sampleCount, capQuality.includedInputCount)
                assertEquals(0, capQuality.excludedInputCount)
                assertEquals(RateBasis.TRANSACTION_DATE, capQuality.actualRateBasis)
                coVerify(exactly = 0) { dao.getAmountsForPercentileCalc(any(), any()) }
            }
        }
    }

    @Test
    fun adaptiveCapFailsClosedOnMissingOrStaleRateEvenWhenOtherWindowsAreEmpty() = runTest {
        for (staleRate in listOf(false, true)) {
            val dao = mockk<ExpenseDao>()
            val converter = mockk<CurrencyConverter>()
            val history = listOf(com.yourname.expensetracker.data.database.entity.Expense(
                id = 1L, amount = 10000.0, currency = "JPY", merchant = "Fixture",
                transactionType = TransactionType.PURCHASE, date = now - 60L * 24 * 60 * 60 * 1000
            ).toExpenseSnapshot())
            coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
                val start = firstArg<Long>()
                val end = secondArg<Long>()
                history.filter { it.date >= start && it.date < end }
            }
            coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
                if (staleRate) ConversionOutcome.Converted(
                    originalAmount = 10000.0, originalCurrency = CurrencyCode.parse("JPY")!!,
                    convertedAmount = 100.0, targetCurrency = CurrencyCode.parse("EUR")!!, rateUsed = 0.01,
                    rateBasis = RateBasis.TRANSACTION_DATE,
                    rateValidDate = history.single().date - 30L * 24 * 60 * 60 * 1000,
                    rateLastUpdated = now, rateSource = "test", conversionPath = ConversionPath.DIRECT
                ) else ConversionOutcome.Failed(
                    originalAmount = 10000.0, originalCurrency = "JPY", targetCurrency = "EUR",
                    rateBasis = RateBasis.TRANSACTION_DATE, failureType = ConversionFailureType.MISSING_RATE,
                    message = "SECRET fixture"
                )
            }
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(10000.0)))
            coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
            val result = realCapEngine(converter, dao).calculatePortfolioRecommendationsResult(
                listOf(typedGoal()), timeHorizon = SmartSavingsEngine.TimeHorizon.WEEK, homeCurrency = "EUR"
            ) as SmartSavingsComputationResult.Unavailable

            assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
            assertTrue(!result.toString().contains("SECRET"))
            val capQuality = result.metadata.inputs.single { it.stage == SavingsInputStage.SAVINGS_CAP_HISTORY }
            assertEquals(1, capQuality.totalInputCount)
            assertEquals(if (staleRate) 0 else 1, capQuality.excludedInputCount)
            assertTrue((if (staleRate) "STALE_EXCHANGE_RATE" else "MISSING_EXCHANGE_RATE") in capQuality.warningCodes)
            coVerify(exactly = 0) { dao.getAmountsForPercentileCalc(any(), any()) }
        }
    }

    @Test
    fun `bl03 regression rent 1000 plus dining 300 recommends meaningful savings above 25`() = runTest {
        val dao = mockk<ExpenseDao>()
        val converter = mockk<CurrencyConverter>()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(remaining = 300.0))
        )
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Housing", icon = "🏠", color = "#0000FF"),
            Category(id = 2L, name = "Dining", icon = "🍽️", color = "#FF8800")
        )

        // 3 months of history: 3 x 1000 EUR rent (essential) + 90 x 30 EUR dining (300/month discretionary).
        // Total monthly spend = 1300 EUR (1000 rent + 300 dining).
        val dayMs = 24L * 60 * 60 * 1000
        val rentHistory = (1..3).map { monthIdx ->
            com.yourname.expensetracker.data.database.entity.Expense(
                id = monthIdx.toLong(),
                amount = 1000.0,
                currency = "EUR",
                merchant = "Landlord",
                categoryId = 1L,
                transactionType = TransactionType.PURCHASE,
                date = now - (monthIdx * 25L * dayMs)
            ).toExpenseSnapshot()
        }
        val diningHistory = (1..90).map { idx ->
            com.yourname.expensetracker.data.database.entity.Expense(
                id = (100 + idx).toLong(),
                amount = 30.0,
                currency = "EUR",
                merchant = "Bistro",
                categoryId = 2L,
                transactionType = TransactionType.PURCHASE,
                date = now - ((idx % 85 + 2) * dayMs)
            ).toExpenseSnapshot()
        }
        val allHistory = rentHistory + diningHistory

        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            // Keep current-month spend at 0 so the user has the full 300/month discretionary headroom,
            // while historical lookback windows receive the 3x1000 rent + 90x30 dining history.
            if (start < now - 20L * dayMs) allHistory.filter { it.date in start until end } else emptyList()
        }

        // Simulator returns total projected spend of 1300 (1000 rent + 300 dining).
        // Old Defect A subtracted 0.30 * 1300 = 390 from the 300 discretionary baseline -> -90 -> 0.
        // Old Defect B computed P90 over single transactions (90x30 + 3x1000 -> P90 = 30, floored to 50 -> cap = 25).
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            monteCarloResult(1300.0).copy(spentToDate = 1000.0, knownUpcoming = 0.0)

        val result = realCapEngine(converter, dao).calculatePortfolioRecommendationsResult(
            listOf(typedGoal()),
            timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH,
            homeCurrency = "EUR"
        ) as SmartSavingsComputationResult.Available

        val safeAmount = result.recommendations.single().recommendation.safeAmount
        assertTrue(
            "Expected recommendation well above the old 25.0 single-tx cap and within 300.0 headroom, but was $safeAmount",
            safeAmount in 60.0..300.0
        )
    }

    @Test
    fun `bl03 monte carlo component isolates discretionary spend when rent dominates total projection`() = runTest {
        // Zero budget surplus and zero pace surplus so ONLY the Monte Carlo component can contribute.
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Housing", icon = "🏠", color = "#0000FF"),
            Category(id = 2L, name = "Dining", icon = "🍽️", color = "#FF8800")
        )

        val dayMs = 24L * 60 * 60 * 1000
        // 3 months of history: 3 x 1000 EUR rent (essential) + 9 x 100 EUR dining (300/month discretionary).
        // Current month (day 15 of 30) already has 1000 EUR rent + 150 EUR dining = 1150 EUR spent
        // (projected pace = 2300 >= avgMonthly 1300, so pace surplus = 0).
        val currentMonthExpenses = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(
                id = 1L, amount = 1000.0, currency = "EUR", merchant = "Landlord",
                categoryId = 1L, transactionType = TransactionType.PURCHASE, date = now - dayMs
            ),
            com.yourname.expensetracker.data.database.entity.Expense(
                id = 2L, amount = 150.0, currency = "EUR", merchant = "Bistro",
                categoryId = 2L, transactionType = TransactionType.PURCHASE, date = now - dayMs
            )
        )
        val historicalExpenses = (1..3).flatMap { m ->
            listOf(
                com.yourname.expensetracker.data.database.entity.Expense(
                    id = (10 + m).toLong(), amount = 1000.0, currency = "EUR", merchant = "Landlord",
                    categoryId = 1L, transactionType = TransactionType.PURCHASE, date = now - (m * 28L * dayMs)
                ),
                com.yourname.expensetracker.data.database.entity.Expense(
                    id = (20 + m).toLong(), amount = 300.0, currency = "EUR", merchant = "Bistro",
                    categoryId = 2L, transactionType = TransactionType.PURCHASE, date = now - (m * 28L * dayMs)
                )
            )
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            if (start < now - 20L * dayMs) historicalExpenses else currentMonthExpenses
        }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            if (start < now - 20L * dayMs) historicalExpenses.map { it.toExpenseSnapshot() }
            else currentMonthExpenses.map { it.toExpenseSnapshot() }
        }

        // Simulator projects 1300 total (1000 rent + 300 dining).
        // Old Defect A: discretionaryBaseline (300) - 0.30 * totalProjected (1300) = 300 - 390 = -90 -> 0.0!
        // Fixed like-for-like: discretionaryBaseline (300) - 0.30 * discretionaryProjected (300) = 210
        // -> mcBuffer = 210 * 0.20 = 42.0 -> weighted 0.30 * 42.0 = 12.60.
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            monteCarloResult(1300.0).copy(spentToDate = 1150.0, knownUpcoming = 0.0)

        val result = engine.calculateSafeToSaveAmount(typedGoal(), homeCurrency = "EUR")

        assertTrue(result.isAvailable)
        assertTrue(
            "Monte Carlo component must stay positive (~12.60) instead of collapsing to 0.0 from rent",
            result.safeAmount in 8.0..45.0
        )
    }

    @Test
    fun `bl03 monthly cap scales with aggregate volume instead of single transaction p90`() = runTest {
        val dao = mockk<ExpenseDao>()
        val converter = mockk<CurrencyConverter>()
        // Large budget surplus so the monthly cap is the binding constraint.
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(remaining = 2000.0))
        )
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 2L, name = "Dining", icon = "🍽️", color = "#FF8800")
        )

        val dayMs = 24L * 60 * 60 * 1000
        // 90 small transactions of 30 EUR across 3 months (900 EUR/month aggregate, single-tx P90 = 30 EUR).
        val smallTxHistory = (1..90).map { idx ->
            com.yourname.expensetracker.data.database.entity.Expense(
                id = idx.toLong(),
                amount = 30.0,
                currency = "EUR",
                merchant = "Cafe",
                categoryId = 2L,
                transactionType = TransactionType.PURCHASE,
                date = now - ((idx % 85 + 2) * dayMs)
            ).toExpenseSnapshot()
        }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            if (start < now - 20L * dayMs) smallTxHistory.filter { it.date in start until end } else emptyList()
        }
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val engineWithRealCap = realCapEngine(converter, dao)
        val monthResult = engineWithRealCap.calculatePortfolioRecommendationsResult(
            listOf(typedGoal()),
            timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH,
            homeCurrency = "EUR"
        ) as SmartSavingsComputationResult.Available
        val weekResult = engineWithRealCap.calculatePortfolioRecommendationsResult(
            listOf(typedGoal()),
            timeHorizon = SmartSavingsEngine.TimeHorizon.WEEK,
            homeCurrency = "EUR"
        ) as SmartSavingsComputationResult.Available

        val monthSafe = monthResult.recommendations.single().recommendation.safeAmount
        val weekSafe = weekResult.recommendations.single().recommendation.safeAmount

        // Old single-tx cap: P90(30) = 30 -> floored to 50 -> 0.40 * 50 = 20 -> floored to 25.00.
        // With 900 EUR/month spending and 2,000 EUR surplus, the monthly cap must be well above 25.00
        // and strictly greater than the weekly cap.
        assertTrue(
            "Monthly recommendation ($monthSafe) must not be capped at the old single-tx floor (25.0)",
            monthSafe >= 100.0
        )
        assertTrue(
            "Monthly cap ($monthSafe) must scale above weekly cap ($weekSafe)",
            monthSafe > weekSafe * 2.0
        )
    }

    @Test
    fun `bl03 calculateSafeToSaveAmount fails closed when forecast is partial or unavailable`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(remaining = 700.0)))
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        val partialForecast = monteCarloResult(300.0).let { res ->
            res.copy(
                metadata = res.metadata.copy(
                    historicalMoneyQuality = HistoricalMoneyQuality(
                        quality = ConversionQuality.PARTIAL,
                        totalInputCount = 6,
                        includedInputCount = 5,
                        excludedInputCount = 1
                    )
                )
            )
        }
        for (badForecast in listOf(MonteCarloResult.unavailable("UNAVAILABLE"), partialForecast)) {
            coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns badForecast

            val singleGoalRec = engine.calculateSafeToSaveAmount(typedGoal(), homeCurrency = "EUR")
            assertTrue("Expected single-goal recommendation to be unavailable", !singleGoalRec.isAvailable)
            assertEquals("Unavailable recommendation must not fabricate a safeAmount", 0.0, singleGoalRec.safeAmount, 0.0)
        }
    }

    @Test
    fun `neutralised foreign row with missing exchange rate does not fail complete money input in savings`() = runTest {
        val dao = mockk<ExpenseDao>()
        val converter = mockk<CurrencyConverter>()
        val neutralisedForeign = com.yourname.expensetracker.data.database.entity.Expense(
            id = 500L,
            amount = 400.0,
            currency = "USD",
            merchant = "Installment Purchase",
            transactionType = TransactionType.PURCHASE,
            date = now - 5L * 24 * 60 * 60 * 1000,
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        ).toExpenseSnapshot()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns listOf(neutralisedForeign)
        coEvery { converter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } returns
            ConversionOutcome.Failed(
                originalAmount = 0.0,
                originalCurrency = "USD",
                targetCurrency = "EUR",
                rateBasis = RateBasis.TRANSACTION_DATE,
                failureType = ConversionFailureType.MISSING_RATE,
                message = "No USD->EUR rate"
            )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(300.0)))
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult(0.0)

        val result = realCapEngine(converter, dao).calculatePortfolioRecommendationsResult(
            listOf(typedGoal()),
            timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH,
            homeCurrency = "EUR"
        )

        assertTrue(result is SmartSavingsComputationResult.Available)
        val available = result as SmartSavingsComputationResult.Available
        assertEquals(ConversionQuality.COMPLETE, available.metadata.conversionQuality)
        assertEquals(0, available.metadata.excludedInputCount)
    }

    private fun realCapEngine(converter: CurrencyConverter, dao: ExpenseDao) = SmartSavingsEngine(
        expenseRepository, categoryRepository, budgetRepository, budgetCalculator, monteCarloSimulator,
        timeProvider, AnalyticsCurrencyNormalizer(converter), cashFlowCalculator,
        SpendingThresholdCalculator(
            dao,
            timeProvider,
            testDispatcher,
            AnalyticsCurrencyNormalizer(converter),
            mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository> {
                every { homeCurrency() } returns flowOf("EUR")
            }
        )
    )

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
