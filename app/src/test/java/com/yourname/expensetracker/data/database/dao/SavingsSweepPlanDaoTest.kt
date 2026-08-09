package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Focused DAO tests for [SavingsSweepPlanDao] timestamp semantics:
 * - acceptPlan / dismissPlan / updateStatus must persist the exact
 *   caller-supplied timestamp into actionedAt (no wall clock).
 * - expireOldPlans must use the caller-supplied `currentTime` and only touch
 *   PENDING rows whose monthEnd is strictly before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsSweepPlanDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SavingsSweepPlanDao
    private var goalId: Long = 0L

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.savingsSweepPlanDao()
        goalId = runBlocking {
            database.savingsGoalDao().insertGoal(
                SavingsGoal(
                    name = "Emergency Fund",
                    targetAmount = 5000.0,
                    targetDate = FIXED_NOW + 365L * 24 * 60 * 60 * 1000,
                    currency = "EUR",
                    createdAt = FIXED_NOW
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `acceptPlan persists supplied timestamp to actionedAt and sets status ACCEPTED`() = runTest {
        val id = dao.insert(createPlan())

        val actionedAt = FIXED_NOW + 60_000L
        dao.acceptPlan(id, actionedAt)

        val loaded = dao.getPlansForGoal(goalId).single()
        assertEquals(SweepPlanStatus.ACCEPTED, loaded.status)
        assertEquals(actionedAt, loaded.actionedAt)
    }

    @Test
    fun `dismissPlan persists supplied timestamp to actionedAt and sets status DISMISSED`() = runTest {
        val id = dao.insert(createPlan())

        val actionedAt = FIXED_NOW + 120_000L
        dao.dismissPlan(id, actionedAt)

        val loaded = dao.getPlansForGoal(goalId).single()
        assertEquals(SweepPlanStatus.DISMISSED, loaded.status)
        assertEquals(actionedAt, loaded.actionedAt)
    }

    @Test
    fun `updateStatus persists supplied timestamp to actionedAt and sets requested status`() = runTest {
        val id = dao.insert(createPlan())

        val actionedAt = FIXED_NOW + 180_000L
        dao.updateStatus(id, SweepPlanStatus.MODIFIED, actionedAt)

        val loaded = dao.getPlansForGoal(goalId).single()
        assertEquals(SweepPlanStatus.MODIFIED, loaded.status)
        assertEquals(actionedAt, loaded.actionedAt)
    }

    @Test
    fun `expireOldPlans uses supplied currentTime and only expires pending plans with monthEnd before it`() = runTest {
        val expiredId = dao.insert(createPlan(monthEnd = FIXED_NOW - 1L))
        val boundaryId = dao.insert(createPlan(monthEnd = FIXED_NOW))
        val futureId = dao.insert(createPlan(monthEnd = FIXED_NOW + 1L))
        val acceptedId = dao.insert(
            createPlan(monthEnd = FIXED_NOW - 1L, status = SweepPlanStatus.ACCEPTED, actionedAt = FIXED_NOW - 1000L)
        )

        dao.expireOldPlans(FIXED_NOW)

        // Strictly before currentTime: expired.
        assertEquals(SweepPlanStatus.EXPIRED, dao.getPlansForGoal(goalId).first { it.id == expiredId }.status)
        // Boundary (monthEnd == currentTime) is NOT strictly before it: stays pending.
        assertEquals(SweepPlanStatus.PENDING, dao.getPlansForGoal(goalId).first { it.id == boundaryId }.status)
        // After currentTime: stays pending.
        assertEquals(SweepPlanStatus.PENDING, dao.getPlansForGoal(goalId).first { it.id == futureId }.status)
        // Non-pending rows are never touched, regardless of monthEnd.
        assertEquals(SweepPlanStatus.ACCEPTED, dao.getPlansForGoal(goalId).first { it.id == acceptedId }.status)
    }

    private fun createPlan(
        monthEnd: Long = FIXED_NOW + 30L * 24 * 60 * 60 * 1000,
        status: SweepPlanStatus = SweepPlanStatus.PENDING,
        actionedAt: Long? = null
    ): SavingsSweepPlan {
        return SavingsSweepPlan(
            goalId = goalId,
            monthEnd = monthEnd,
            totalUnderspend = 250.0,
            riskBuffer = 50.0,
            safeSweepAmount = 200.0,
            allocatedAmount = 200.0,
            allocationPercentage = 100.0,
            status = status,
            actionedAt = actionedAt,
            notes = null,
            confidence = 0.9,
            currency = "EUR",
            computedAt = FIXED_NOW
        )
    }
}
