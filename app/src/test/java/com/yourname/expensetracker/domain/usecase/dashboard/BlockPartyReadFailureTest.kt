package com.yourname.expensetracker.domain.usecase.dashboard

import android.database.sqlite.SQLiteException
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastDataQuality
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Block Party read-failure contract (fix-1.0.4 block-party follow-up).
 *
 * A. A failed occurrence read inside [SynthesisEngine] (non-cancellation exception or a
 *    restore-barrier block) must not break Home: compute() returns, the rest of the dashboard
 *    compiles, Block Party is hidden. With and without an active manual rule.
 * B. CancellationException still propagates (DAO path and barrier path, engine and assembler).
 * C. The assembler's own failed read yields RECURRING_OCCURRENCES_UNAVAILABLE, which travels the
 *    REAL chain (ForecastInputAssembler.assembleNormalized -> SynthesisEngine.synthesize ->
 *    forecast.qualityWarnings) and hides the card even though the engine read succeeds. Unrelated
 *    warnings do not hide it (those use a hand-built ForecastInput, see the note on that test).
 * D. Normal path unchanged.
 * E. The log for a failed read carries the exception class name only.
 *
 * "Now" is 2026-06-15 12:00 in the JVM default zone (June has 30 days). Real [SynthesisEngine],
 * real [DatabaseReadBarrier] (over a mocked [RestoreMaintenanceMode]); only the converter and
 * peripheral use cases are mocks.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BlockPartyReadFailureTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }

    private var storedOccurrences: List<RecurringOccurrence> = emptyList()

    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var healthyDao: RecurringOccurrenceDao

    @Before
    fun setup() {
        fixedNowMs = at(15, 12)
        currencyConverter = mockk(relaxed = true)
        healthyDao = mockk(relaxed = true)
        coEvery { healthyDao.getByDateRange(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            storedOccurrences.filter { it.dueDate >= start && it.dueDate < end }
        }
    }

    // ------------------------------------------------ A: engine read fails, never throws

    @Test
    fun `A engine DAO SQLiteException with overall budget and NO active rule hides Block Party and compiles the rest`() = runTest {
        val dao = failingDao(SQLiteException("synthetic engine read failure"))
        val useCase = buildUseCase(engineDao = dao, engineBarrier = null, assembler = realAssembler(healthyDao, openBarrier()))

        val result = useCase.compute(data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0))))

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
        coVerify(atLeast = 1) { dao.getByDateRange(any(), any()) }
    }

    @Test
    fun `A engine DAO SQLiteException with an active manual rule hides Block Party and compiles the rest`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val dao = failingDao(SQLiteException("synthetic engine read failure"))
        val useCase = buildUseCase(engineDao = dao, engineBarrier = null, assembler = realAssembler(healthyDao, openBarrier()))

        val result = useCase.compute(
            data(
                patterns = listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7))),
                planned = listOf(projectedRow(30L, bill)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
        coVerify(atLeast = 1) { dao.getByDateRange(any(), any()) }
    }

    @Test
    fun `A engine restore barrier block with NO active rule hides Block Party without reading the DAO`() = runTest {
        val useCase = buildUseCase(
            engineDao = healthyDao,
            engineBarrier = blockedBarrier(),
            assembler = realAssembler(healthyDao, openBarrier())
        )

        val result = useCase.compute(data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0))))

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
        // The barrier gates the read: the DAO is never reached through the engine.
        coVerify(exactly = 0) { healthyDao.getByDateRange(any(), any()) }
    }

    @Test
    fun `A engine restore barrier block with an active manual rule hides Block Party and compiles the rest`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val useCase = buildUseCase(
            engineDao = healthyDao,
            engineBarrier = blockedBarrier(),
            assembler = realAssembler(healthyDao, openBarrier())
        )

        val result = useCase.compute(
            data(
                patterns = listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7))),
                planned = emptyList(),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
    }

    // ------------------------------------------------ B: cancellation propagates

    @Test
    fun `B engine DAO cancellation propagates through compute with an active manual rule`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val dao = failingDao(CancellationException("synthetic cancellation"))
        val useCase = buildUseCase(engineDao = dao, engineBarrier = null, assembler = realAssembler(healthyDao, openBarrier()))

        val thrown = captureThrown {
            useCase.compute(
                data(
                    patterns = listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7))),
                    planned = emptyList(),
                    budgets = listOf(overallBudget(3000.0))
                )
            )
        }

        assertTrue("CancellationException must propagate, was $thrown", thrown is CancellationException)
    }

    @Test
    fun `B engine barrier cancellation propagates through compute with no active rule`() = runTest {
        val barrier = mockk<DatabaseReadBarrier>()
        every { barrier.checkReadAllowed(any<String>()) } throws CancellationException("synthetic barrier cancellation")
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = barrier, assembler = realAssembler(healthyDao, openBarrier()))

        val thrown = captureThrown {
            useCase.compute(data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0))))
        }

        assertTrue("CancellationException must propagate, was $thrown", thrown is CancellationException)
    }

    @Test
    fun `B assembler DAO cancellation propagates through compute`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val assemblerDao = failingDao(CancellationException("synthetic cancellation"))
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = null, assembler = realAssembler(assemblerDao, openBarrier()))

        val thrown = captureThrown { useCase.compute(rentData(bill)) }

        assertTrue("CancellationException must propagate, was $thrown", thrown is CancellationException)
    }

    @Test
    fun `B assembler barrier cancellation propagates through compute`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val barrier = mockk<DatabaseReadBarrier>()
        every { barrier.checkReadAllowed(any<String>()) } throws CancellationException("synthetic barrier cancellation")
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = null, assembler = realAssembler(healthyDao, barrier))

        val thrown = captureThrown { useCase.compute(rentData(bill)) }

        assertTrue("CancellationException must propagate, was $thrown", thrown is CancellationException)
    }

    // ------------------------------------------------ C: assembler warning hides the card

    /**
     * Real chain, DAO failure: only the assembler's read fails (engine DAO healthy, so the engine
     * alone would draw 30 days). The bounded warning is produced by assembleNormalized, carried by
     * SynthesisEngine.synthesize into forecast.qualityWarnings, and computeBlockParty hides the card.
     * Fails if the hide rule is removed (the engine would then place the bill and return 30 days).
     */
    @Test
    fun `C assembler DAO failure alone hides Block Party through the real warning chain`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val assemblerDao = failingDao(SQLiteException("synthetic assembler read failure"))
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = null, assembler = realAssembler(assemblerDao, openBarrier()))

        val result = useCase.compute(rentData(bill))

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
        coVerify(atLeast = 1) { assemblerDao.getByDateRange(any(), any()) }
        // The engine read never runs: the assembler's warning alone hid the card.
        coVerify(exactly = 0) { healthyDao.getByDateRange(any(), any()) }
    }

    /** Real chain, restore barrier on the assembler only. */
    @Test
    fun `C assembler restore barrier block alone hides Block Party through the real warning chain`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val useCase = buildUseCase(
            engineDao = healthyDao,
            engineBarrier = null,
            assembler = realAssembler(healthyDao, blockedBarrier())
        )

        val result = useCase.compute(rentData(bill))

        assertBlockPartyHidden(result)
        assertRestOfDashboardCompiled(result)
    }

    /** The warning carried by the forecast is the bounded code, with no exception text. */
    @Test
    fun `C real assembler and synthesize carry the bounded warning into forecast qualityWarnings`() = runTest {
        val sentinel = "SENTINEL_SQL_DETAIL_55120"
        val bill = occurrence(1L, 20, 700.0, "Rent")
        val engine = SynthesisEngine(timeProvider, recurringOccurrenceDao = healthyDao, currencyConverter = currencyConverter)

        val failing = realAssembler(failingDao(IllegalStateException(sentinel)), openBarrier())
            .assembleNormalized(normalizedInput(listOf(projectedRow(30L, bill)), listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7)))))
        val failingForecast = engine.synthesize(failing)

        val warnings = failingForecast.qualityWarnings
        assertTrue("warnings: $warnings", warnings.any { it.startsWith("${ForecastInputAssembler.RECURRING_OCCURRENCES_UNAVAILABLE_CODE}:") })
        assertTrue("no exception text", warnings.none { it.contains(sentinel) })
        assertTrue(failingForecast.isPartial)

        storedOccurrences = listOf(bill)
        val healthy = realAssembler(healthyDao, openBarrier())
            .assembleNormalized(normalizedInput(listOf(projectedRow(30L, bill)), listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7)))))
        val healthyForecast = engine.synthesize(healthy)
        assertTrue(healthyForecast.qualityWarnings.none { it.contains(ForecastInputAssembler.RECURRING_OCCURRENCES_UNAVAILABLE_CODE) })
    }

    /**
     * Control on the same real chain: a healthy assembler read leaves the card shown, so the hide
     * in the tests above is caused by the warning only.
     */
    @Test
    fun `C control healthy assembler and engine reads show the card`() = runTest {
        val bill = occurrence(1L, 20, 700.0, "Rent")
        storedOccurrences = listOf(bill)
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = null, assembler = realAssembler(healthyDao, openBarrier()))

        val days = blockDays(useCase.compute(rentData(bill)))

        assertEquals(30, days.size)
    }

    /**
     * NOTE: hand-built forecast. The ForecastInput comes from a mocked assembler (as in
     * ComputeDashboardWidgetsUseCaseBlockPartyTest) because the real assembler cannot be driven to
     * emit an unrelated conversion warning without a currency-conversion stub; the real
     * SynthesisEngine.synthesize still turns it into forecast.qualityWarnings. Engine read is healthy.
     * Unrelated partial state plus unrelated warnings must NOT hide the card.
     */
    @Test
    fun `C unrelated partial state and conversion warnings do not hide Block Party`() = runTest {
        val unrelated = listOf(
            "MISSING_EXCHANGE_RATE: synthetic rate missing",
            "Planned expenses in USD could not be converted to EUR",
            // Prefix boundary: the code must be the start of the warning, followed by a colon.
            "NOTE RECURRING_OCCURRENCES_UNAVAILABLE: mentioned mid-string",
            "RECURRING_OCCURRENCES_UNAVAILABLE_OTHER: different code"
        )
        val assembler = handBuiltAssembler(isPartial = true, warnings = unrelated)
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = null, assembler = assembler)

        val days = blockDays(useCase.compute(data(emptyList(), emptyList(), listOf(overallBudget(3000.0)))))

        assertEquals(30, days.size)
    }

    /** Partial with no warnings at all (the unrelated partial state alone) keeps the card. */
    @Test
    fun `C partial flag without any warning does not hide Block Party`() = runTest {
        val useCase = buildUseCase(
            engineDao = healthyDao,
            engineBarrier = null,
            assembler = handBuiltAssembler(isPartial = true, warnings = emptyList())
        )

        val days = blockDays(useCase.compute(data(emptyList(), emptyList(), listOf(overallBudget(3000.0)))))

        assertEquals(30, days.size)
    }

    /** Hand-built control: exactly the bounded code plus colon hides the card. */
    @Test
    fun `C hand-built warning with the exact code hides Block Party`() = runTest {
        val useCase = buildUseCase(
            engineDao = healthyDao,
            engineBarrier = null,
            assembler = handBuiltAssembler(
                isPartial = true,
                warnings = listOf("MISSING_EXCHANGE_RATE: unrelated", "RECURRING_OCCURRENCES_UNAVAILABLE: synthetic")
            )
        )

        assertBlockPartyHidden(useCase.compute(data(emptyList(), emptyList(), listOf(overallBudget(3000.0)))))
    }

    // ------------------------------------------------ D: normal path unchanged

    @Test
    fun `D successful read subtracts the PAID row and shows the card with the same target`() = runTest {
        val paid = occurrence(3L, 1, 800.0, "Rent", status = "PAID")
        storedOccurrences = listOf(paid)
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = openBarrier(), assembler = realAssembler(healthyDao, openBarrier()))

        val result = useCase.compute(
            data(
                patterns = listOf(pattern(3L, "Rent", 800.0, at(1, 0, month = 7))),
                planned = listOf(projectedRow(30L, paid).copy(status = "FULFILLED")),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach {
            assertEquals("base day ${it.dayOfMonth}", 2200.0 / 30.0, it.baseTarget, TOL)
            assertEquals("target day ${it.dayOfMonth}", 2200.0 / 30.0, it.targetBudget, TOL)
        }
        assertEquals(2200.0, days.sumOf { it.targetBudget }, TOL)
        assertEquals(1, result.allWidgets.filterIsInstance<DashboardWidget.BudgetBlockParty>().size)
        coVerify(atLeast = 1) { healthyDao.getByDateRange(any(), any()) }
    }

    @Test
    fun `D successful read with no active rule and nothing paid keeps the plain limit target`() = runTest {
        val useCase = buildUseCase(engineDao = healthyDao, engineBarrier = openBarrier(), assembler = realAssembler(healthyDao, openBarrier()))

        val days = blockDays(useCase.compute(data(emptyList(), emptyList(), listOf(overallBudget(3000.0)))))

        assertEquals(30, days.size)
        days.forEach { assertEquals(100.0, it.targetBudget, TOL) }
    }

    // ------------------------------------------------ E: log carries the class name only

    @Test
    fun `E failed read log carries the exception class name and no message`() = runTest {
        val sentinel = "SENTINEL_SECRET_PATH_77731"
        val logs = mutableListOf<Triple<Int, String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += Triple(priority, message, t)
            }
        }
        Timber.plant(tree)
        try {
            val dao = failingDao(IllegalStateException(sentinel))
            val useCase = buildUseCase(engineDao = dao, engineBarrier = null, assembler = realAssembler(healthyDao, openBarrier()))

            useCase.compute(data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0))))
        } finally {
            Timber.uproot(tree)
        }

        val failureLog = logs.firstOrNull { it.second.contains("block-party occurrence read failed") }
        assertNotNull("failure log expected, logs: ${logs.map { it.second }}", failureLog)
        assertTrue(failureLog!!.second, failureLog.second.contains("(IllegalStateException)"))
        assertNull("no throwable attached", failureLog.third)
        assertFalse("no exception text in any log", logs.any { it.second.contains(sentinel) || it.third != null })
    }

    // ------------------------------------------------ harness

    private suspend fun captureThrown(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    private fun assertBlockPartyHidden(result: CompiledDashboardData) {
        assertTrue("no Block Party days", blockDays(result).isEmpty())
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    /** The failure must not take the rest of Home with it. */
    private fun assertRestOfDashboardCompiled(result: CompiledDashboardData) {
        assertTrue("widgets compiled", result.allWidgets.isNotEmpty())
        assertEquals(1, result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().size)
    }

    private fun blockDays(result: CompiledDashboardData): List<DomainDayBudgetStatus> =
        result.allWidgets.filterIsInstance<DashboardWidget.BudgetBlockParty>().singleOrNull()?.days ?: emptyList()

    private fun failingDao(error: Throwable): RecurringOccurrenceDao {
        val dao = mockk<RecurringOccurrenceDao>(relaxed = true)
        coEvery { dao.getByDateRange(any(), any()) } throws error
        return dao
    }

    private fun openBarrier(): DatabaseReadBarrier {
        val mode = mockk<RestoreMaintenanceMode>()
        every { mode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        return DatabaseReadBarrier(mode)
    }

    private fun blockedBarrier(): DatabaseReadBarrier {
        val mode = mockk<RestoreMaintenanceMode>()
        every { mode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_STAGING
        return DatabaseReadBarrier(mode)
    }

    private fun realAssembler(dao: RecurringOccurrenceDao, barrier: DatabaseReadBarrier): ForecastInputAssembler {
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        coEvery { coordinator.projectOccurrences(any(), any(), any(), any()) } returns emptyList()
        return ForecastInputAssembler(
            timeProvider,
            analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            currencyConverter = currencyConverter,
            recurringLifecycleCoordinator = coordinator,
            recurringOccurrenceDao = dao,
            databaseReadBarrier = barrier
        )
    }

    /** Mocked assembler returning a hand-built ForecastInput with the given data-quality fields. */
    private fun handBuiltAssembler(isPartial: Boolean, warnings: List<String>): ForecastInputAssembler {
        val assembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { assembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            ForecastInputAssembler.ForecastInput(
                pastSumDaily = input.pastSumDaily,
                recurringPatterns = input.recurringPatterns,
                plannedExpenses = input.plannedExpenses,
                savingsGoals = input.savingsGoals,
                budgetStatuses = input.budgetStatuses,
                spendingPace = input.spendingPace,
                confirmedOccurrences = emptyList(),
                displayCurrency = input.homeCurrency.code,
                dataQuality = ForecastDataQuality(isPartial = isPartial, conversionWarnings = warnings)
            )
        }
        return assembler
    }

    private fun buildUseCase(
        engineDao: RecurringOccurrenceDao,
        engineBarrier: DatabaseReadBarrier?,
        assembler: ForecastInputAssembler
    ): ComputeDashboardWidgetsUseCase {
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50,
                savingsRateScore = 50,
                runwayScore = 50,
                budgetAdherenceScore = 50,
                billReliabilityScore = 50,
                factorContributions = emptyList(),
                trend = HealthTrend.STABLE,
                recommendation = null
            )
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.NoRecommendation()
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute(any()) } returns MoneyRadarData(
            urgencyScore = 0,
            urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        return ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(
                timeProvider,
                recurringOccurrenceDao = engineDao,
                currencyConverter = currencyConverter,
                databaseReadBarrier = engineBarrier
            ),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = assembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    private fun rentData(bill: RecurringOccurrence): ProcessedDashboardData = data(
        patterns = listOf(pattern(1L, "Rent", 700.0, at(20, 0, month = 7))),
        planned = listOf(projectedRow(30L, bill)),
        budgets = listOf(overallBudget(3000.0))
    )

    private fun overallBudget(amount: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = amount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = at(1, 0),
        periodEnd = at(30, 23),
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        // fix-1.0.4 fixture: effective spend and health equal the gross spentAmount / health above.
        effectiveSpend = 0.0,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    private fun pattern(id: Long, merchant: String, amount: Double, next: Long) = RecurringPattern(
        merchantName = merchant,
        averageAmount = amount,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = next,
        confidence = 1.0f,
        previousDates = emptyList(),
        id = id
    )

    private fun occurrence(
        ruleId: Long,
        day: Int,
        amount: Double,
        merchant: String,
        status: String = "PLANNED"
    ): RecurringOccurrence {
        val due = at(day, 0)
        val paid = status == "PAID"
        return RecurringOccurrence(
            sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
            sourceId = ruleId,
            occurrenceKey = "${RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE}|$ruleId|$due|MONTHLY",
            dueDate = due,
            status = status,
            expectedAmount = amount,
            expectedCurrency = "EUR",
            paidAmount = if (paid) amount else null,
            paidCurrency = if (paid) "EUR" else null,
            frequency = RecurrenceFrequency.MONTHLY.name,
            merchant = merchant,
            categoryId = null
        )
    }

    private fun projectedRow(id: Long, occ: RecurringOccurrence) = PlannedExpense(
        id = id,
        description = occ.merchant ?: "Recurring Expense",
        amount = occ.expectedAmount,
        currency = occ.expectedCurrency,
        date = occ.dueDate,
        categoryId = occ.categoryId,
        isRecurring = true,
        priority = PlannedExpensePriority.MUST,
        sourceOccurrenceKey = occ.occurrenceKey,
        status = "PLANNED",
        sourceRecurringRuleId = occ.sourceId
    )

    private fun normalizedInput(
        planned: List<PlannedExpense>,
        patterns: List<RecurringPattern>
    ) = NormalizedForecastInput(
        homeCurrency = CurrencyCode("EUR"),
        normalizedExpenses = emptyList(),
        pastSumDaily = emptyList(),
        recurringPatterns = patterns,
        plannedExpenses = planned,
        savingsGoals = emptyList(),
        budgetStatuses = emptyList(),
        spendingPace = SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 15,
            daysInMonth = 30,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = -1f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR"
        )
    )

    private fun data(
        patterns: List<RecurringPattern>,
        planned: List<PlannedExpense>,
        budgets: List<BudgetStatusSnapshot>
    ): ProcessedDashboardData {
        val dashboardData = DashboardData(
            expenses = emptyList(),
            categories = emptyList(),
            budgetStatuses = budgets,
            pendingCount = 0,
            weather = FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ),
            recurringPatterns = patterns,
            plannedExpenses = planned,
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 0
        )
        return ProcessedDashboardData(data = dashboardData, summary = summary, categoryBreakdown = emptyList())
    }

    /** Epoch millis of [month]/[day] 2026 at [hour]:00 in the JVM default zone (the zone the engine uses). */
    private fun at(day: Int, hour: Int, month: Int = 6): Long =
        LocalDateTime.of(2026, month, day, hour, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    companion object {
        private const val TOL = 1e-6
    }
}
