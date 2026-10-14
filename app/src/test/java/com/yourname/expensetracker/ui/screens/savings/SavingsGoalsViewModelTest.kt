package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import io.mockk.spyk
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceCoordinator
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceResult
import com.yourname.expensetracker.domain.savings.RecommendationSource
import com.yourname.expensetracker.domain.savings.SavingsAchievement
import com.yourname.expensetracker.domain.savings.SavingsGamificationEngine
import com.yourname.expensetracker.domain.savings.SavingsRecommendation
import com.yourname.expensetracker.domain.savings.SavingsStreak
import com.yourname.expensetracker.domain.savings.SmartSavingsComputationResult
import com.yourname.expensetracker.domain.savings.SmartSavingsOutcomeMetadata
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.savings.SmartSavingsEngine
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelTest : ViewModelTestUtils() {

    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)
    // Constructed spy preserves the clock evaluated by Kotlin default arguments.
    private val savingsContributionHistoryRepository = spyk(
        SavingsContributionHistoryRepository(dataStore = mockk(), timeProvider = timeProvider)
    )
    private val viewModels = mutableListOf<SavingsGoalsViewModel>()
    private val smartSavingsEngine = mockk<SmartSavingsEngine>(relaxed = true)
    private val gamificationEngine = mockk<SavingsGamificationEngine>(relaxed = true)
    private val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
    private val savingsSweepAcceptanceCoordinator = mockk<SavingsSweepAcceptanceCoordinator>(relaxed = true)
    private val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)

    private lateinit var goalsFlow: MutableStateFlow<List<SavingsGoal>>
    private val currencyFlow = MutableStateFlow("EUR")
    private val rateFlow = MutableStateFlow(0L)
    private lateinit var viewModel: SavingsGoalsViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) } returns true
        coEvery { savingsSweepAcceptanceCoordinator.accept(any()) } returns SavingsSweepAcceptanceResult.Accepted

        every { monthlySavingsSweepUseCase.shouldShowSweepPrompt() } returns false
        every { gamificationEngine.calculateLevel(any()) } answers {
            val totalSaved = invocation.args[0] as Double
            ((totalSaved / 500.0).toInt() + 1)
        }
        every { gamificationEngine.getLevelTitle(any()) } returns "Savings Rookie"
        coEvery { gamificationEngine.aggregateGoalBalances(any(), any()) } answers {
            val goals = invocation.args[0] as List<SavingsGoal>
            MoneyAggregateResult.Available(
                MoneyAggregate.singleCurrency(
                    amount = goals.sumOf { it.currentAmount },
                    currency = CurrencyCode("EUR"),
                    transactionCount = goals.size,
                    rateBasis = RateBasis.IDENTITY
                )
            )
        }

        coEvery { gamificationEngine.calculateStreak() } returns SavingsStreak(
            currentStreakDays = 0,
            personalBestDays = 0,
            lastSavingsDate = null,
            monthlyContributions = 0,
            totalContributedThisMonth = 0.0
        )
        coEvery { gamificationEngine.getAchievements(homeCurrency = any()) } returns emptyList<SavingsAchievement>()
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            SmartSavingsComputationResult.NoRecommendation()
        coEvery { smartSavingsEngine.calculateSafeToSaveAmount(any(), any(), any()) } returns SavingsRecommendation(
            safeAmount = 0.0,
            confidence = 0.5,
            impact = "No recommendation",
            source = RecommendationSource.BUDGET_SURPLUS
        )

        configureRepositoryWithGoals(emptyList())
    }

    @Test
    fun `initial state shows goals list`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(
            listOf(
                createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 200.0),
                createGoal(id = 2L, name = "Vacation", targetAmount = 1500.0, currentAmount = 100.0)
            )
        )
        viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.goals.size)
        assertEquals(300.0, requireNotNull(state.totalSaved), 0.0001)
        assertFalse(state.isLoading)
    }

    @Test
    fun `add goal updates state`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(
            listOf(
                createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 200.0)
            )
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addGoal(
            name = "New Bike",
            targetAmount = 800.0,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.goals.size)
        assertTrue(state.goals.any { it.name == "New Bike" && it.targetAmount == 800.0 })
        assertEquals("EUR", state.goals.first { it.name == "New Bike" }.currency)
    }

    @Test
    fun `progress update reflects in UI`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(
            listOf(
                createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 100.0)
            )
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.contributeToGoal(goalId = 1L, amount = 150.0)
        advanceUntilIdle()

        val state = viewModel.state.value
        val updatedGoal = state.goals.first { it.id == 1L }

        assertEquals(250.0, updatedGoal.currentAmount, 0.0001)
        assertEquals(250.0, requireNotNull(state.totalSaved), 0.0001)
        coVerify(exactly = 1) {
            savingsContributionHistoryRepository.recordContribution(1L, 150.0, timeProvider.now(), "manual", "EUR")
        }
    }

    @Test
    fun `contributeToGoal uses atomic addToGoalAmount`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(
            listOf(
                createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 100.0)
            )
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        // Two contributions in quick succession — both should stack via atomic add
        viewModel.contributeToGoal(goalId = 1L, amount = 50.0)
        viewModel.contributeToGoal(goalId = 1L, amount = 75.0)
        advanceUntilIdle()

        val state = viewModel.state.value
        val updatedGoal = state.goals.first { it.id == 1L }

        // 100.0 + 50.0 + 75.0 = 225.0
        assertEquals(225.0, updatedGoal.currentAmount, 0.0001)
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(1L, 50.0) }
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(1L, 75.0) }
    }

    @Test
    fun `contributeToGoal with nonexistent goal does not crash`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(
            listOf(
                createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 100.0)
            )
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        // Goal 999 doesn't exist — should silently succeed (no-op)
        viewModel.contributeToGoal(goalId = 999L, amount = 50.0)
        advanceUntilIdle()

        // State should be unchanged
        val state = viewModel.state.value
        assertEquals(1, state.goals.size)
        assertEquals(100.0, state.goals.first().currentAmount, 0.0001)
    }

    @Test
    fun `empty state when no goals`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(emptyList())
        viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.goals.isEmpty())
        assertEquals(0.0, requireNotNull(state.totalSaved), 0.0001)
        assertFalse(state.isLoading)
    }

    @Test
    fun `loadGoals uses canonical portfolio recommendations without per goal duplication`() = runTest(testDispatcher) {
        val goals = listOf(
            createGoal(id = 1L, name = "Emergency", targetAmount = 1000.0, currentAmount = 200.0),
            createGoal(id = 2L, name = "Vacation", targetAmount = 1500.0, currentAmount = 100.0)
        )
        configureRepositoryWithGoals(goals)
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(goals, SmartSavingsEngine.TimeHorizon.MONTH, "EUR") } returns SmartSavingsComputationResult.Available(listOf(
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal = goals[0],
                recommendation = SavingsRecommendation(
                    safeAmount = 25.0,
                    confidence = 0.8,
                    impact = "Good",
                    source = RecommendationSource.BUDGET_SURPLUS,
                    currency = "EUR"
                )
            ),
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal = goals[1],
                recommendation = SavingsRecommendation(
                    safeAmount = 15.0,
                    confidence = 0.7,
                    impact = "Also good",
                    source = RecommendationSource.SPENDING_PACE,
                    currency = "EUR"
                )
            )
        ))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.smartRecommendations.size)
        assertEquals(25.0, state.smartRecommendations.first { it.goal.id == 1L }.recommendedAmount, 0.0001)
        assertEquals(15.0, state.smartRecommendations.first { it.goal.id == 2L }.recommendedAmount, 0.0001)
        coVerify(exactly = 1) {
            smartSavingsEngine.calculatePortfolioRecommendationsResult(goals, SmartSavingsEngine.TimeHorizon.MONTH, "EUR")
        }
        coVerify(exactly = 0) {
            smartSavingsEngine.calculateSafeToSaveAmount(any(), any(), any())
        }
    }

    @org.junit.After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                viewModels.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            }
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun `currency changes invalidate a held recommendation before acceptance`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns SmartSavingsComputationResult.Available(listOf(
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR")
            )
        ))
        viewModel = createViewModel()
        advanceUntilIdle()
        val recommendation = viewModel.state.value.smartRecommendations.single()
        currencyFlow.value = "USD"
        advanceUntilIdle()
        viewModel.acceptSmartRecommendation(recommendation)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
    }

    @Test
    fun `unavailable savings preserves unknown money and level`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(listOf(createGoal(1L, "Emergency", 1000.0, 200.0)))
        coEvery { gamificationEngine.aggregateGoalBalances(any(), any()) } returns MoneyAggregateResult.Unavailable(
            "DISPLAY_CONVERSION_UNAVAILABLE", RateBasis.LATEST_AVAILABLE
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        assertNull(viewModel.state.value.totalSaved)
        assertNull(viewModel.state.value.moneyTotalSaved)
        assertNull(viewModel.state.value.userLevel)
        assertTrue(viewModel.state.value.totalSavedResult is MoneyAggregateResult.Unavailable)
    }

    @Test
    fun `currency changes recompute money without a goals emission`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(listOf(createGoal(1L, "Emergency", 1000.0, 200.0)))
        coEvery { gamificationEngine.aggregateGoalBalances(any(), "USD") } returns MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(220.0, CurrencyCode.USD, 1)
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        currencyFlow.value = "USD"
        advanceUntilIdle()
        assertEquals("USD", viewModel.state.value.homeCurrency)
        assertEquals(220.0, requireNotNull(viewModel.state.value.totalSaved), 0.0001)
        assertEquals(CurrencyCode.USD, viewModel.state.value.moneyTotalSaved?.currency)
        coVerify { gamificationEngine.aggregateGoalBalances(any(), "USD") }
    }

    @Test
    fun `rate changes reject held recommendations even when the amount is unchanged`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns SmartSavingsComputationResult.Available(listOf(
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR")
            )
        ))
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        rateFlow.value = 123L
        advanceUntilIdle()
        val current = viewModel.state.value.smartRecommendations.single()
        assertEquals(held.recommendedAmount, current.recommendedAmount, 0.0)
        assertTrue(held.computationId != current.computationId)
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
    }

    @Test
    fun `goal basis change rejects recommendation before the collector recomputes`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns SmartSavingsComputationResult.Available(listOf(
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR")
            )
        ))
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        goalsFlow.value = listOf(goal.copy(currentAmount = 300.0))
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
    }

    @Test
    fun `partial savings retains result but cannot calculate a level`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(listOf(createGoal(1L, "Emergency", 1000.0, 200.0)))
        val partial = MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(
            100.0, CurrencyCode.EUR, 1
        ).copy(isPartial = true, conversionQuality = com.yourname.expensetracker.domain.core.money.ConversionQuality.PARTIAL))
        coEvery { gamificationEngine.aggregateGoalBalances(any(), any()) } returns partial
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(partial, viewModel.state.value.totalSavedResult)
        assertNull(viewModel.state.value.totalSaved)
        assertNull(viewModel.state.value.moneyTotalSaved)
        assertNull(viewModel.state.value.userLevel)
        coVerify(exactly = 0) { gamificationEngine.calculateLevel(any()) }
    }

    @Test
    fun `sweep rate change between computation and acceptance prevents persistence`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val sweep = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, listOf(com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                goal.id, goal.name, goal.currentAmount, goal.targetAmount, 40.0, 1.0, "EUR"
            )), timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns SavingsSweepComputationResult.Available(sweep)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        assertEquals(sweep, viewModel.state.value.sweepRecommendation)
        rateFlow.value = 123L
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { savingsSweepAcceptanceCoordinator.accept(any()) }
        assertNull(viewModel.state.value.sweepRecommendation)
        assertEquals("SAVINGS_SWEEP_STALE_RECOMMENDATION", viewModel.state.value.sweepUnavailableReason)
    }

    @Test
    fun unavailableSmartOutcomeRetainsMetadataAndCannotBeAccepted() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val outcome = SmartSavingsComputationResult.Unavailable(
            "DISPLAY_CONVERSION_UNAVAILABLE",
            SmartSavingsOutcomeMetadata(
                currency = "EUR", conversionQuality = ConversionQuality.UNAVAILABLE,
                totalInputCount = 3, excludedInputCount = 3,
                warningCodes = listOf("STALE_EXCHANGE_RATE"), latestRateTimestamp = 123L
            )
        )
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns outcome
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(outcome, viewModel.state.value.smartComputationResult)
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", viewModel.state.value.smartUnavailableReason)
        assertTrue(viewModel.state.value.smartRecommendations.isEmpty())
        viewModel.acceptSmartRecommendation(SmartRecommendation(goal, 25.0, 0.9, "TEST", "TEST", "EUR"))
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { smartSavingsEngine.calculatePortfolioRecommendations(any(), any(), any()) }
    }

    @Test
    fun validNoRecommendationIsRetainedWithoutUnavailableMessage() = runTest(testDispatcher) {
        val outcome = SmartSavingsComputationResult.NoRecommendation(SmartSavingsOutcomeMetadata(currency = "EUR"))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns outcome
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(outcome, viewModel.state.value.smartComputationResult)
        assertNull(viewModel.state.value.smartUnavailableReason)
        assertTrue(viewModel.state.value.smartRecommendations.isEmpty())
    }

    @Test
    fun availableSmartOutcomeRetainsQualityNotJustDisplayAmount() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val outcome = SmartSavingsComputationResult.Available(
            listOf(com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR")
            )),
            SmartSavingsOutcomeMetadata(currency = "EUR", totalInputCount = 4, includedInputCount = 4,
                latestRateTimestamp = 123L, requestedRateBases = setOf(RateBasis.TRANSACTION_DATE))
        )
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns outcome
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(outcome, viewModel.state.value.smartComputationResult)
        assertEquals(25.0, viewModel.state.value.smartRecommendations.single().recommendedAmount, 0.0)
        assertNull(viewModel.state.value.smartUnavailableReason)
    }

    @Test
    fun `sweep unavailable state preserves controlled reason instead of empty recommendation`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(listOf(createGoal(1L, "Emergency", 1000.0, 200.0)))
        val outcome = SavingsSweepComputationResult.Unavailable(
            "DISPLAY_CONVERSION_UNAVAILABLE", SmartSavingsOutcomeMetadata(
                currency = "EUR", conversionQuality = ConversionQuality.UNAVAILABLE,
                excludedInputCount = 2, totalInputCount = 2, warningCodes = listOf("MISSING_EXCHANGE_RATE")
            )
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns outcome
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        assertNull(viewModel.state.value.sweepRecommendation)
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", viewModel.state.value.sweepUnavailableReason)
        assertEquals(outcome, viewModel.state.value.sweepComputationResult)
    }

    @Test
    fun multi_goal_sweep_delegates_to_durable_batch_transaction() = runTest(testDispatcher) {
        val first = createGoal(1L, "Emergency", 1000.0, 200.0)
        val second = createGoal(2L, "Vacation", 1000.0, 100.0)
        configureRepositoryWithGoals(listOf(first, second))
        val sweep = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, listOf(
                com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                    first.id, first.name, first.currentAmount, first.targetAmount, 20.0, 1.0, "EUR"
                ),
                com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                    second.id, second.name, second.currentAmount, second.targetAmount, 20.0, 1.0, "EUR"
                )
            ), timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns SavingsSweepComputationResult.Available(sweep)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()

        coVerify(exactly = 1) { savingsSweepAcceptanceCoordinator.accept(sweep) }
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `acceptance recomputes safe money instead of persisting a stale pool`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val initial = com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
            goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR"))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            SmartSavingsComputationResult.Available(listOf(initial))
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns SmartSavingsComputationResult.Available(listOf(
            initial.copy(recommendation = initial.recommendation.copy(safeAmount = 10.0))
        ))
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
    }

    @Test
    fun `accepted same currency recommendation records its truthful contribution units`() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Emergency", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns SmartSavingsComputationResult.Available(listOf(
            com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
                goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR"))
        ))
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        assertEquals(CurrencyCode.EUR, held.moneyRecommendedAmount?.currency)
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(goal.id, 25.0) }
        coVerify(exactly = 1) { savingsContributionHistoryRepository.recordContribution(goal.id, 25.0, any(), "manual", "EUR") }
    }

    @Test
    fun unknownGoalsCannotPublishActionableSmartRecommendationsOrReceiveManualMoney() = runTest(testDispatcher) {
        val legacy = createGoal(1L, "Legacy", 1000.0, 0.0).copy(currencyAssumption = "LEGACY_DEFAULT")
        configureRepositoryWithGoals(listOf(legacy))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            availableSmartResult(legacy)
        viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.smartRecommendations.isEmpty())
        viewModel.contributeToGoal(legacy.id, 10.0, "EUR")
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun smartAcceptanceRechecksProvenanceWithoutGoalEmission() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            availableSmartResult(goal)
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal.copy(currencyAssumption = "UNKNOWN"))
        )
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun provenanceChangedDuringSmartRecomputationCannotBeWritten() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            availableSmartResult(goal)
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } answers {
            every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
                listOf(goal.copy(currencyAssumption = "LEGACY_DEFAULT"))
            )
            availableSmartResult(goal)
        }
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun smartAcceptanceRechecksHomeSettingsWithoutFlowEmission() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            availableSmartResult(goal)
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        every { currencyRepo.homeCurrency() } returns flowOf("USD")
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        assertEquals("EUR", viewModel.state.value.homeCurrency)
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun sweepAcceptanceRejectsLegacyProvenanceAndChangedHomeWithoutEmissions() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val sweep = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, listOf(com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                goal.id, goal.name, goal.currentAmount, goal.targetAmount, 40.0, 1.0, "EUR"
            )), timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.Available(sweep)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        assertEquals(sweep, viewModel.state.value.sweepRecommendation)
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal.copy(currencyAssumption = "LEGACY_DEFAULT"))
        )
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        every { savingsGoalRepository.observeSavingsGoals() } returns goalsFlow
        every { currencyRepo.homeCurrency() } returns flowOf("GBP")
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsSweepAcceptanceCoordinator.accept(any()) }
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun sweepAcceptanceRechecksSettingsAfterRecomputation() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val sweep = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, listOf(com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                goal.id, goal.name, goal.currentAmount, goal.targetAmount, 40.0, 1.0, "EUR"
            )), timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.Available(sweep)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        var changeRate = false
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } coAnswers {
            if (changeRate) {
                every { currencyRepo.lastRateUpdate() } returns flowOf(1L)
            } else {
                every { currencyRepo.homeCurrency() } returns flowOf("USD")
            }
            SavingsSweepComputationResult.Available(sweep)
        }
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        every { currencyRepo.homeCurrency() } returns currencyFlow
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.Available(sweep)
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        changeRate = true
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } coAnswers {
            every { currencyRepo.lastRateUpdate() } returns flowOf(1L)
            SavingsSweepComputationResult.Available(sweep)
        }
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        coVerify(exactly = 0) { savingsSweepAcceptanceCoordinator.accept(any()) }
        assertEquals(200.0, goalsFlow.value.single().currentAmount, 0.0)
        assertNull(viewModel.state.value.sweepRecommendation)
        assertEquals("SAVINGS_SWEEP_STALE_RECOMMENDATION", viewModel.state.value.sweepUnavailableReason)
    }

    @Test
    fun invalidManualMoneyCannotReachEitherPersistenceStore() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        viewModel = createViewModel()
        advanceUntilIdle()
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            viewModel.contributeToGoal(goal.id, amount, "EUR")
        }
        advanceUntilIdle()
        assertEquals(200.0, goalsFlow.value.single().currentAmount, 0.0)
        assertNull(viewModel.state.value.contributionHistoryWriteFailure)
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun rejectedManualHistoryWriteIsVisibleAndCannotBeRetriedAsSuccess() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) } returns false
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.contributeToGoal(goal.id, 25.0, "EUR")
        advanceUntilIdle()
        assertEquals(225.0, goalsFlow.value.single().currentAmount, 0.0)
        assertEquals("SAVINGS_HISTORY_WRITE_REJECTED", viewModel.state.value.contributionHistoryWriteFailure)
        assertNull(viewModel.state.value.streak)
        assertTrue(viewModel.state.value.achievements.isEmpty())
        viewModel.contributeToGoal(goal.id, 25.0, "EUR")
        advanceUntilIdle()
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(goal.id, 25.0) }
        coVerify(exactly = 1) { savingsContributionHistoryRepository.recordContribution(goal.id, 25.0, any(), "manual", "EUR") }
    }

    @Test
    fun failedSmartHistoryWriteSuppressesHeldAndRecomputedActions() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            availableSmartResult(goal)
        coEvery { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) } returns false
        viewModel = createViewModel()
        advanceUntilIdle()
        val held = viewModel.state.value.smartRecommendations.single()
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        assertEquals("SAVINGS_HISTORY_WRITE_REJECTED", viewModel.state.value.contributionHistoryWriteFailure)
        assertTrue(viewModel.state.value.smartRecommendations.isEmpty())
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.smartRecommendations.isEmpty())
        viewModel.acceptSmartRecommendation(held)
        advanceUntilIdle()
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(goal.id, 25.0) }
        coVerify(exactly = 1) { savingsContributionHistoryRepository.recordContribution(goal.id, 25.0, any(), "manual", "EUR") }
    }

    @Test
    fun rejected_sweep_acceptance_remains_visible_with_controlled_reason() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        val sweep = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, listOf(com.yourname.expensetracker.domain.usecase.savings.GoalAllocation(
                goal.id, goal.name, goal.currentAmount, goal.targetAmount, 40.0, 1.0, "EUR"
            )), timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.Available(sweep)
        coEvery { savingsSweepAcceptanceCoordinator.accept(sweep) } returns
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_GOAL_STALE")
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        assertEquals("SAVINGS_GOAL_STALE", viewModel.state.value.sweepUnavailableReason)
        assertEquals(sweep, viewModel.state.value.sweepRecommendation)
        assertEquals(200.0, goalsFlow.value.single().currentAmount, 0.0)
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        assertEquals(sweep, viewModel.state.value.sweepRecommendation)
        viewModel.acceptSweepRecommendation()
        advanceUntilIdle()
        coVerify(exactly = 2) { savingsSweepAcceptanceCoordinator.accept(sweep) }
        coVerify(exactly = 0) { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) }
        coVerify(exactly = 0) { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) }
    }

    @Test
    fun historyStorageFailureUsesOnlyControlledReasonWithoutPayload() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) } throws
            IllegalStateException("SECRET /private/history financial payload")
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.contributeToGoal(goal.id, 25.0, "EUR")
        advanceUntilIdle()
        assertEquals("SAVINGS_HISTORY_WRITE_UNAVAILABLE", viewModel.state.value.contributionHistoryWriteFailure)
        assertTrue(!viewModel.state.value.toString().contains("SECRET"))
        assertNull(viewModel.state.value.streak)
        viewModel.contributeToGoal(goal.id, 25.0, "EUR")
        advanceUntilIdle()
        coVerify(exactly = 1) { savingsGoalRepository.incrementSavingsGoalAmount(goal.id, 25.0) }
    }

    @Test
    fun historyWriteCancellationIsNotConvertedToOrdinaryFailure() = runTest(testDispatcher) {
        val goal = createGoal(1L, "Known", 1000.0, 200.0)
        configureRepositoryWithGoals(listOf(goal))
        coEvery { savingsContributionHistoryRepository.recordContribution(any(), any(), any(), any(), any()) } throws
            kotlinx.coroutines.CancellationException("SECRET cancellation")
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.contributeToGoal(goal.id, 25.0, "EUR")
        advanceUntilIdle()
        assertNull(viewModel.state.value.contributionHistoryWriteFailure)
        coVerify(exactly = 1) { savingsContributionHistoryRepository.recordContribution(goal.id, 25.0, any(), "manual", "EUR") }
    }

    private fun availableSmartResult(goal: SavingsGoal) = SmartSavingsComputationResult.Available(listOf(
        com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation(
            goal, SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, goal.currency)
        )
    ))

    private fun configureRepositoryWithGoals(initialGoals: List<SavingsGoal>) {
        goalsFlow = MutableStateFlow(initialGoals)

        every { savingsGoalRepository.observeSavingsGoals() } returns goalsFlow

        coEvery { savingsGoalRepository.createSavingsGoal(any()) } coAnswers {
            val incoming = invocation.args[0] as SavingsGoal
            val nextId = (goalsFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
            goalsFlow.value = goalsFlow.value + incoming.copy(id = nextId)
            nextId
        }

        coEvery { savingsGoalRepository.incrementSavingsGoalAmount(any(), any()) } coAnswers {
            val goalId = invocation.args[0] as Long
            val delta = invocation.args[1] as Double
            val exists = goalsFlow.value.any { it.id == goalId }
            if (exists) {
                goalsFlow.value = goalsFlow.value.map { goal ->
                    if (goal.id == goalId) goal.copy(currentAmount = goal.currentAmount + delta) else goal
                }
            }
            exists
        }
    }

    private fun createViewModel(): SavingsGoalsViewModel {
        every { currencyRepo.homeCurrency() } returns currencyFlow
        every { currencyRepo.lastRateUpdate() } returns rateFlow
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return SavingsGoalsViewModel(
            savingsGoalRepository = savingsGoalRepository,
            savingsContributionHistoryRepository = savingsContributionHistoryRepository,
            smartSavingsEngine = smartSavingsEngine,
            gamificationEngine = gamificationEngine,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            savingsSweepAcceptanceCoordinator = savingsSweepAcceptanceCoordinator,
            timeProvider = timeProvider,
            currencySettingsRepository = currencyRepo,
        ).also { viewModels += it }
    }

    private fun createGoal(
        id: Long,
        name: String,
        targetAmount: Double,
        currentAmount: Double
    ): SavingsGoal {
        return SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            currencyAssumption = "EXPLICIT",
            createdAt = timeProvider.now(),
        )
    }
}
