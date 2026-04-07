package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.analytics.fixtures.GoldenDataSets
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SavingsGamificationEngineTest : AnalyticsEngineTestBase() {

    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var engine: SavingsGamificationEngine

    @Before
    override fun setUp() {
        super.setUp()
        savingsGoalRepository = mockk(relaxed = true)

        // Keep tests aligned with the shared golden reference date.
        every { timeProvider.now() } returns GoldenDataSets.APRIL_1_2026

        engine = SavingsGamificationEngine(
            savingsGoalRepository = savingsGoalRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `calculateStreak consecutive days of savings returns correct streak count`() = runTest {
        val dayMs = 24L * 60 * 60 * 1000
        val now = GoldenDataSets.APRIL_1_2026

        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(
                goal(id = 1L, currentAmount = 20.0, createdAt = now - dayMs),
                goal(id = 2L, currentAmount = 30.0, createdAt = now - (2 * dayMs)),
                goal(id = 3L, currentAmount = 50.0, createdAt = now - (3 * dayMs))
            )
        )

        val streak = engine.calculateStreak()

        assertEquals(5, streak.currentStreakDays)
        assertEquals(30, streak.personalBestDays)
        assertEquals(now - dayMs, streak.lastSavingsDate)
        assertEquals(3, streak.monthlyContributions)
        assertApproxEquals(100.0, streak.totalContributedThisMonth, 0.0001)
    }

    @Test
    fun `getAchievements milestones unlocked at thresholds 100 500 1000`() = runTest {
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 10L, currentAmount = 100.0, targetAmount = 2000.0, createdAt = march2026Start))
        )
        val at100 = engine.getAchievements()
        val at100Century = achievement(at100, "century_saver")
        val at100Thousand = achievement(at100, "thousand_saver")

        assertTrue(at100Century.isUnlocked)
        assertNotNull(at100Century.unlockedAt)
        assertApproxEquals(1.0, at100Century.progress, 0.0001)
        assertFalse(at100Thousand.isUnlocked)
        assertApproxEquals(0.1, at100Thousand.progress, 0.0001)

        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 11L, currentAmount = 500.0, targetAmount = 2000.0, createdAt = march2026Start))
        )
        val at500 = engine.getAchievements()
        val at500Century = achievement(at500, "century_saver")
        val at500Thousand = achievement(at500, "thousand_saver")

        assertTrue(at500Century.isUnlocked)
        assertFalse(at500Thousand.isUnlocked)
        assertApproxEquals(0.5, at500Thousand.progress, 0.0001)

        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 12L, currentAmount = 1000.0, targetAmount = 2000.0, createdAt = march2026Start))
        )
        val at1000 = engine.getAchievements()
        val at1000Century = achievement(at1000, "century_saver")
        val at1000Thousand = achievement(at1000, "thousand_saver")

        assertTrue(at1000Century.isUnlocked)
        assertTrue(at1000Thousand.isUnlocked)
        assertNotNull(at1000Thousand.unlockedAt)
        assertApproxEquals(1.0, at1000Thousand.progress, 0.0001)
    }

    @Test
    fun `calculateLevel level based on total saved brackets`() {
        assertEquals(1, engine.calculateLevel(0.0))
        assertEquals(1, engine.calculateLevel(99.0))
        assertEquals(1, engine.calculateLevel(100.0))
        assertEquals(1, engine.calculateLevel(499.0))
        assertEquals(2, engine.calculateLevel(500.0))
        assertEquals(2, engine.calculateLevel(999.0))
        assertEquals(3, engine.calculateLevel(1000.0))
        assertEquals(4, engine.calculateLevel(1500.0))
    }

    @Test
    fun `getLevelTitle returns correct title for each level`() {
        assertEquals("Savings Rookie", engine.getLevelTitle(1))
        assertEquals("Savings Apprentice", engine.getLevelTitle(2))
        assertEquals("Savings Journeyman", engine.getLevelTitle(3))
        assertEquals("Savings Expert", engine.getLevelTitle(4))
        assertEquals("Savings Master", engine.getLevelTitle(5))
        assertEquals("Savings Legend", engine.getLevelTitle(6))
    }

    private fun achievement(achievements: List<SavingsAchievement>, id: String): SavingsAchievement {
        return achievements.first { it.id == id }
    }

    private fun goal(
        id: Long,
        currentAmount: Double,
        targetAmount: Double = 1000.0,
        createdAt: Long
    ): SavingsGoal {
        return SavingsGoal(
            id = id,
            name = "Goal $id",
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            createdAt = createdAt
        )
    }
}
