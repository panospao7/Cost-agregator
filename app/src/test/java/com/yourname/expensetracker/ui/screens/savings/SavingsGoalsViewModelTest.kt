package com.yourname.expensetracker.ui.screens.savings

import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.RecommendationSource
import com.yourname.expensetracker.domain.savings.SavingsAchievement
import com.yourname.expensetracker.domain.savings.SavingsGamificationEngine
import com.yourname.expensetracker.domain.savings.SavingsRecommendation
import com.yourname.expensetracker.domain.savings.SavingsStreak
import com.yourname.expensetracker.domain.savings.SmartSavingsEngine
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelTest : ViewModelTestUtils() {

    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val smartSavingsEngine = mockk<SmartSavingsEngine>(relaxed = true)
    private val gamificationEngine = mockk<SavingsGamificationEngine>(relaxed = true)
    private val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)

    private lateinit var goalsFlow: MutableStateFlow<List<SavingsGoal>>
    private lateinit var viewModel: SavingsGoalsViewModel

    @Before
    override fun setup() {
        super.setup()

        every { monthlySavingsSweepUseCase.shouldShowSweepPrompt() } returns false
        every { gamificationEngine.calculateLevel(any()) } answers {
            val totalSaved = invocation.args[0] as Double
            ((totalSaved / 500.0).toInt() + 1)
        }
        every { gamificationEngine.getLevelTitle(any()) } returns "Savings Rookie"

        coEvery { gamificationEngine.calculateStreak() } returns SavingsStreak(
            currentStreakDays = 0,
            personalBestDays = 0,
            lastSavingsDate = null,
            monthlyContributions = 0,
            totalContributedThisMonth = 0.0
        )
        coEvery { gamificationEngine.getAchievements() } returns emptyList<SavingsAchievement>()
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        coEvery { smartSavingsEngine.calculateSafeToSaveAmount(any(), any()) } returns SavingsRecommendation(
            safeAmount = 0.0,
            confidence = 0.5,
            impact = "No recommendation",
            source = RecommendationSource.BUDGET_SURPLUS
        )

        configureRepositoryWithGoals(emptyList())
        viewModel = createViewModel()
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
        assertEquals(300.0, state.totalSaved, 0.0001)
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
        assertEquals(250.0, state.totalSaved, 0.0001)
    }

    @Test
    fun `empty state when no goals`() = runTest(testDispatcher) {
        configureRepositoryWithGoals(emptyList())
        viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.goals.isEmpty())
        assertEquals(0.0, state.totalSaved, 0.0001)
        assertFalse(state.isLoading)
    }

    private fun configureRepositoryWithGoals(initialGoals: List<SavingsGoal>) {
        goalsFlow = MutableStateFlow(initialGoals)

        every { savingsGoalRepository.getAllGoals() } returns goalsFlow

        coEvery { savingsGoalRepository.addGoal(any()) } coAnswers {
            val incoming = invocation.args[0] as SavingsGoal
            val nextId = (goalsFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
            goalsFlow.value = goalsFlow.value + incoming.copy(id = nextId)
            nextId
        }

        coEvery { savingsGoalRepository.updateGoalAmount(any(), any()) } coAnswers {
            val goalId = invocation.args[0] as Long
            val amount = invocation.args[1] as Double
            goalsFlow.value = goalsFlow.value.map { goal ->
                if (goal.id == goalId) goal.copy(currentAmount = amount) else goal
            }
            Unit
        }
    }

    private fun createViewModel(): SavingsGoalsViewModel {
        return SavingsGoalsViewModel(
            savingsGoalRepository = savingsGoalRepository,
            smartSavingsEngine = smartSavingsEngine,
            gamificationEngine = gamificationEngine,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase
        )
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
            protectionLevel = GoalProtectionLevel.WARNING
        )
    }
}
