package com.yourname.expensetracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import com.yourname.expensetracker.data.database.entity.SavingsGoal as SavingsGoalEntity
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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

    private fun newCoordinator(ledgerDao: SavingsContributionLedgerDao) =
        SavingsSweepAcceptanceCoordinator(
            database,
            database.savingsGoalDao(),
            database.savingsSweepPlanDao(),
            ledgerDao,
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
        totalUnderspend = 50.0,
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
