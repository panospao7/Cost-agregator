package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentRecommendation
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.RecommendationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Focused DAO tests for [BudgetAdjustmentDao] timestamp semantics:
 * - markRecommendationApplied / markRecommendationDismissed must persist the
 *   exact caller-supplied timestamp (no wall clock).
 * - expireOldRecommendations must use the caller-supplied `now` and only touch
 *   PENDING rows whose expiresAt is strictly before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BudgetAdjustmentDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BudgetAdjustmentDao
    private var budgetId: Long = 0L

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.budgetAdjustmentDao()
        budgetId = runBlocking {
            database.budgetDao().insert(
                Budget(
                    categoryId = null,
                    amount = 1000.0,
                    period = BudgetPeriod.MONTHLY,
                    startDate = FIXED_NOW,
                    currency = "EUR",
                    activeOverallKey = 1L,
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
    fun `markRecommendationApplied persists supplied timestamp to appliedAt and sets status APPLIED`() = runTest {
        val id = dao.insertRecommendation(createRecommendation())

        val appliedAt = FIXED_NOW + 60_000L
        dao.markRecommendationApplied(id, appliedAt)

        val loaded = dao.getRecommendationById(id)
        assertNotNull(loaded)
        assertEquals(RecommendationStatus.APPLIED, loaded.status)
        assertEquals(appliedAt, loaded.appliedAt)
        assertNull(loaded.dismissedAt)
    }

    @Test
    fun `markRecommendationDismissed persists supplied timestamp to dismissedAt and sets status DISMISSED`() = runTest {
        val id = dao.insertRecommendation(createRecommendation())

        val dismissedAt = FIXED_NOW + 120_000L
        dao.markRecommendationDismissed(id, dismissedAt)

        val loaded = dao.getRecommendationById(id)
        assertNotNull(loaded)
        assertEquals(RecommendationStatus.DISMISSED, loaded.status)
        assertEquals(dismissedAt, loaded.dismissedAt)
        assertNull(loaded.appliedAt)
    }

    @Test
    fun `expireOldRecommendations uses supplied now and only expires pending rows with expiresAt before it`() = runTest {
        val expiredId = dao.insertRecommendation(createRecommendation(expiresAt = FIXED_NOW - 1L))
        val boundaryId = dao.insertRecommendation(createRecommendation(expiresAt = FIXED_NOW))
        val futureId = dao.insertRecommendation(createRecommendation(expiresAt = FIXED_NOW + 1L))
        val appliedId = dao.insertRecommendation(
            createRecommendation(expiresAt = FIXED_NOW - 1L, status = RecommendationStatus.APPLIED)
        )

        dao.expireOldRecommendations(FIXED_NOW)

        // Strictly before now: expired.
        assertEquals(RecommendationStatus.EXPIRED, dao.getRecommendationById(expiredId)?.status)
        // Boundary (expiresAt == now) is NOT strictly before now: stays pending.
        assertEquals(RecommendationStatus.PENDING, dao.getRecommendationById(boundaryId)?.status)
        // After now: stays pending.
        assertEquals(RecommendationStatus.PENDING, dao.getRecommendationById(futureId)?.status)
        // Non-pending rows are never touched, regardless of expiry.
        assertEquals(RecommendationStatus.APPLIED, dao.getRecommendationById(appliedId)?.status)
    }

    private fun createRecommendation(
        status: RecommendationStatus = RecommendationStatus.PENDING,
        expiresAt: Long? = FIXED_NOW + 7L * 24 * 60 * 60 * 1000,
        appliedAt: Long? = null,
        dismissedAt: Long? = null
    ): BudgetAdjustmentRecommendation {
        return BudgetAdjustmentRecommendation(
            budgetId = budgetId,
            categoryId = null,
            categoryName = "Overall",
            currentBudget = 500.0,
            recommendedBudget = 550.0,
            delta = 50.0,
            currency = "EUR",
            deltaPercentage = 10.0,
            reason = "Spending trend increased",
            confidence = 0.8,
            trend = BudgetTrend.INCREASING,
            status = status,
            generatedAt = FIXED_NOW,
            expiresAt = expiresAt,
            appliedAt = appliedAt,
            dismissedAt = dismissedAt
        )
    }
}
