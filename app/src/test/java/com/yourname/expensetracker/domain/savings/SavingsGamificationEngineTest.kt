package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SavingsGamificationEngineTest {

    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var contributionHistoryRepository: SavingsContributionHistoryRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var engine: SavingsGamificationEngine
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        savingsGoalRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider.forDate(2026, 4, 1)
        contributionHistoryRepository = createRepository(createStateFile(), timeProvider)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)

        engine = SavingsGamificationEngine(
            savingsGoalRepository = savingsGoalRepository,
            contributionHistoryRepository = contributionHistoryRepository,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `calculateStreak uses recorded contribution history for streak and month totals`() = runTest {
        val march30 = FakeTimeProvider.forDate(2026, 3, 30, 8, 0).now()
        val march31 = FakeTimeProvider.forDate(2026, 3, 31, 18, 0).now()
        val april1Morning = FakeTimeProvider.forDate(2026, 4, 1, 9, 0).now()
        val april1Evening = FakeTimeProvider.forDate(2026, 4, 1, 20, 0).now()

        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        contributionHistoryRepository.recordContribution(1L, 10.0, march30, "manual")
        contributionHistoryRepository.recordContribution(1L, 12.0, march31, "manual")
        contributionHistoryRepository.recordContribution(2L, 15.0, april1Morning, "manual")
        contributionHistoryRepository.recordContribution(2L, 20.0, april1Evening, "sweep")

        val streak = engine.calculateStreak()

        assertEquals(3, streak.currentStreakDays)
        assertEquals(3, streak.personalBestDays)
        assertEquals(april1Evening, streak.lastSavingsDate)
        assertEquals(2, streak.monthlyContributions)
        assertApproxEquals(35.0, streak.totalContributedThisMonth, 0.0001)
    }

    @Test
    fun `calculateStreak returns honest zero history for legacy balances without events`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(
                goal(id = 1L, currentAmount = 250.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now())
            )
        )

        val streak = engine.calculateStreak()

        assertEquals(0, streak.currentStreakDays)
        assertEquals(0, streak.personalBestDays)
        assertNull(streak.lastSavingsDate)
        assertEquals(0, streak.monthlyContributions)
        assertApproxEquals(0.0, streak.totalContributedThisMonth, 0.0001)
    }

    @Test
    fun `calculateStreak resets current streak when latest contribution is older than yesterday`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        contributionHistoryRepository.recordContribution(1L, 5.0, FakeTimeProvider.forDate(2026, 3, 20, 8, 0).now())
        contributionHistoryRepository.recordContribution(1L, 5.0, FakeTimeProvider.forDate(2026, 3, 21, 8, 0).now())
        contributionHistoryRepository.recordContribution(1L, 5.0, FakeTimeProvider.forDate(2026, 3, 22, 8, 0).now())
        contributionHistoryRepository.recordContribution(1L, 5.0, FakeTimeProvider.forDate(2026, 3, 23, 8, 0).now())
        contributionHistoryRepository.recordContribution(1L, 5.0, FakeTimeProvider.forDate(2026, 3, 24, 8, 0).now())

        val streak = engine.calculateStreak()

        assertEquals(0, streak.currentStreakDays)
        assertEquals(5, streak.personalBestDays)
    }

    @Test
    fun `getAchievements milestones unlocked at thresholds 100 500 1000`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 10L, currentAmount = 100.0, targetAmount = 2000.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
        )
        val at100 = engine.getAchievements()
        val at100Century = achievement(at100, "century_saver")
        val at100Thousand = achievement(at100, "thousand_saver")

        assertTrue(at100Century.isUnlocked)
        assertNotNull(at100Century.unlockedAt)
        assertApproxEquals(1.0, at100Century.progress, 0.0001)
        assertFalse(at100Thousand.isUnlocked)
        assertApproxEquals(0.1, at100Thousand.progress, 0.0001)

        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 11L, currentAmount = 500.0, targetAmount = 2000.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
        )
        val at500 = engine.getAchievements()
        val at500Century = achievement(at500, "century_saver")
        val at500Thousand = achievement(at500, "thousand_saver")

        assertTrue(at500Century.isUnlocked)
        assertFalse(at500Thousand.isUnlocked)
        assertApproxEquals(0.5, at500Thousand.progress, 0.0001)

        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 12L, currentAmount = 1000.0, targetAmount = 2000.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
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
    fun `getAchievements unlocks seven day streak from recorded history`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 20L, currentAmount = 40.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
        )

        for (day in 26..31) {
            contributionHistoryRepository.recordContribution(
                goalId = 20L,
                amount = 5.0,
                timestamp = FakeTimeProvider.forDate(2026, 3, day, 12, 0).now(),
                source = "manual"
            )
        }
        contributionHistoryRepository.recordContribution(
            goalId = 20L,
            amount = 5.0,
            timestamp = FakeTimeProvider.forDate(2026, 4, 1, 12, 0).now(),
            source = "manual"
        )

        val achievement = achievement(engine.getAchievements(), "saving_streak_7")

        assertTrue(achievement.isUnlocked)
        assertNotNull(achievement.unlockedAt)
        assertApproxEquals(1.0, achievement.progress, 0.0001)
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

    private fun createRepository(
        stateFile: File,
        timeProvider: FakeTimeProvider
    ): SavingsContributionHistoryRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { stateFile }
        )
        return SavingsContributionHistoryRepository(dataStore, timeProvider)
    }

    private fun createStateFile(): File {
        return Files.createTempFile("savings-gamification-history", ".preferences_pb").toFile()
    }
}
