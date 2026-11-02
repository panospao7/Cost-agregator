package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceCoordinator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.savings.GoalSavingsRecommendation
import com.yourname.expensetracker.domain.savings.RecommendationSource
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_BALANCE
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_HISTORY
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_UNAVAILABLE
import com.yourname.expensetracker.domain.savings.SavingsAchievement
import com.yourname.expensetracker.domain.savings.SavingsGamificationEngine
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteEligibility
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteResult
import com.yourname.expensetracker.domain.savings.SavingsRecommendation
import com.yourname.expensetracker.domain.savings.SavingsStreak
import com.yourname.expensetracker.domain.savings.SmartSavingsComputationResult
import com.yourname.expensetracker.domain.savings.SmartSavingsEngine
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * savings-goal-delete S3: ViewModel half of the delete flow. The repository (eligibility and
 * delete) is a mock because the emptiness rule is owned by the repository and policy (INV-16);
 * these tests pin only how the ViewModel maps bounded results to state and one-shot events.
 * All data is synthetic. Nothing here waits on real time: every wait is a virtual advanceUntilIdle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelDeleteTest : ViewModelTestUtils() {

    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)
    private val historyRepository = mockk<SavingsContributionHistoryRepository>(relaxed = true)
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
    private val events = CopyOnWriteArrayList<SavingsGoalsEvent>()
    private val logLines = CopyOnWriteArrayList<String>()

    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logLines += listOfNotNull(tag, message, t?.message, t?.stackTraceToString()).joinToString(" ")
        }
    }

    // Synthetic fixtures.
    private val goalA = createGoal(1L, GOAL_A_NAME, 1000.0, FUNDED_AMOUNT)
    private val goalB = createGoal(2L, GOAL_B_NAME, 500.0, 0.0)

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(logTree)
        every { monthlySavingsSweepUseCase.shouldShowSweepPrompt() } returns false
        every { gamificationEngine.calculateLevel(any()) } answers {
            val totalSaved = invocation.args[0] as Double
            ((totalSaved / 500.0).toInt() + 1)
        }
        every { gamificationEngine.getLevelTitle(any()) } returns "Savings Rookie"
        coEvery { gamificationEngine.aggregateGoalBalances(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
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
        goalsFlow = MutableStateFlow(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns goalsFlow
    }

    @After
    override fun tearDown() {
        Timber.uproot(logTree)
        super.tearDown()
    }

    // ------------------------------------------------------------------ pre-check (AE-20)

    @Test
    fun `AE-20 funded goal with balance blocks into NotAllowed with no event and no delete`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(1L) } returns
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE)

        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.NotAllowed(1L), viewModel.state.value.deleteFlow)
        assertTrue("NotAllowed must not emit an event", events.isEmpty())
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
    }

    @Test
    fun `AE-20 goal with history only also blocks into NotAllowed with no event`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.NotAllowed(2L), viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
    }

    @Test
    fun `AE-20 empty goal is Deletable and shows Confirm carrying the goal name without any write`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Deletable

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Confirm(2L, GOAL_B_NAME), viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
        assertEquals(listOf(goalA, goalB), viewModel.state.value.goals)
    }

    @Test
    fun `AE-20 requestDeleteGoal enters Checking while the read is in flight`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } coAnswers { gate.await() }

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(2L), viewModel.state.value.deleteFlow)

        gate.complete(SavingsGoalDeleteEligibility.Deletable)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Confirm(2L, GOAL_B_NAME), viewModel.state.value.deleteFlow)
    }

    @Test
    fun `pre-check HISTORY_UNAVAILABLE emits bounded rejection and returns to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.HISTORY_UNAVAILABLE)), events.toList())
    }

    @Test
    fun `pre-check UNAVAILABLE maps to GENERIC rejection and returns to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC)), events.toList())
    }

    @Test
    fun `pre-check with an unknown rejection code fails closed to GENERIC`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Rejected("SOME_FUTURE_CODE")

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC)), events.toList())
    }

    @Test
    fun `pre-check exception becomes GENERIC rejection and nothing escapes the scope`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } throws IllegalStateException(SECRET)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC)), events.toList())
    }

    @Test
    fun `pre-check AlreadyAbsent takes the Deleted path with GoalDeleted and Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.AlreadyAbsent

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
    }

    @Test
    fun `pre-check Deletable for a goal no longer listed returns to Idle with no event`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(99L) } returns
            SavingsGoalDeleteEligibility.Deletable

        viewModel.requestDeleteGoal(99L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue("nothing was deleted, so no GoalDeleted or DeleteRejected", events.isEmpty())
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
    }

    @Test
    fun `late pre-check exception of a dismissed check does not disturb a newer flow`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gateA = CompletableDeferred<SavingsGoalDeleteEligibility>()
        val gateB = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(1L) } coAnswers { gateA.await() }
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } coAnswers { gateB.await() }
        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()
        viewModel.dismissDeleteFlow()
        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(2L), viewModel.state.value.deleteFlow)

        gateA.completeExceptionally(IllegalStateException(SECRET))
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Checking(2L), viewModel.state.value.deleteFlow)
        assertTrue("a stale failure must not emit an event", events.isEmpty())

        gateB.complete(SavingsGoalDeleteEligibility.Deletable)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Confirm(2L, GOAL_B_NAME), viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `dismissing during Checking makes a late Deletable result a no-op`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } coAnswers { gate.await() }
        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(2L), viewModel.state.value.deleteFlow)

        viewModel.dismissDeleteFlow()
        gate.complete(SavingsGoalDeleteEligibility.Deletable)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `dismissing during Checking makes a late rejection or absent result a no-op`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } coAnswers { gate.await() }
        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        viewModel.dismissDeleteFlow()
        gate.complete(SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE))
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue("a dismissed flow must not surface a late message", events.isEmpty())
    }

    @Test
    fun `dismissDeleteFlow closes Confirm and NotAllowed back to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Deletable
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(1L) } returns
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.deleteFlow is DeleteFlowState.Confirm)
        viewModel.dismissDeleteFlow()
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)

        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.deleteFlow is DeleteFlowState.NotAllowed)
        viewModel.dismissDeleteFlow()
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `requestDeleteGoal twice runs one pre-check and a second request while Confirm is ignored`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } returns
            SavingsGoalDeleteEligibility.Deletable

        viewModel.requestDeleteGoal(2L)
        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Confirm(2L, GOAL_B_NAME), viewModel.state.value.deleteFlow)
        coVerify(exactly = 1) { savingsGoalRepository.getSavingsGoalDeletionEligibility(any()) }
    }

    // ------------------------------------------------------------------ confirm path

    @Test
    fun `confirmed delete of an empty goal emits GoalDeleted once and returns to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
        coVerify(exactly = 1) { savingsGoalRepository.deleteSavingsGoal(goalB) }
    }

    @Test
    fun `confirmed delete reporting AlreadyAbsent is success without an error`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returns SavingsGoalDeleteResult.AlreadyAbsent
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
    }

    @Test
    fun `confirmed delete rejected with HAS_BALANCE maps to NOT_EMPTY and returns to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returns
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE)
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.NOT_EMPTY)), events.toList())
    }

    @Test
    fun `confirmed delete rejected with HAS_HISTORY maps to NOT_EMPTY and returns to Idle`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returns
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY)
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.NOT_EMPTY)), events.toList())
    }

    @Test
    fun `confirmed delete rejected with HISTORY_UNAVAILABLE maps to its own reason`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returns
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE)
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.HISTORY_UNAVAILABLE)), events.toList())
    }

    @Test
    fun `confirmed delete rejected with UNAVAILABLE or an unknown code maps to GENERIC`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } returnsMany listOf(
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE),
            SavingsGoalDeleteResult.Rejected("SOME_FUTURE_CODE")
        )

        confirmFor(goalB)
        viewModel.deleteGoal(goalB)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)

        confirmFor(goalB)
        viewModel.deleteGoal(goalB)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)

        val generic = SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC)
        assertEquals(listOf<SavingsGoalsEvent>(generic, generic), events.toList())
    }

    @Test
    fun `AE-15 delete exception becomes GENERIC and the secret text never reaches state events or logs`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } throws IllegalStateException(SECRET)
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC)), events.toList())
        assertNoSecretOrGoalDataLeaked()
    }

    @Test
    fun `AE-15 pre-check exception does not leak the secret text into state events or logs`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(any()) } throws IllegalStateException(SECRET)

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertNoSecretOrGoalDataLeaked()
    }

    @Test
    fun `INV-14 blocked delete logs no goal name id or amount and emits only WriteBlocked`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(any()) } throws blockedWriteException()
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertEquals(
            listOf<SavingsGoalsEvent>(SavingsGoalsEvent.WriteBlocked(UiText.StringResource(R.string.ui_write_blocked_maintenance))),
            events.toList()
        )
        assertNoSecretOrGoalDataLeaked()
    }

    // ------------------------------------------------------------------ double tap and Deleting

    @Test
    fun `second deleteGoal while Deleting is ignored and only one delete and one event happen`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteResult>()
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } coAnswers { gate.await() }
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        viewModel.deleteGoal(goalB)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Deleting(2L), viewModel.state.value.deleteFlow)

        gate.complete(SavingsGoalDeleteResult.Deleted)
        advanceUntilIdle()

        coVerify(exactly = 1) { savingsGoalRepository.deleteSavingsGoal(any()) }
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
    }

    @Test
    fun `dismissDeleteFlow is ignored while Deleting and request is ignored too`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteResult>()
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } coAnswers { gate.await() }
        confirmFor(goalB)
        viewModel.deleteGoal(goalB)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Deleting(2L), viewModel.state.value.deleteFlow)

        viewModel.dismissDeleteFlow()
        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Deleting(2L), viewModel.state.value.deleteFlow)
        coVerify(exactly = 1) { savingsGoalRepository.getSavingsGoalDeletionEligibility(any()) }

        gate.complete(SavingsGoalDeleteResult.Deleted)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
    }

    @Test
    fun `deleteGoal for a different goal than the Confirm one is ignored`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(any()) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalB)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Confirm(2L, GOAL_B_NAME), viewModel.state.value.deleteFlow)
        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `deleteGoal while NotAllowed or Checking is ignored`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(any()) } returns SavingsGoalDeleteResult.Deleted
        val gate = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(1L) } coAnswers { gate.await() }

        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(1L), viewModel.state.value.deleteFlow)
        viewModel.deleteGoal(goalA)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(1L), viewModel.state.value.deleteFlow)

        gate.complete(SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE))
        advanceUntilIdle()
        assertEquals(DeleteFlowState.NotAllowed(1L), viewModel.state.value.deleteFlow)
        viewModel.deleteGoal(goalA)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.NotAllowed(1L), viewModel.state.value.deleteFlow)

        coVerify(exactly = 0) { savingsGoalRepository.deleteSavingsGoal(any()) }
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------ cancellation (AE-14 VM half)

    @Test
    fun `AE-14 cancellation thrown by the delete returns to Idle with no message`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } throws
            kotlinx.coroutines.CancellationException("cancelled")
        confirmFor(goalB)

        viewModel.deleteGoal(goalB)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue("cancellation must not become a message", events.isEmpty())
    }

    @Test
    fun `AE-14 cancellation thrown by the pre-check returns to Idle with no message`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } throws
            kotlinx.coroutines.CancellationException("cancelled")

        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `AE-14 cancelling the scope during an in-flight delete returns to Idle and never reports GoalDeleted`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteResult>()
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalB) } coAnswers { gate.await() }
        confirmFor(goalB)
        viewModel.deleteGoal(goalB)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Deleting(2L), viewModel.state.value.deleteFlow)

        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `AE-14 cancelling the scope during an in-flight pre-check returns to Idle with no message`() = runTest(testDispatcher) {
        startViewModel(listOf(goalA, goalB))
        val gate = CompletableDeferred<SavingsGoalDeleteEligibility>()
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(2L) } coAnswers { gate.await() }
        viewModel.requestDeleteGoal(2L)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Checking(2L), viewModel.state.value.deleteFlow)

        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        advanceUntilIdle()

        assertEquals(DeleteFlowState.Idle, viewModel.state.value.deleteFlow)
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------ INV-11 (AE-13)

    @Test
    fun `AE-13 successful delete clears matching selection and every recommendation naming the goal`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        stubSweepNaming(goalA.id, goalB.id)
        startViewModel(listOf(goalA, goalB))
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.selectGoal(goalA)
        assertEquals(setOf(1L, 2L), viewModel.state.value.smartRecommendations.map { it.goal.id }.toSet())
        assertNotNull(viewModel.state.value.sweepRecommendation)
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.selectedGoal)
        assertEquals(listOf(2L), state.smartRecommendations.map { it.goal.id })
        assertNull("a sweep naming the deleted goal must go", state.sweepRecommendation)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
    }

    @Test
    fun `AE-13 deleting goal 1 while goal 2 is selected keeps goal 2 selected and other recommendations`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        stubSweepNaming(goalB.id)
        startViewModel(listOf(goalA, goalB))
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.selectGoal(goalB)
        val sweepBefore = viewModel.state.value.sweepRecommendation
        assertNotNull(sweepBefore)
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(goalB, state.selectedGoal)
        assertEquals(listOf(2L), state.smartRecommendations.map { it.goal.id })
        assertEquals("a sweep that does not name the goal must stay", sweepBefore, state.sweepRecommendation)
    }

    @Test
    fun `AE-13 a sweep allocating to the deleted goal among others is dropped`() = runTest(testDispatcher) {
        stubSweepNaming(goalB.id, goalA.id)
        startViewModel(listOf(goalA, goalB))
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.sweepRecommendation)
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        assertNull(viewModel.state.value.sweepRecommendation)
    }

    @Test
    fun `AE-13 AlreadyAbsent also clears every VM-held reference to the goal`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        stubSweepNaming(goalA.id)
        startViewModel(listOf(goalA, goalB))
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.selectGoal(goalA)
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns SavingsGoalDeleteResult.AlreadyAbsent
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.selectedGoal)
        assertEquals(listOf(2L), state.smartRecommendations.map { it.goal.id })
        assertNull(state.sweepRecommendation)
        assertEquals(listOf<SavingsGoalsEvent>(SavingsGoalsEvent.GoalDeleted), events.toList())
    }

    @Test
    fun `AE-13 pre-check AlreadyAbsent also clears selection and recommendations`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        startViewModel(listOf(goalA, goalB))
        viewModel.selectGoal(goalA)
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(1L) } returns
            SavingsGoalDeleteEligibility.AlreadyAbsent

        viewModel.requestDeleteGoal(1L)
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedGoal)
        assertEquals(listOf(2L), viewModel.state.value.smartRecommendations.map { it.goal.id })
    }

    @Test
    fun `AE-13 control - a rejected delete leaves selection and recommendations untouched`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        stubSweepNaming(goalA.id)
        startViewModel(listOf(goalA, goalB))
        viewModel.computeSweepRecommendation()
        advanceUntilIdle()
        viewModel.selectGoal(goalA)
        val before = viewModel.state.value
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE)
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        advanceUntilIdle()

        val after = viewModel.state.value
        assertEquals(goalA, after.selectedGoal)
        assertEquals(before.smartRecommendations, after.smartRecommendations)
        assertEquals(before.sweepRecommendation, after.sweepRecommendation)
        assertEquals(before.goals, after.goals)
    }

    @Test
    fun `AE-13 after the goals flow re-emits the list lacks the deleted goal and nothing names it`() = runTest(testDispatcher) {
        stubRecommendationsForEveryGoal()
        startViewModel(listOf(goalA, goalB))
        viewModel.selectGoal(goalA)
        coEvery { savingsGoalRepository.deleteSavingsGoal(goalA) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(goalA)

        viewModel.deleteGoal(goalA)
        goalsFlow.value = listOf(goalB)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(goalB), state.goals)
        assertNull(state.selectedGoal)
        assertEquals(listOf(2L), state.smartRecommendations.map { it.goal.id })
    }

    // ------------------------------------------------------------------ AE-16 / AE-21

    @Test
    fun `AE-16 deleting the empty goal leaves total saved and level equal and the other goal bit identical`() = runTest(testDispatcher) {
        val empty = createGoal(3L, "Test Goal C", 200.0, 0.0)
        val funded = createGoal(4L, "Test Goal D", 400.0, 30.0)
        startViewModel(listOf(empty, funded))
        val before = viewModel.state.value
        assertEquals(30.0, requireNotNull(before.totalSaved), 0.0)
        coEvery { savingsGoalRepository.deleteSavingsGoal(empty) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(empty)

        viewModel.deleteGoal(empty)
        goalsFlow.value = listOf(funded)
        advanceUntilIdle()

        val after = viewModel.state.value
        assertEquals(before.totalSaved, after.totalSaved)
        assertEquals(before.userLevel, after.userLevel)
        assertNotNull(after.userLevel)
        assertEquals(listOf(funded), after.goals)
        assertEquals(30.0, after.goals.single().currentAmount, 0.0)
    }

    @Test
    fun `AE-21 deleting the last goal shows the empty state with real zero header values not Unavailable`() = runTest(testDispatcher) {
        val only = createGoal(5L, "Test Goal E", 300.0, 0.0)
        startViewModel(listOf(only))
        coEvery { savingsGoalRepository.deleteSavingsGoal(only) } returns SavingsGoalDeleteResult.Deleted
        confirmFor(only)

        viewModel.deleteGoal(only)
        goalsFlow.value = emptyList()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.goals.isEmpty())
        assertTrue(state.loadableState is com.yourname.expensetracker.ui.model.LoadableUiState.Empty)
        assertFalse(state.isLoading)
        assertEquals(0.0, requireNotNull(state.totalSaved), 0.0)
        assertEquals(1, state.userLevel)
        assertNotEquals("Unavailable", state.levelTitle)
        assertEquals("Savings Rookie", state.levelTitle)
        assertNotNull(state.moneyTotalSaved)
        assertEquals(DeleteFlowState.Idle, state.deleteFlow)
        coVerify { gamificationEngine.calculateLevel(0.0) }
    }

    // ------------------------------------------------------------------ helpers

    private fun TestScope.startViewModel(initialGoals: List<SavingsGoal>) {
        goalsFlow.value = initialGoals
        every { currencyRepo.homeCurrency() } returns currencyFlow
        every { currencyRepo.lastRateUpdate() } returns rateFlow
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        viewModel = SavingsGoalsViewModel(
            savingsGoalRepository = savingsGoalRepository,
            savingsContributionHistoryRepository = historyRepository,
            smartSavingsEngine = smartSavingsEngine,
            gamificationEngine = gamificationEngine,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            savingsSweepAcceptanceCoordinator = savingsSweepAcceptanceCoordinator,
            timeProvider = timeProvider,
            currencySettingsRepository = currencyRepo
        )
        backgroundScope.launch(testDispatcher) { viewModel.events.collect { events += it } }
        advanceUntilIdle()
    }

    /** Drives the real request path to Confirm for [goal] so deleteGoal runs from the legal state. */
    private fun TestScope.confirmFor(goal: SavingsGoal) {
        coEvery { savingsGoalRepository.getSavingsGoalDeletionEligibility(goal.id) } returns
            SavingsGoalDeleteEligibility.Deletable
        viewModel.requestDeleteGoal(goal.id)
        advanceUntilIdle()
        assertEquals(DeleteFlowState.Confirm(goal.id, goal.name), viewModel.state.value.deleteFlow)
    }

    private fun stubRecommendationsForEveryGoal() {
        coEvery { smartSavingsEngine.calculatePortfolioRecommendationsResult(any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val goals = invocation.args[0] as List<SavingsGoal>
            SmartSavingsComputationResult.Available(
                goals.map {
                    GoalSavingsRecommendation(
                        it,
                        SavingsRecommendation(25.0, 0.9, "TEST", RecommendationSource.BUDGET_SURPLUS, "EUR")
                    )
                }
            )
        }
    }

    private fun stubSweepNaming(vararg goalIds: Long) {
        val allocations = goalIds.map { id ->
            val goal = listOf(goalA, goalB).first { it.id == id }
            GoalAllocation(goal.id, goal.name, goal.currentAmount, goal.targetAmount, 20.0, 1.0, "EUR")
        }
        val sweep = SavingsSweepRecommendation(
            50.0, 10.0, 40.0, allocations, timeProvider.now(), timeProvider.now() + 1000L, 0.9, "EUR", 0L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.Available(sweep)
    }

    private fun assertNoSecretOrGoalDataLeaked() {
        assertFalse(events.toString().contains(SECRET))
        assertFalse(viewModel.state.value.deleteFlow.toString().contains(SECRET))
        assertFalse(viewModel.state.value.toString().contains(SECRET))
        for (line in logLines) {
            assertFalse("log leaked secret text: $line", line.contains(SECRET))
            assertFalse("log leaked a goal name: $line", line.contains(GOAL_A_NAME) || line.contains(GOAL_B_NAME))
            assertFalse("log leaked an amount: $line", line.contains(FUNDED_AMOUNT.toString()))
        }
        for (event in events) {
            val rendered = event.toString()
            assertFalse(rendered.contains(GOAL_A_NAME) || rendered.contains(GOAL_B_NAME))
        }
    }

    private fun createGoal(id: Long, name: String, targetAmount: Double, currentAmount: Double): SavingsGoal =
        SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            currencyAssumption = "EXPLICIT",
            createdAt = timeProvider.now()
        )

    private companion object {
        const val SECRET = "secret text"
        const val GOAL_A_NAME = "Test Goal A"
        const val GOAL_B_NAME = "Test Goal B"
        const val FUNDED_AMOUNT = 42.5
    }
}
