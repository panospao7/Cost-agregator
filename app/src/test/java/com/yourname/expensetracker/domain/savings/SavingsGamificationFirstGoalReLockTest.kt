package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * savings-goal-delete AE-22 (O-5, accepted): the `first_goal` achievement is derived from the
 * goals that currently exist, so deleting the only goal re-locks it. Also pins the real
 * zero-goal header values for AE-21 against the real engine (not the mocked one used by the
 * ViewModel tests). The existing engine tests are untouched.
 */
class SavingsGamificationFirstGoalReLockTest {

    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var engine: SavingsGamificationEngine
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        savingsGoalRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider.forDate(2026, 4, 1)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf(CurrencyCode.EUR.code)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val stateFile: File = Files.createTempFile("savings-first-goal-relock", ".preferences_pb").toFile()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { stateFile })
        engine = SavingsGamificationEngine(
            savingsGoalRepository = savingsGoalRepository,
            contributionHistoryRepository = SavingsContributionHistoryRepository(dataStore, timeProvider),
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `AE-22 control - first_goal is unlocked while the only goal exists`() = runTest {
        val created = FakeTimeProvider.forDate(2026, 3, 1).now()
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(goal(1L, created)))

        val firstGoal = engine.getAchievements(homeCurrency = "EUR").single { it.id == "first_goal" }

        assertTrue(firstGoal.isUnlocked)
        assertEquals(1.0, firstGoal.progress, 0.0)
        assertEquals(created, firstGoal.unlockedAt)
    }

    @Test
    fun `AE-22 first_goal re-locks when the only goal is deleted`() = runTest {
        val created = FakeTimeProvider.forDate(2026, 3, 1).now()
        val goals = MutableStateFlow(listOf(goal(1L, created)))
        every { savingsGoalRepository.observeSavingsGoals() } returns goals
        assertTrue(engine.getAchievements(homeCurrency = "EUR").single { it.id == "first_goal" }.isUnlocked)

        goals.value = emptyList()
        val firstGoal = engine.getAchievements(homeCurrency = "EUR").single { it.id == "first_goal" }

        assertFalse("first_goal is derived, so it re-locks (accepted O-5)", firstGoal.isUnlocked)
        assertEquals(0.0, firstGoal.progress, 0.0)
        assertNull(firstGoal.unlockedAt)
    }

    @Test
    fun `AE-22 boundary - deleting one of two goals keeps first_goal unlocked from the earliest remaining`() = runTest {
        val older = FakeTimeProvider.forDate(2026, 2, 1).now()
        val newer = FakeTimeProvider.forDate(2026, 3, 1).now()
        val goals = MutableStateFlow(listOf(goal(1L, older), goal(2L, newer)))
        every { savingsGoalRepository.observeSavingsGoals() } returns goals
        assertEquals(older, engine.getAchievements(homeCurrency = "EUR").single { it.id == "first_goal" }.unlockedAt)

        goals.value = listOf(goal(2L, newer))
        val firstGoal = engine.getAchievements(homeCurrency = "EUR").single { it.id == "first_goal" }

        assertTrue(firstGoal.isUnlocked)
        assertEquals(newer, firstGoal.unlockedAt)
    }

    @Test
    fun `AE-21 real engine yields a real zero total and level one for an empty goal list`() = runTest {
        val result = engine.aggregateGoalBalances(emptyList(), "EUR")

        assertTrue("an empty list is Available, not Unavailable", result is MoneyAggregateResult.Available)
        val aggregate = (result as MoneyAggregateResult.Available).aggregate
        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(ConversionQuality.COMPLETE, aggregate.conversionQuality)
        assertFalse(aggregate.isPartial)
        assertNotNull(engine.getLevelTitle(engine.calculateLevel(0.0)))
        assertEquals(1, engine.calculateLevel(0.0))
        assertEquals("Savings Rookie", engine.getLevelTitle(engine.calculateLevel(0.0)))
    }

    private fun goal(id: Long, createdAt: Long) = SavingsGoal(
        id = id,
        name = "Test Goal $id",
        targetAmount = 1000.0,
        currentAmount = 0.0,
        targetDate = null,
        protectionLevel = GoalProtectionLevel.WARNING,
        currencyAssumption = "EXPLICIT",
        createdAt = createdAt
    )
}
