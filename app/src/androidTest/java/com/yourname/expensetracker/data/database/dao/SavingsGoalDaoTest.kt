package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room-backed regression tests for [SavingsGoalDao], focused on
 * the atomic `addToGoalAmount` increment semantics (Phase B.4 Batch 9).
 *
 * Reviewer finding: the atomicity fix was only validated via mocked
 * repository in ViewModel tests.  These tests exercise the real
 * Room-generated SQL (`SET currentAmount = currentAmount + :delta`)
 * to prove stacked and concurrent increments accumulate correctly.
 */
@RunWith(AndroidJUnit4::class)
class SavingsGoalDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SavingsGoalDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.savingsGoalDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeGoal(
        name: String = "Test Goal",
        targetAmount: Double = 1000.0,
        currentAmount: Double = 0.0,
        protectionLevel: GoalProtectionLevel = GoalProtectionLevel.WARNING,
        targetDate: Long? = null,
        createdAt: Long = 1_700_000_000_000L
    ) = SavingsGoal(
        name = name,
        targetAmount = targetAmount,
        currentAmount = currentAmount,
        protectionLevel = protectionLevel,
        targetDate = targetDate,
        createdAt = createdAt
    )

    // ── Basic CRUD sanity ────────────────────────────────────────────

    @Test
    fun insert_then_getById_returns_persisted_goal() = runBlocking {
        val id = dao.insertGoal(makeGoal(name = "Emergency", targetAmount = 5000.0, currentAmount = 200.0))
        val stored = dao.getById(id)

        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals("Emergency", stored.name)
        assertEquals(5000.0, stored.targetAmount, 0.0001)
        assertEquals(200.0, stored.currentAmount, 0.0001)
    }

    @Test
    fun delete_then_getById_returns_null() = runBlocking {
        val id = dao.insertGoal(makeGoal())
        val stored = dao.getById(id)!!
        dao.deleteGoal(stored)

        assertNull(dao.getById(id))
    }

    @Test
    fun getAllGoals_returns_flow_of_all_inserted_goals() = runBlocking {
        dao.insertGoal(makeGoal(name = "A"))
        dao.insertGoal(makeGoal(name = "B"))
        dao.insertGoal(makeGoal(name = "C"))

        val all = dao.getAllGoals().first()
        assertEquals(3, all.size)
        assertEquals(setOf("A", "B", "C"), all.map { it.name }.toSet())
    }

    // ── updateGoalAmount (absolute set) ──────────────────────────────

    @Test
    fun updateGoalAmount_sets_currentAmount_to_exact_value() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 100.0))

        dao.updateGoalAmount(id, 350.0)

        val updated = dao.getById(id)!!
        assertEquals(350.0, updated.currentAmount, 0.0001)
    }

    // ── addToGoalAmount — atomic increment core tests ────────────────

    @Test
    fun addToGoalAmount_single_increment_accumulates_on_current_value() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 100.0))

        val rowsUpdated = dao.addToGoalAmount(id, 50.0)

        assertEquals(1, rowsUpdated)
        assertEquals(150.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun addToGoalAmount_stacked_increments_accumulate_correctly() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 100.0))

        dao.addToGoalAmount(id, 25.0)
        dao.addToGoalAmount(id, 50.0)
        dao.addToGoalAmount(id, 75.0)

        // 100 + 25 + 50 + 75 = 250
        assertEquals(250.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun addToGoalAmount_with_fractional_cents_accumulates_precisely() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 0.0))

        // Simulate many small contributions that could expose floating-point drift
        repeat(100) {
            dao.addToGoalAmount(id, 0.01)
        }

        assertEquals(1.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun addToGoalAmount_negative_delta_decrements_correctly() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 500.0))

        val rowsUpdated = dao.addToGoalAmount(id, -100.0)

        assertEquals(1, rowsUpdated)
        assertEquals(400.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun addToGoalAmount_zero_delta_leaves_amount_unchanged() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 250.0))

        val rowsUpdated = dao.addToGoalAmount(id, 0.0)

        assertEquals(1, rowsUpdated)
        assertEquals(250.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun addToGoalAmount_nonexistent_goal_returns_zero_rows() = runBlocking {
        dao.insertGoal(makeGoal()) // ensures table exists

        val rowsUpdated = dao.addToGoalAmount(goalId = 9999L, delta = 50.0)

        assertEquals(0, rowsUpdated)
    }

    @Test
    fun addToGoalAmount_does_not_affect_other_goals() = runBlocking {
        val id1 = dao.insertGoal(makeGoal(name = "Goal A", currentAmount = 100.0))
        val id2 = dao.insertGoal(makeGoal(name = "Goal B", currentAmount = 200.0))

        dao.addToGoalAmount(id1, 50.0)

        assertEquals(150.0, dao.getById(id1)!!.currentAmount, 0.0001)
        assertEquals(200.0, dao.getById(id2)!!.currentAmount, 0.0001) // untouched
    }

    // ── Concurrent increment regression ──────────────────────────────
    //
    // The atomic SQL UPDATE is inherently serialized by SQLite's
    // write lock, so concurrent coroutines should never lose an
    // increment.  This test launches N parallel increments and
    // asserts the final total equals N * delta — any
    // read-modify-write regression would produce a lower value.

    @Test
    fun addToGoalAmount_concurrent_increments_no_lost_updates() = runBlocking {
        val id = dao.insertGoal(makeGoal(currentAmount = 0.0))

        val concurrency = 50
        val delta = 10.0

        val jobs = (1..concurrency).map {
            async {
                dao.addToGoalAmount(id, delta)
            }
        }
        jobs.awaitAll()

        val expected = concurrency * delta // 500.0
        assertEquals(expected, dao.getById(id)!!.currentAmount, 0.0001)
    }

    // ── Sweep-allocation application path ────────────────────────────
    //
    // The ViewModel's acceptSweepRecommendation() iterates a list of
    // GoalAllocations and calls addToGoalAmount once per goal.
    // This test mirrors that exact pattern against real Room to prove
    // multi-goal sweep allocations accumulate correctly.

    @Test
    fun sweep_allocation_multi_goal_increments_accumulate_correctly() = runBlocking {
        val goalA = dao.insertGoal(makeGoal(name = "Emergency Fund", currentAmount = 100.0, targetAmount = 1000.0))
        val goalB = dao.insertGoal(makeGoal(name = "Vacation", currentAmount = 200.0, targetAmount = 3000.0))
        val goalC = dao.insertGoal(makeGoal(name = "New Laptop", currentAmount = 50.0, targetAmount = 1500.0))

        // Simulate a sweep recommendation with 3 allocations (mirrors ViewModel loop)
        val allocations = listOf(
            goalA to 80.0,   // Emergency Fund gets 80
            goalB to 120.0,  // Vacation gets 120
            goalC to 50.0    // New Laptop gets 50
        )
        for ((goalId, amount) in allocations) {
            dao.addToGoalAmount(goalId, amount)
        }

        assertEquals(180.0, dao.getById(goalA)!!.currentAmount, 0.0001)  // 100 + 80
        assertEquals(320.0, dao.getById(goalB)!!.currentAmount, 0.0001)  // 200 + 120
        assertEquals(100.0, dao.getById(goalC)!!.currentAmount, 0.0001)  // 50 + 50
    }

    @Test
    fun sweep_allocation_followed_by_manual_contribution_stacks() = runBlocking {
        val id = dao.insertGoal(makeGoal(name = "Emergency", currentAmount = 100.0))

        // Sweep allocation first
        dao.addToGoalAmount(id, 75.0)
        // Then a manual contribution
        dao.addToGoalAmount(id, 25.0)

        // Both should stack: 100 + 75 + 25 = 200
        assertEquals(200.0, dao.getById(id)!!.currentAmount, 0.0001)
    }

    @Test
    fun sweep_allocation_with_nonexistent_goal_in_mix_does_not_corrupt_others() = runBlocking {
        val goodGoal = dao.insertGoal(makeGoal(name = "Real Goal", currentAmount = 100.0))
        val deletedGoalId = 9999L

        // Simulate a sweep where one goal was deleted between computation and application
        dao.addToGoalAmount(goodGoal, 50.0)
        val missingResult = dao.addToGoalAmount(deletedGoalId, 100.0)
        dao.addToGoalAmount(goodGoal, 30.0)

        assertEquals(0, missingResult) // deleted goal: no rows updated
        assertEquals(180.0, dao.getById(goodGoal)!!.currentAmount, 0.0001) // 100 + 50 + 30
    }

    @Test
    fun concurrent_sweep_allocations_to_same_goal_accumulate() = runBlocking {
        val id = dao.insertGoal(makeGoal(name = "Shared Target", currentAmount = 0.0))

        // Simulate two sweep cycles hitting the same goal concurrently
        val sweepAllocations = listOf(45.0, 55.0, 30.0, 70.0)

        val jobs = sweepAllocations.map { amount ->
            async { dao.addToGoalAmount(id, amount) }
        }
        jobs.awaitAll()

        // 45 + 55 + 30 + 70 = 200
        assertEquals(200.0, dao.getById(id)!!.currentAmount, 0.0001)
    }
}
