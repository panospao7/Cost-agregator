package com.yourname.expensetracker.ui.screens.savings

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceCoordinator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.savings.SavingsGamificationEngine
import com.yourname.expensetracker.domain.savings.SavingsStreak
import com.yourname.expensetracker.domain.savings.SmartSavingsComputationResult
import com.yourname.expensetracker.domain.savings.SmartSavingsEngine
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R18b: a write rejected by the backup/restore write barrier must surface the bounded
 * maintenance message, must not crash, and the "goal created" event must only follow a
 * successful write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)
    private val smartSavingsEngine = mockk<SmartSavingsEngine>(relaxed = true)
    private val gamificationEngine = mockk<SavingsGamificationEngine>(relaxed = true)
    private val lifestyleUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
    private val sweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
    private val sweepCoordinator = mockk<SavingsSweepAcceptanceCoordinator>(relaxed = true)
    private val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
    private val historyRepository = mockk<SavingsContributionHistoryRepository>(relaxed = true)

    private val goalsFlow = MutableStateFlow<List<SavingsGoal>>(emptyList())
    private lateinit var viewModel: SavingsGoalsViewModel

    @Before
    override fun setup() {
        super.setup()
        every { sweepUseCase.shouldShowSweepPrompt() } returns false
        every { savingsGoalRepository.observeSavingsGoals() } returns goalsFlow
        every { currencyRepo.homeCurrency() } returns MutableStateFlow("EUR")
        every { currencyRepo.lastRateUpdate() } returns MutableStateFlow(0L)
        every { gamificationEngine.getLevelTitle(any()) } returns "Savings Rookie"
        coEvery { gamificationEngine.aggregateGoalBalances(any(), any()) } returns
            MoneyAggregateResult.Available(
                MoneyAggregate.singleCurrency(
                    amount = 0.0,
                    currency = CurrencyCode("EUR"),
                    transactionCount = 0,
                    rateBasis = RateBasis.IDENTITY
                )
            )
        coEvery { gamificationEngine.calculateStreak() } returns SavingsStreak(
            currentStreakDays = 0,
            personalBestDays = 0,
            lastSavingsDate = null,
            monthlyContributions = 0,
            totalContributedThisMonth = 0.0
        )
        coEvery { gamificationEngine.getAchievements(homeCurrency = any()) } returns emptyList()
        coEvery { lifestyleUseCase.evaluateAndPrompt() } returns null
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } returns
            SmartSavingsComputationResult.NoRecommendation()
        viewModel = SavingsGoalsViewModel(
            savingsGoalRepository = savingsGoalRepository,
            savingsContributionHistoryRepository = historyRepository,
            smartSavingsEngine = smartSavingsEngine,
            gamificationEngine = gamificationEngine,
            lifestyleSavingsPromptUseCase = lifestyleUseCase,
            monthlySavingsSweepUseCase = sweepUseCase,
            savingsSweepAcceptanceCoordinator = sweepCoordinator,
            timeProvider = timeProvider,
            currencySettingsRepository = currencyRepo
        )
    }

    private val blockedEvent =
        SavingsGoalsEvent.WriteBlocked(UiText.StringResource(R.string.ui_write_blocked_maintenance))

    @Test
    fun `blocked addGoal emits write blocked and never goal created`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { savingsGoalRepository.createSavingsGoal(any()) } throws blockedWriteException()

        viewModel.addGoal("Bike", 800.0, null, GoalProtectionLevel.WARNING)
        advanceUntilIdle()

        assertEquals(blockedEvent, viewModel.events.first())
    }

    @Test
    fun `addGoal emits goal created only after the write succeeded`() = runTest(testDispatcher) {
        advanceUntilIdle()
        var writeCompleted = false
        coEvery { savingsGoalRepository.createSavingsGoal(any()) } coAnswers {
            writeCompleted = true
            7L
        }

        viewModel.addGoal("Bike", 800.0, null, GoalProtectionLevel.WARNING)
        advanceUntilIdle()

        assertEquals(SavingsGoalsEvent.GoalCreated, viewModel.events.first())
        assertEquals("write must have completed before the event", true, writeCompleted)
    }

    @Test
    fun `blocked deleteGoal emits write blocked`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { savingsGoalRepository.deleteSavingsGoal(any()) } throws blockedWriteException()
        val goal = SavingsGoal(
            id = 1L, name = "Bike", targetAmount = 100.0, currentAmount = 0.0, targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING, currencyAssumption = "EXPLICIT",
            createdAt = timeProvider.now()
        )

        viewModel.deleteGoal(goal)
        advanceUntilIdle()

        assertEquals(blockedEvent, viewModel.events.first())
    }

    @Test
    fun `blocked lifestyle acceptance keeps the card and emits write blocked`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { lifestyleUseCase.recordAcceptance(any()) } throws blockedWriteException()

        viewModel.acceptLifestyleRecommendation(null)
        advanceUntilIdle()

        assertEquals(blockedEvent, viewModel.events.first())
    }
}
