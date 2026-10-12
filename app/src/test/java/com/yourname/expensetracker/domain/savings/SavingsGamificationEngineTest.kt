package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
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
        io.mockk.every { currencySettingsRepository.homeCurrency() } returns flowOf(CurrencyCode.EUR.code)

        engine = SavingsGamificationEngine(
            savingsGoalRepository = savingsGoalRepository,
            contributionHistoryRepository = contributionHistoryRepository,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
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
        assertNull(streak.totalContributedThisMonth)
    }

    @Test
    fun `calculateStreak aggregates current month contributions with explicit currency`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        contributionHistoryRepository.recordContribution(
            goalId = 1L,
            amount = 15.0,
            timestamp = FakeTimeProvider.forDate(2026, 4, 1, 9, 0).now(),
            currency = CurrencyCode.EUR.code
        )
        contributionHistoryRepository.recordContribution(
            goalId = 1L,
            amount = 20.0,
            timestamp = FakeTimeProvider.forDate(2026, 4, 1, 20, 0).now(),
            currency = CurrencyCode.EUR.code
        )

        val streak = engine.calculateStreak()

        assertApproxEquals(35.0, streak.totalContributedThisMonth ?: Double.NaN, 0.0001)
        assertNotNull(streak.totalContributedThisMonthResult)
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
    fun `corrupt contribution history makes streak unavailable instead of zero`() = runTest {
        val history = mockk<SavingsContributionHistoryRepository>()
        io.mockk.coEvery { history.getAllContributions() } throws
            com.yourname.expensetracker.data.repository.SavingsContributionHistoryUnavailableException()
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        val tested = SavingsGamificationEngine(
            savingsGoalRepository, history, timeProvider, analyticsCurrencyNormalizer,
            currencySettingsRepository, mockk(relaxed = true)
        )

        val streak = tested.calculateStreak()

        assertFalse(streak.isAvailable)
        assertNull(streak.totalContributedThisMonth)
        assertTrue(streak.totalContributedThisMonthResult is MoneyAggregateResult.Unavailable)
    }

    @Test
    fun `corrupt contribution history makes streak achievement unavailable`() = runTest {
        val history = mockk<SavingsContributionHistoryRepository>()
        io.mockk.coEvery { history.getAllContributions() } throws
            com.yourname.expensetracker.data.repository.SavingsContributionHistoryUnavailableException()
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        val tested = SavingsGamificationEngine(
            savingsGoalRepository, history, timeProvider, analyticsCurrencyNormalizer,
            currencySettingsRepository, mockk(relaxed = true)
        )

        val result = achievement(tested.getAchievements(homeCurrency = "EUR"), "saving_streak_7")

        assertFalse(result.isAvailable)
        assertFalse(result.isUnlocked)
        assertEquals(0.0, result.progress, 0.0001)
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
        val at100 = engine.getAchievements(homeCurrency = "EUR")
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
        val at500 = engine.getAchievements(homeCurrency = "EUR")
        val at500Century = achievement(at500, "century_saver")
        val at500Thousand = achievement(at500, "thousand_saver")

        assertTrue(at500Century.isUnlocked)
        assertFalse(at500Thousand.isUnlocked)
        assertApproxEquals(0.5, at500Thousand.progress, 0.0001)

        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 12L, currentAmount = 1000.0, targetAmount = 2000.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
        )
        val at1000 = engine.getAchievements(homeCurrency = "EUR")
        val at1000Century = achievement(at1000, "century_saver")
        val at1000Thousand = achievement(at1000, "thousand_saver")

        assertTrue(at1000Century.isUnlocked)
        assertTrue(at1000Thousand.isUnlocked)
        assertNotNull(at1000Thousand.unlockedAt)
        assertApproxEquals(1.0, at1000Thousand.progress, 0.0001)
    }

    @Test
    fun `savings aggregation fails closed for legacy cross-currency goal balances`() = runTest {
        val result = engine.aggregateGoalBalances(
            goals = listOf(
                goal(
                    id = 50L,
                    currentAmount = 200.0,
                    targetAmount = 500.0,
                    createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()
                ).copy(currency = "USD", currencyAssumption = "LEGACY_DEFAULT")
            ),
            homeCurrencyCode = "EUR"
        )

        assertTrue(result is MoneyAggregateResult.Unavailable)
    }

    @Test
    fun `savings aggregation fails closed for same-home legacy goal balances`() = runTest {
        val result = engine.aggregateGoalBalances(
            goals = listOf(
                goal(
                    id = 52L,
                    currentAmount = 200.0,
                    targetAmount = 500.0,
                    createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()
                ).copy(currency = "EUR", currencyAssumption = "LEGACY_DEFAULT")
            ),
            homeCurrencyCode = "EUR"
        )

        assertTrue(result is MoneyAggregateResult.Unavailable)
    }

    @Test
    fun `currency-less legacy contributions do not unlock home-currency thresholds`() = runTest {
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(id = 51L, currentAmount = 0.0, targetAmount = 500.0, createdAt = FakeTimeProvider.forDate(2026, 3, 1).now()))
        )
        contributionHistoryRepository.recordContribution(
            goalId = 51L,
            amount = 150.0,
            timestamp = FakeTimeProvider.forDate(2026, 3, 10).now(),
            source = "legacy"
        )

        assertFalse(achievement(engine.getAchievements(homeCurrency = "EUR"), "century_saver").isUnlocked)
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

        val achievement = achievement(engine.getAchievements(homeCurrency = "EUR"), "saving_streak_7")

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

    @Test
    fun `retained contribution history converts at most linearly`() = runTest {
        val count = 5000
        val history = mockk<SavingsContributionHistoryRepository>()
        io.mockk.coEvery { history.getAllContributions() } returns List(count) { index ->
            com.yourname.expensetracker.data.repository.SavingsContributionEvent(
                1L, 0.001, timeProvider.now() + index, "manual", "USD"
            )
        }
        val converter = mockk<CurrencyConverter>()
        io.mockk.coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
            com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted(
                firstArg(), CurrencyCode.USD, firstArg<Double>() * 0.5, CurrencyCode.EUR,
                0.5, com.yourname.expensetracker.domain.core.money.RateBasis.TRANSACTION_DATE,
                arg<Long?>(4), null, "TEST", com.yourname.expensetracker.domain.core.money.ConversionPath.DIRECT
            )
        }
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(1L, 0.0, createdAt = timeProvider.now()))
        )
        val testedEngine = SavingsGamificationEngine(
            savingsGoalRepository, history, timeProvider, analyticsCurrencyNormalizer,
            currencySettingsRepository, converter
        )
        testedEngine.getAchievements(homeCurrency = "EUR")
        io.mockk.coVerify(exactly = count) {
            converter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `goal crusher chooses earliest completion rather than goal order`() = runTest {
        val early = FakeTimeProvider.forDate(2026, 3, 10).now()
        val late = FakeTimeProvider.forDate(2026, 3, 20).now()
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, 100.0, 100.0, early - 1000),
            goal(2L, 100.0, 100.0, early - 1000)
        ))
        contributionHistoryRepository.recordContribution(1L, 100.0, late, "manual", "EUR")
        contributionHistoryRepository.recordContribution(2L, 100.0, early, "manual", "EUR")
        assertEquals(early, achievement(engine.getAchievements(homeCurrency = "EUR"), "goal_crusher").unlockedAt)
    }

    @Test
    fun `completion stays in goal currency when historical and latest rates differ`() = runTest {
        val early = FakeTimeProvider.forDate(2026, 3, 10).now()
        val late = FakeTimeProvider.forDate(2026, 3, 20).now()
        val converter = mockk<CurrencyConverter>()
        io.mockk.coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            val basis = arg<RateBasis>(3)
            val rate = if (basis == RateBasis.LATEST_AVAILABLE) 2.0 else 0.5
            ConversionOutcome.Converted(amount, CurrencyCode.USD, amount * rate, CurrencyCode.EUR,
                rate, basis, arg<Long?>(4), null, "TEST", ConversionPath.DIRECT)
        }
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, 100.0, 100.0, early - 1000).copy(currency = "USD", currencyAssumption = "EXPLICIT")
        ))
        contributionHistoryRepository.recordContribution(1L, 50.0, early, "manual", "USD")
        contributionHistoryRepository.recordContribution(1L, 50.0, late, "manual", "USD")
        val testedEngine = SavingsGamificationEngine(savingsGoalRepository, contributionHistoryRepository,
            timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository, converter)
        assertEquals(late, achievement(testedEngine.getAchievements(homeCurrency = "EUR"), "goal_crusher").unlockedAt)
        io.mockk.coVerify(exactly = 3) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unresolved contribution prefix cannot fabricate milestone or completion dates`() = runTest {
        val early = FakeTimeProvider.forDate(2026, 3, 10).now()
        val late = FakeTimeProvider.forDate(2026, 3, 20).now()
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, 150.0, 100.0, early - 1000)
        ))
        contributionHistoryRepository.recordContribution(1L, 20.0, early, "legacy")
        contributionHistoryRepository.recordContribution(1L, 150.0, late, "manual", "EUR")
        val achievements = engine.getAchievements(homeCurrency = "EUR")
        assertTrue(achievement(achievements, "century_saver").isUnlocked)
        assertNull(achievement(achievements, "century_saver").unlockedAt)
        assertNull(achievement(achievements, "goal_crusher").unlockedAt)
    }

    @Test
    fun `monthly history retains historical basis rate dates and source currency`() = runTest {
        val timestamp = FakeTimeProvider.forDate(2026, 4, 1, 9, 0).now()
        val converter = mockk<CurrencyConverter>()
        io.mockk.coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            ConversionOutcome.Converted(amount, CurrencyCode.USD, amount * 0.5, CurrencyCode.EUR,
                0.5, RateBasis.TRANSACTION_DATE, arg<Long?>(4), null, "TEST", ConversionPath.DIRECT)
        }
        contributionHistoryRepository.recordContribution(1L, 20.0, timestamp, "manual", "USD")
        val testedEngine = SavingsGamificationEngine(savingsGoalRepository, contributionHistoryRepository,
            timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository, converter)
        val result = testedEngine.calculateStreak().totalContributedThisMonthResult as MoneyAggregateResult.Available
        assertEquals(10.0, result.aggregate.displayAmount, 0.0001)
        assertEquals(RateBasis.TRANSACTION_DATE, result.aggregate.requestedRateBasis)
        assertEquals(RateBasis.TRANSACTION_DATE, result.aggregate.actualRateBasis)
        assertEquals(setOf(CurrencyCode.USD), result.aggregate.metadata.sourceCurrencyCodes)
        assertEquals(setOf(timestamp), result.aggregate.metadata.rateValidDates)
    }

    @Test
    fun `third currency completion history is bounded by twice events plus portfolio valuation`() = runTest {
        val count = 5000
        val history = mockk<SavingsContributionHistoryRepository>()
        io.mockk.coEvery { history.getAllContributions() } returns List(count) { index ->
            com.yourname.expensetracker.data.repository.SavingsContributionEvent(
                1L, 0.001, timeProvider.now() + index, "manual", "USD")
        }
        val converter = mockk<CurrencyConverter>()
        io.mockk.coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            ConversionOutcome.Converted(amount, CurrencyCode(arg<String>(1)), amount * 0.5,
                CurrencyCode(arg<String>(2)), 0.5, arg(3), arg<Long?>(4), null, "TEST", ConversionPath.DIRECT)
        }
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, 1.0, 1.0, timeProvider.now()).copy(currency = "CHF", currencyAssumption = "EXPLICIT")
        ))
        val testedEngine = SavingsGamificationEngine(savingsGoalRepository, history, timeProvider,
            analyticsCurrencyNormalizer, currencySettingsRepository, converter)
        testedEngine.getAchievements(homeCurrency = "EUR")
        io.mockk.coVerify(exactly = count * 2 + 1) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing portfolio FX remains partial and cannot create a balance achievement`() = runTest {
        val converter = mockk<CurrencyConverter>()
        io.mockk.coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } returns
            ConversionOutcome.Failed(200.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
                com.yourname.expensetracker.domain.core.money.ConversionFailureType.MISSING_RATE, "TEST")
        val goals = listOf(goal(1L, 50.0, createdAt = timeProvider.now()),
            goal(2L, 200.0, createdAt = timeProvider.now()).copy(currency = "USD", currencyAssumption = "EXPLICIT"))
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(goals)
        val tested = SavingsGamificationEngine(savingsGoalRepository, contributionHistoryRepository,
            timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository, converter)
        val result = tested.aggregateGoalBalances(goals, "EUR") as MoneyAggregateResult.Available
        assertTrue(result.aggregate.isPartial)
        assertEquals(1, result.aggregate.metadata.excludedTransactionCount)
        val achievements = tested.getAchievements(homeCurrency = "EUR")
        assertFalse(achievement(achievements, "century_saver").isAvailable)
        assertFalse(achievement(achievements, "century_saver").isUnlocked)
        assertFalse(achievement(achievements, "goal_crusher").isAvailable)
    }

    @Test
    fun unknownProvenanceNeverMakesZeroBalancesOrMixedPortfoliosAvailable() = runTest {
        for (provenance in listOf("LEGACY_DEFAULT", "UNKNOWN", "", "ASSUMED_HOME_CURRENCY", "ASSUMED_LEGACY_EUR", "SECRET unknown")) {
            for (currency in listOf("EUR", "USD", "GBP")) {
                val unknown = goal(1L, 0.0, createdAt = timeProvider.now()).copy(
                    currency = currency, currencyAssumption = provenance
                )
                for (goals in listOf(listOf(unknown), listOf(
                    unknown, goal(2L, 150.0, createdAt = timeProvider.now())
                ))) {
                    val result = engine.aggregateGoalBalances(goals, "EUR") as MoneyAggregateResult.Unavailable
                    assertEquals("INVALID_SOURCE_CURRENCY", result.reason)
                    assertEquals(goals.size, result.metadata.excludedTransactionCount)
                    assertTrue(!result.toString().contains("SECRET"))
                }
            }
        }
    }

    @Test
    fun unknownCompletedGoalCannotUnlockMoneyThresholdsOrGoalCrusher() = runTest {
        val known = goal(1L, 150.0, 100.0, timeProvider.now())
        for (provenance in listOf("LEGACY_DEFAULT", "UNKNOWN", "ASSUMED_HOME_CURRENCY", "SECRET unknown")) {
            val unknown = goal(2L, 1000.0, 100.0, timeProvider.now()).copy(currencyAssumption = provenance)
            io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(known, unknown))
            val achievements = engine.getAchievements(homeCurrency = "EUR")
            for (id in listOf("century_saver", "thousand_saver", "goal_crusher")) {
                val achievement = achievement(achievements, id)
                assertFalse(achievement.isAvailable)
                assertFalse(achievement.isUnlocked)
                assertNull(achievement.unlockedAt)
            }
            // Goal creation is count-based, not a denomination-dependent award.
            assertTrue(achievement(achievements, "first_goal").isUnlocked)
        }
    }

    @Test
    fun explicitEurUsdGbpBalanceValuationsStayInRequestedUnits() = runTest {
        for (currency in listOf("EUR", "USD", "GBP")) {
            val goals = listOf(goal(1L, 150.0, createdAt = timeProvider.now()).copy(
                currency = currency, currencyAssumption = "EXPLICIT_HOME_CURRENCY"
            ))
            val result = engine.aggregateGoalBalances(goals, currency) as MoneyAggregateResult.Available
            assertApproxEquals(150.0, result.aggregate.displayAmount)
            assertEquals(CurrencyCode(currency), result.aggregate.displayCurrency)
            io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(goals)
            assertTrue(achievement(engine.getAchievements(homeCurrency = currency), "century_saver").isUnlocked)
        }
    }

    @Test
    fun semanticallyCorruptPersistedEventsMakeHistoryDependentAwardsUnavailable() = runTest {
        val file = createStateFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val history = SavingsContributionHistoryRepository(store, timeProvider)
        val tested = SavingsGamificationEngine(savingsGoalRepository, history, timeProvider,
            analyticsCurrencyNormalizer, currencySettingsRepository, mockk(relaxed = true))
        io.mockk.every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, 1500.0, 1000.0, timeProvider.now())
        ))
        val valid = "{\"goalId\":1,\"amount\":100.0,\"timestamp\":${timeProvider.now()},\"source\":\"manual\",\"currency\":\"EUR\"}"
        val invalidEvents = listOf(
            "null", valid.replace("\"amount\":100.0", "\"amount\":0"),
            valid.replace("\"source\":\"manual\"", "\"source\":\"SECRET financial payload\""),
            valid.replace("\"currency\":\"EUR\"", "\"currency\":\"ZZZ\"")
        )
        for (invalid in invalidEvents) {
            val raw = "{\"events\":[$valid,$invalid]}"
            store.edit { it[stringPreferencesKey("savings_contribution_history_json")] = raw }
            val streak = tested.calculateStreak()
            assertFalse(streak.isAvailable)
            assertNull(streak.totalContributedThisMonth)
            val result = streak.totalContributedThisMonthResult as MoneyAggregateResult.Unavailable
            assertEquals("SAVINGS_HISTORY_CORRUPT", result.reason)
            val achievements = tested.getAchievements(homeCurrency = "EUR")
            for (id in listOf("saving_streak_7", "century_saver", "thousand_saver", "goal_crusher")) {
                val achievement = achievement(achievements, id)
                assertFalse(achievement.isAvailable)
                assertFalse(achievement.isUnlocked)
                assertNull(achievement.unlockedAt)
            }
            assertTrue(achievement(achievements, "first_goal").isUnlocked)
            assertEquals(raw, history.snapshotJson())
        }
    }

    @Test
    fun legacyPersistedEventWithoutCurrencyIsValidHistoryWithUnavailableMoney() = runTest {
        val file = createStateFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store.edit {
            it[stringPreferencesKey("savings_contribution_history_json")] =
                "{\"events\":[{\"goalId\":1,\"amount\":10.0,\"timestamp\":${timeProvider.now()}}]}"
        }
        val history = SavingsContributionHistoryRepository(store, timeProvider)
        val tested = SavingsGamificationEngine(savingsGoalRepository, history, timeProvider,
            analyticsCurrencyNormalizer, currencySettingsRepository, mockk(relaxed = true))
        val streak = tested.calculateStreak()
        assertTrue(streak.isAvailable)
        assertEquals(1, streak.currentStreakDays)
        assertEquals(1, streak.monthlyContributions)
        assertNull(streak.totalContributedThisMonth)
        assertEquals("INVALID_SOURCE_CURRENCY",
            (streak.totalContributedThisMonthResult as MoneyAggregateResult.Unavailable).reason)
        assertNull(history.getAllContributions().single().currency)
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
            currencyAssumption = "EXPLICIT",
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
