package com.yourname.expensetracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepMonthAcceptanceDao
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import com.yourname.expensetracker.data.database.entity.SavingsGoal as SavingsGoalEntity
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsSweepAcceptanceCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var coordinator: SavingsSweepAcceptanceCoordinator
    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        every { writeBarrier.checkWritesAllowed(any<String>()) } just runs
        coordinator = SavingsSweepAcceptanceCoordinator(
            database = database,
            savingsGoalDao = database.savingsGoalDao(),
            savingsSweepPlanDao = database.savingsSweepPlanDao(),
            contributionLedgerDao = database.savingsContributionLedgerDao(),
            monthAcceptanceDao = database.savingsSweepMonthAcceptanceDao(),
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicate_retry_is_idempotent_and_does_not_increment_again() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val recommendation = recommendation(
            allocation(1L, 200.0, 40.0)
        )

        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation))
        assertEquals(SavingsSweepAcceptanceResult.AlreadyAccepted, coordinator.accept(recommendation))
        assertEquals(240.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        assertEquals(1, database.savingsSweepPlanDao().getPlansForMonth(recommendation.monthEnd).size)
        assertEquals(1, database.savingsSweepMonthAcceptanceDao().getAll().size)
    }

    @Test
    fun deleting_all_accepted_goals_does_not_reopen_the_same_month_sweep() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val first = recommendation(allocation(1L, 200.0, 40.0))
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(first))
        database.savingsGoalDao().deleteGoal(database.savingsGoalDao().getById(1L)!!)
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getPlansForMonth(first.monthEnd).isEmpty())
        assertEquals(1, database.savingsSweepMonthAcceptanceDao().getAll().size)

        database.savingsGoalDao().insertGoal(goal(2L, 200.0))
        val recomputed = recommendation(allocation(2L, 200.0, 40.0)).copy(computedAt = first.computedAt + 1L)
        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED"),
            coordinator.accept(recomputed)
        )
        assertEquals(200.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        timeProvider.setTime(TimePeriodUtils.getEndOfMonth(timeProvider.now()) + 1_000L)
        assertEquals(SavingsSweepAcceptanceResult.Accepted,
            coordinator.accept(recommendation(allocation(2L, 200.0, 40.0))))
        assertEquals(2, database.savingsSweepMonthAcceptanceDao().getAll().size)
    }

    @Test
    fun stale_second_allocation_rolls_back_the_entire_multi_goal_acceptance() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsGoalDao().insertGoal(goal(2L, 100.0))
        val recommendation = recommendation(
            allocation(1L, 200.0, 20.0),
            allocation(2L, 999.0, 20.0)
        )

        val result = coordinator.accept(recommendation)

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_SNAPSHOT_MISMATCH"),
            result
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(100.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getPlansForMonth(recommendation.monthEnd).isEmpty())
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
    }

    @Test
    fun cancellation_from_time_provider_is_rethrown() = runTest {
        val cancellingTimeProvider = mockk<TimeProvider>()
        every { cancellingTimeProvider.now() } throws CancellationException("cancelled")
        val cancellingCoordinator = SavingsSweepAcceptanceCoordinator(
            database = database,
            savingsGoalDao = database.savingsGoalDao(),
            savingsSweepPlanDao = database.savingsSweepPlanDao(),
            contributionLedgerDao = database.savingsContributionLedgerDao(),
            monthAcceptanceDao = database.savingsSweepMonthAcceptanceDao(),
            writeBarrier = writeBarrier,
            timeProvider = cancellingTimeProvider
        )

        try {
            cancellingCoordinator.accept(recommendation(allocation(1L, 200.0, 20.0)))
            throw AssertionError("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }

    @Test
    fun successful_multi_goal_acceptance_commits_every_balance_plan_and_ledger_row() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsGoalDao().insertGoal(goal(2L, 100.0))
        val recommendation = recommendation(
            allocation(1L, 200.0, 20.0),
            allocation(2L, 100.0, 30.0)
        )

        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation))
        assertEquals(220.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(130.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertEquals(2, database.savingsContributionLedgerDao().getAll().size)
        assertEquals(
            listOf(SweepPlanStatus.ACCEPTED, SweepPlanStatus.ACCEPTED),
            database.savingsSweepPlanDao().getPlansForMonth(recommendation.monthEnd).map { it.status }
        )
    }

    @Test
    fun ledger_write_failure_rolls_back_prior_plan_and_balance_mutations() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val failingCoordinator = newCoordinator(ledgerDao(insertResult = -1L))

        val result = failingCoordinator.accept(recommendation(allocation(1L, 200.0, 20.0)))

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_IDEMPOTENCY_CONFLICT"),
            result
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun cancellation_after_balance_and_plan_writes_is_rethrown_and_rolls_back() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        // Room crosses coroutine contexts. An extra field prevents stack-trace
        // recovery from copying this fixture, so identity remains observable.
        val cancellation = InjectedCancellation("ledger insert")
        val cancellingCoordinator = newCoordinator(ledgerDao(insertError = cancellation))

        try {
            cancellingCoordinator.accept(recommendation(allocation(1L, 200.0, 20.0)))
            throw AssertionError("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }

        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun unknown_goal_provenance_is_rejected_even_when_currency_label_matches() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0, currencyAssumption = "LEGACY_DEFAULT"))

        val result = coordinator.accept(recommendation(allocation(1L, 200.0, 20.0)))

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_SNAPSHOT_MISMATCH"),
            result
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun changed_goal_currency_is_rejected_before_any_write() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0).copy(currency = "USD"))

        val result = coordinator.accept(recommendation(allocation(1L, 200.0, 20.0)))

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_SNAPSHOT_MISMATCH"),
            result
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun committed_acceptance_survives_database_reopen_and_retry() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "savings-sweep-reopen.db"
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            database.savingsGoalDao().insertGoal(goal(1L, 200.0))
            val recommendation = recommendation(allocation(1L, 200.0, 40.0))
            assertEquals(
                SavingsSweepAcceptanceResult.Accepted,
                newCoordinator(database.savingsContributionLedgerDao()).accept(recommendation)
            )
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .allowMainThreadQueries().build()

            assertEquals(
                SavingsSweepAcceptanceResult.AlreadyAccepted,
                newCoordinator(database.savingsContributionLedgerDao()).accept(recommendation)
            )
            assertEquals(240.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
            assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun marker_insert_conflict_rolls_back_all_writes_and_does_not_burn_month() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val markerDao = mockk<SavingsSweepMonthAcceptanceDao>()
        coEvery { markerDao.hasAcceptanceBetween(any(), any()) } returns false
        coEvery { markerDao.insert(any()) } returns -1L
        val candidate = recommendation(allocation(1L, 200.0, 40.0))
        assertEquals(SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_IDEMPOTENCY_CONFLICT"),
            newCoordinator(database.savingsContributionLedgerDao(), markerDao).accept(candidate))
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(candidate))
    }

    @Test
    fun cancellation_after_marker_insert_rolls_back_marker_and_every_goal_write() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val realDao = database.savingsSweepMonthAcceptanceDao()
        val markerDao = object : SavingsSweepMonthAcceptanceDao by realDao {
            override suspend fun insert(acceptance: SavingsSweepMonthAcceptance): Long {
                realDao.insert(acceptance)
                throw InjectedCancellation("month_marker")
            }
        }
        val candidate = recommendation(allocation(1L, 200.0, 40.0))
        try {
            newCoordinator(database.savingsContributionLedgerDao(), markerDao).accept(candidate)
            throw AssertionError("Cancellation was swallowed")
        } catch (error: InjectedCancellation) {
            assertEquals("month_marker", error.checkpoint)
        }
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(realDao.getAll().isEmpty())
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(candidate))
    }

    private fun newCoordinator(
        ledgerDao: SavingsContributionLedgerDao,
        markerDao: SavingsSweepMonthAcceptanceDao = database.savingsSweepMonthAcceptanceDao()
    ) =
        SavingsSweepAcceptanceCoordinator(
            database,
            database.savingsGoalDao(),
            database.savingsSweepPlanDao(),
            ledgerDao,
            markerDao,
            writeBarrier,
            timeProvider
        )

    @Test
    fun retry_after_optional_plan_cleanup_remains_idempotent() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val recommendation = recommendation(allocation(1L, 200.0, 40.0))
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation))
        database.savingsSweepPlanDao().deleteOldPlans(recommendation.computedAt + 1L)

        assertEquals(SavingsSweepAcceptanceResult.AlreadyAccepted, coordinator.accept(recommendation))
        assertEquals(240.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
    }

    @Test
    fun malformed_recommendations_are_rejected_without_mutations() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val valid = recommendation(allocation(1L, 200.0, 20.0))
        val malformed = listOf(
            valid.copy(safeSweepAmount = Double.NaN),
            valid.copy(confidence = Double.POSITIVE_INFINITY),
            valid.copy(computedAt = 0L),
            valid.copy(goalAllocations = listOf(valid.goalAllocations.single(), valid.goalAllocations.single())),
            valid.copy(goalAllocations = listOf(valid.goalAllocations.single().copy(targetAmount = Double.NaN)))
        )
        malformed.forEach { candidate ->
            assertEquals(
                SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_INVALID_RECOMMENDATION"),
                coordinator.accept(candidate)
            )
        }
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun allocations_above_safe_sweep_are_rejected_without_mutations() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsGoalDao().insertGoal(goal(2L, 100.0))
        val candidate = recommendation(
            allocation(1L, 200.0, 30.0),
            allocation(2L, 100.0, 30.0)
        ).copy(safeSweepAmount = 50.0)

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_INVALID_RECOMMENDATION"),
            coordinator.accept(candidate)
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(100.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun allocation_sum_overflow_is_rejected_without_mutations() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 0.0).copy(targetAmount = Double.MAX_VALUE))
        database.savingsGoalDao().insertGoal(goal(2L, 0.0).copy(targetAmount = Double.MAX_VALUE))
        val candidate = recommendation(
            allocation(1L, 0.0, Double.MAX_VALUE).copy(targetAmount = Double.MAX_VALUE),
            allocation(2L, 0.0, Double.MAX_VALUE).copy(targetAmount = Double.MAX_VALUE)
        ).copy(safeSweepAmount = Double.MAX_VALUE)

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_INVALID_RECOMMENDATION"),
            coordinator.accept(candidate)
        )
        assertEquals(0.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(0.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun allocation_above_goal_target_rolls_back_other_allocations() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsGoalDao().insertGoal(goal(2L, 990.0))
        val candidate = recommendation(
            allocation(1L, 200.0, 20.0),
            allocation(2L, 990.0, 20.0)
        )

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_SNAPSHOT_MISMATCH"),
            coordinator.accept(candidate)
        )
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(990.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun residual_safe_amount_and_exact_goal_completion_are_allowed() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 990.0))
        val candidate = recommendation(allocation(1L, 990.0, 10.0))
            .copy(safeSweepAmount = 50.0)

        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(candidate))
        assertEquals(1_000.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(10.0, database.savingsContributionLedgerDao().getAll().single().amount, 0.0)
        assertEquals(SweepPlanStatus.ACCEPTED, database.savingsSweepPlanDao().getAllPlans().single().status)
    }

    @Test
    fun double_roundoff_is_allowed_but_larger_safe_amount_excess_is_rejected() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsGoalDao().insertGoal(goal(2L, 100.0))
        val candidate = recommendation(
            allocation(1L, 200.0, 0.1),
            allocation(2L, 100.0, 0.1 + 0.2)
        ).copy(safeSweepAmount = 0.4)
        val oversized = candidate.copy(
            goalAllocations = listOf(
                candidate.goalAllocations[0],
                candidate.goalAllocations[1].copy(suggestedAllocation = 0.30000000000001)
            )
        )

        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_INVALID_RECOMMENDATION"),
            coordinator.accept(oversized)
        )
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(candidate))
        assertEquals(200.1, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(100.3, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
        assertEquals(2, database.savingsContributionLedgerDao().getAll().size)
    }

    @Test
    fun producer_goal_completion_with_decimal_roundoff_commits_without_exceeding_target() = runTest {
        timeProvider.setTime(TimePeriodUtils.getEndOfMonth(timeProvider.now()) - 2 * 86_400_000L)
        database.savingsGoalDao().insertGoal(goal(1L, 592.09).copy(targetAmount = 1975.95))
        val producer = sweepProducer(1892.71)
        val candidate = (producer.computeSweepRecommendationResult() as SavingsSweepComputationResult.Available)
            .recommendation

        val resultingAmount = 592.09 + candidate.goalAllocations.single().suggestedAllocation
        assertTrue(resultingAmount <= 1975.95)
        assertEquals(1975.95, resultingAmount, Math.ulp(1975.95))
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(candidate))
        assertEquals(resultingAmount, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(candidate.goalAllocations.single().suggestedAllocation,
            database.savingsContributionLedgerDao().getAll().single().amount, 0.0)
    }

    @Test
    fun recomputed_sweep_is_rejected_in_same_month_even_after_plan_cleanup_but_next_month_is_allowed() = runTest {
        timeProvider.setTime(TimePeriodUtils.getEndOfMonth(timeProvider.now()) - 2 * 86_400_000L)
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val producer = sweepProducer(40.0)
        val first = (producer.computeSweepRecommendationResult() as SavingsSweepComputationResult.Available).recommendation
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(first))
        database.savingsSweepPlanDao().deleteOldPlans(timeProvider.now() + 1L)
        timeProvider.advanceTime(1_000L)
        // RG05A-1 (owner D-06 approval): the producer must not offer a card for a month that
        // already consumed its sweep, even after optional plan cleanup.
        assertNoRecommendation(
            "RG05A-1 producer must not re-offer a consumed month after plan cleanup",
            producer.computeSweepRecommendationResult()
        )
        // The coordinator remains the authoritative last line of defence for a stale card.
        val recomputed = first.copy(computedAt = first.computedAt + 1_000L)
        assertTrue(first != recomputed)
        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED"),
            coordinator.accept(recomputed)
        )
        assertEquals(232.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())

        val nextMonth = TimePeriodUtils.getEndOfMonth(timeProvider.now())
        timeProvider.setTime(TimePeriodUtils.getEndOfMonth(nextMonth) - 2 * 86_400_000L)
        val next = (producer.computeSweepRecommendationResult() as SavingsSweepComputationResult.Available).recommendation
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(next))
        assertEquals(264.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(2, database.savingsContributionLedgerDao().getAll().size)
    }

    @Test
    fun rg05a1_month_marker_alone_suppresses_producer_card() = runTest {
        val (monthStart, monthEndExclusive) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsSweepMonthAcceptanceDao().insert(
            SavingsSweepMonthAcceptance(
                monthStart = monthStart,
                monthEndExclusive = monthEndExclusive,
                actionedAt = timeProvider.now(),
                reasonCode = SavingsSweepMonthAcceptance.ACCEPTED
            )
        )

        assertNoRecommendation(
            "RG05A-1 marker covering month M must suppress the producer card",
            sweepProducer(40.0).computeSweepRecommendationResult()
        )
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
    }

    @Test
    fun rg05a1_accepted_plan_alone_suppresses_producer_card() = runTest {
        val (_, monthEndExclusive) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsSweepPlanDao().insert(
            sweepPlan(monthEndExclusive - 1L, SweepPlanStatus.ACCEPTED, "rg05a1-accepted-plan")
        )

        assertNoRecommendation(
            "RG05A-1 ACCEPTED plan for month M must suppress the producer card",
            sweepProducer(40.0).computeSweepRecommendationResult()
        )
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    @Test
    fun rg05a1_sweep_ledger_row_alone_suppresses_producer_card() = runTest {
        val (monthStart, _) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsContributionLedgerDao().insert(
            ledgerRow("rg05a1-sweep-row", "sweep", monthStart + 1_000L)
        )

        assertNoRecommendation(
            "RG05A-1 sweep ledger row inside month M must suppress the producer card",
            sweepProducer(40.0).computeSweepRecommendationResult()
        )
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
    }

    @Test
    fun rg05a1_previous_month_marker_does_not_suppress_producer_card() = runTest {
        val (monthStart, _) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val previousStart = TimePeriodUtils.getStartOfMonth(monthStart - 1L)
        database.savingsSweepMonthAcceptanceDao().insert(
            SavingsSweepMonthAcceptance(previousStart, monthStart, previousStart + 1_000L,
                SavingsSweepMonthAcceptance.ACCEPTED)
        )

        val result = sweepProducer(40.0).computeSweepRecommendationResult()

        assertTrue("RG05A-1 control: M-1 marker must leave month M available: $result",
            result is SavingsSweepComputationResult.Available)
    }

    @Test
    fun rg05a1_manual_ledger_row_does_not_suppress_producer_card() = runTest {
        val (monthStart, _) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsContributionLedgerDao().insert(
            ledgerRow("rg05a1-manual-row", "manual", monthStart + 1_000L)
        )

        val result = sweepProducer(40.0).computeSweepRecommendationResult()

        assertTrue("RG05A-1 control: manual ledger row must leave month M available: $result",
            result is SavingsSweepComputationResult.Available)
    }

    @Test
    fun rg05a1_pending_plan_does_not_suppress_producer_card() = runTest {
        val (_, monthEndExclusive) = enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        database.savingsSweepPlanDao().insert(
            sweepPlan(monthEndExclusive - 1L, SweepPlanStatus.PENDING, "rg05a1-pending-plan")
        )

        val result = sweepProducer(40.0).computeSweepRecommendationResult()

        assertTrue("RG05A-1 control: PENDING plan must leave month M available: $result",
            result is SavingsSweepComputationResult.Available)
    }

    @Test
    fun rg05a1_accept_then_new_producer_one_minute_later_offers_no_card() = runTest {
        enterSweepWindowOfCurrentMonth()
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val first = (sweepProducer(40.0).computeSweepRecommendationResult()
            as SavingsSweepComputationResult.Available).recommendation
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(first))

        timeProvider.advanceTime(60_000L)
        val laterProducer = sweepProducer(40.0)

        assertNoRecommendation(
            "RG05A-1 a fresh producer after acceptance in the same month must offer no card",
            laterProducer.computeSweepRecommendationResult()
        )
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        assertEquals(1, database.savingsSweepMonthAcceptanceDao().getAll().size)
        assertEquals(200.0 + first.goalAllocations.single().suggestedAllocation,
            database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
    }

    @Test
    fun safe_pool_above_underspend_after_buffer_is_rejected_without_writes() = runTest {
        database.savingsGoalDao().insertGoal(goal(1L, 200.0))
        val candidate = recommendation(allocation(1L, 200.0, 40.0))
            .copy(totalUnderspend = 49.0, riskBuffer = 10.0, safeSweepAmount = 40.0)
        assertEquals(SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_INVALID_RECOMMENDATION"),
            coordinator.accept(candidate))
        assertEquals(200.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
    }

    /** Moves the clock into the last days of its month; returns [start, endExclusive) of that month. */
    private fun enterSweepWindowOfCurrentMonth(): Pair<Long, Long> {
        timeProvider.setTime(TimePeriodUtils.getEndOfMonth(timeProvider.now()) - 2 * 86_400_000L)
        return TimePeriodUtils.getStartOfMonth(timeProvider.now()) to
            TimePeriodUtils.getEndOfMonth(timeProvider.now())
    }

    private fun assertNoRecommendation(message: String, result: SavingsSweepComputationResult) {
        assertTrue("$message but was $result", result is SavingsSweepComputationResult.NoRecommendation)
    }

    private fun sweepPlan(monthEndInclusive: Long, status: SweepPlanStatus, key: String) = SavingsSweepPlan(
        goalId = 1L,
        monthEnd = monthEndInclusive,
        totalUnderspend = 60.0,
        riskBuffer = 10.0,
        safeSweepAmount = 40.0,
        allocatedAmount = 40.0,
        allocationPercentage = 1.0,
        status = status,
        actionedAt = if (status == SweepPlanStatus.PENDING) null else timeProvider.now(),
        confidence = 0.9,
        currency = "EUR",
        computedAt = timeProvider.now(),
        acceptanceKey = key
    )

    private fun ledgerRow(key: String, source: String, timestamp: Long) = SavingsContributionLedgerEntry(
        idempotencyKey = key,
        goalId = 1L,
        amount = 40.0,
        currency = "EUR",
        timestamp = timestamp,
        source = source,
        sweepPlanId = null
    )

    private fun sweepProducer(safeAmount: Double): MonthlySavingsSweepUseCase {
        val budgets = mockk<BudgetRepository>()
        val goals = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>()
        val expenses = mockk<ExpenseRepository>()
        val recurring = mockk<RecurringExpenseRepository>()
        val planned = mockk<PlannedExpenseRepository>()
        val simulator = mockk<com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator>()
        val normalizer = mockk<com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer>()
        val settings = mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>()
        val converter = mockk<com.yourname.expensetracker.domain.currency.CurrencyConverter>()
        val occurrences = mockk<com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao>()
        every { budgets.getBudgetStatuses() } returns flowOf(listOf(
            com.yourname.expensetracker.domain.budget.BudgetStatus(
                budget = com.yourname.expensetracker.data.database.entity.Budget(
                    id = 1L, categoryId = null, amount = safeAmount,
                    period = com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY,
                    startDate = timeProvider.now(), currency = "EUR", currencyAssumption = "EXPLICIT"
                ), category = null, spentAmount = 0.0, remainingAmount = safeAmount,
                percentUsed = 0f, healthStatus = com.yourname.expensetracker.domain.budget.BudgetHealthStatus.ON_TRACK,
                periodStart = 0L, periodEnd = Long.MAX_VALUE, effectiveLimit = safeAmount
            )
        ))
        every { goals.observeSavingsGoals() } answers {
            kotlinx.coroutines.flow.flow {
                val row = database.savingsGoalDao().getById(1L)!!
                emit(listOf(com.yourname.expensetracker.domain.model.SavingsGoal(
                    row.id, row.name, row.targetAmount, row.currentAmount, null,
                    com.yourname.expensetracker.domain.model.GoalProtectionLevel.WARNING,
                    row.currency, row.currencyAssumption, row.createdAt
                )))
            }
        }
        every { settings.homeCurrency() } returns flowOf("EUR")
        every { settings.lastRateUpdate() } returns flowOf(0L)
        every { recurring.getAllFlow() } returns flowOf(emptyList())
        every { planned.getAllPlannedExpenses() } returns flowOf(emptyList())
        coEvery { expenses.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { normalizer.normalizeSnapshots(any(), "EUR") } returns
            com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult(
                "EUR", emptyList(), emptyList(), emptyList(), null, totalInputCount = 0
            )
        coEvery { occurrences.getByDateRange(any(), any()) } returns emptyList()
        coEvery { converter.convertMultiple(any(), "EUR") } returns
            com.yourname.expensetracker.domain.currency.MultiConversionAggregate(0.0, "EUR", emptyList())
        coEvery { simulator.simulate(any(), any(), any(), "EUR", any()) } returns
            com.yourname.expensetracker.domain.forecasting.MonteCarloResult(
                0.0, 0.0, 0.0, 0.0, 0.0, 1.0, safeAmount, 0.0, 0.0,
                com.yourname.expensetracker.domain.forecasting.SimulationConfidence(
                    0.9, com.yourname.expensetracker.domain.forecasting.ConfidenceLevel.HIGH, "TEST"
                ), com.yourname.expensetracker.domain.forecasting.SimulationMetadata(
                    10, 10, 1000, 0.0, 0.0, 1, timeProvider.now()
                ), "EUR"
            )
        return MonthlySavingsSweepUseCase(budgets, goals, expenses, recurring, planned, simulator,
            timeProvider, normalizer, settings, converter, occurrences,
            SavingsSweepMonthConsumption(
                database.savingsSweepMonthAcceptanceDao(),
                database.savingsSweepPlanDao(),
                database.savingsContributionLedgerDao()
            ))
    }

    private fun ledgerDao(
        insertResult: Long = 1L,
        insertError: CancellationException? = null
    ): SavingsContributionLedgerDao {
        val realDao = database.savingsContributionLedgerDao()
        return object : SavingsContributionLedgerDao {
            override suspend fun insert(entry: SavingsContributionLedgerEntry): Long {
                insertError?.let { throw it }
                return insertResult
            }

            override suspend fun getByIdempotencyKey(idempotencyKey: String) =
                realDao.getByIdempotencyKey(idempotencyKey)

            override suspend fun getAll() = realDao.getAll()

            override suspend fun hasSweepBetween(startInclusive: Long, endExclusive: Long) =
                realDao.hasSweepBetween(startInclusive, endExclusive)
        }
    }

    private class InjectedCancellation(val checkpoint: String) :
        CancellationException("cancelled after writes")

    private fun goal(
        id: Long,
        currentAmount: Double,
        currencyAssumption: String = "EXPLICIT"
    ) = SavingsGoalEntity(
        id = id,
        name = "Goal $id",
        targetAmount = 1_000.0,
        currentAmount = currentAmount,
        protectionLevel = GoalProtectionLevel.WARNING,
        currency = "EUR",
        currencyAssumption = currencyAssumption,
        createdAt = timeProvider.now()
    )

    private fun allocation(goalId: Long, currentProgress: Double, amount: Double) = GoalAllocation(
        goalId = goalId,
        goalName = "Goal $goalId",
        currentProgress = currentProgress,
        targetAmount = 1_000.0,
        suggestedAllocation = amount,
        allocationPercentage = 0.5,
        currency = "EUR"
    )

    private fun recommendation(vararg allocations: GoalAllocation) = SavingsSweepRecommendation(
        totalUnderspend = maxOf(60.0, allocations.sumOf { it.suggestedAllocation } + 10.0),
        riskBuffer = 10.0,
        safeSweepAmount = allocations.sumOf { it.suggestedAllocation },
        goalAllocations = allocations.toList(),
        computedAt = timeProvider.now(),
        monthEnd = timeProvider.now() + 1_000L,
        confidence = 0.9,
        currency = "EUR",
        rateTimestamp = 0L
    )
}
