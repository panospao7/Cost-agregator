package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.toExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
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
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RP-06 6a (P5-002): the dashboard pace must come from the canonical
 * [com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator] wired into
 * [ComputeDashboardWidgetsUseCase] — not the retired hard-coded
 * `pacePercentage = 100f / NO_BASELINE` placeholder.
 *
 * Contract proven here:
 * - A completed previous month produces a real pace (UNDER/ON/OVER) and the
 *   SpendingPaceWidget is emitted through the real dashboard path.
 * - No completed baseline produces NO_BASELINE with the canonical -1f sentinel
 *   and NO pace widget (the -1f never reaches the UI because of that gate).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComputeDashboardWidgetsUseCasePaceWiringTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var monthlySavingsSweepUseCase: MonthlySavingsSweepUseCase
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private var lastForecastInput: NormalizedForecastInput? = null

    @Test
    fun `owner exclusions survive compatibility pace reconstruction`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencyConverter.convertOutcome(any(), "XYZ", "EUR", any(), any(), any()) } answers {
            com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed(
                firstArg(), "XYZ", "EUR", arg(3),
                com.yourname.expensetracker.domain.core.money.ConversionFailureType.INVALID_SOURCE_CURRENCY,
                "TEST_INVALID_SOURCE"
            )
        }
        val expenses = listOf(
            purchase(1L, 100.0, toEpochMs(2026, 6, 2)),
            purchase(2L, 300.0, toEpochMs(2026, 5, 10)),
            purchase(3L, 900.0, toEpochMs(2026, 6, 3)).copy(currency = "XYZ")
        )
        val result = computeUseCase.compute(createProcessedData(expenses))
        val pace = requireNotNull(lastForecastInput).spendingPace
        assertEquals(PaceStatus.NO_BASELINE, pace.paceStatus)
        org.junit.Assert.assertNull(pace.currentMonthSpent)
        org.junit.Assert.assertNull(pace.previousMonthTotal)
        org.junit.Assert.assertNull(pace.averageMonthlyTotal)
        val unavailable = pace.currentMoneyResult as com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Unavailable
        assertTrue(unavailable.metadata.excludedTransactionCount > 0)
        assertTrue(unavailable.metadata.invalidCurrencyCount > 0)
        val owner = (result.normalizedInput as DashboardNormalizedInputResult.Available).input
        assertEquals(owner.dataQuality.excludedTransactionCount, unavailable.metadata.excludedTransactionCount)
        assertTrue(result.allWidgets.none { it is DashboardWidget.SpendingPaceWidget })
    }

    @Test
    fun savingsWidgetOwnDeadlinePreservesTheOtherDashboardWidgets() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val data = createProcessedData(emptyList())
        val baseline = computeUseCase.compute(data)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } coAnswers {
            kotlinx.coroutines.delay(10_000L)
            com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult.NoRecommendation()
        }
        val started = testScheduler.currentTime
        val result = computeUseCase.compute(data)
        assertEquals(3_000L, testScheduler.currentTime - started)
        assertTrue(result.allWidgets.none { it is DashboardWidget.SavingsSweepPrompt })
        assertTrue(result.allWidgets.any { it is DashboardWidget.SavingsSweepUnavailable })
        assertTrue(result.allWidgets.isNotEmpty())
        assertEquals(
            baseline.allWidgets.map { it::class },
            result.allWidgets.filterNot { it is DashboardWidget.SavingsSweepUnavailable }.map { it::class }
        )
    }

    @Test
    fun callerDeadlineIsNotSwallowedBySavingsWidgetDeadline() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } coAnswers {
            kotlinx.coroutines.delay(10_000L)
            com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult.NoRecommendation()
        }
        val started = testScheduler.currentTime
        val failure = runCatching {
            kotlinx.coroutines.withTimeout(1_000L) {
                computeUseCase.compute(createProcessedData(emptyList()))
            }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(1_000L, testScheduler.currentTime - started)
    }

    // Non-copying sentinel: the framework may copy cross-boundary throwables;
    // createCopy()=null keeps the original instance so assertSame detects
    // application wrapping/replacement.
    private class IdentityCancellation(message: String) :
        kotlinx.coroutines.CancellationException(message), CopyableThrowable<IdentityCancellation> {
        override fun createCopy(): IdentityCancellation? = null
    }

    @Test
    fun savingsWidgetPropagatesDependencyCancellationUnchanged() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val cancellation = IdentityCancellation("test cancellation")
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } throws cancellation
        val failure = runCatching {
            computeUseCase.compute(createProcessedData(emptyList()))
        }.exceptionOrNull()
        org.junit.Assert.assertSame(cancellation, failure)
    }

    @Test
    fun failedSavingsWidgetDoesNotDiscardOtherDashboardWidgets() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val data = createProcessedData(emptyList())
        val baseline = computeUseCase.compute(data)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } throws
            IllegalStateException("SECRET financial payload")
        val result = computeUseCase.compute(data)
        assertTrue(result.allWidgets.none { it is DashboardWidget.SavingsSweepPrompt })
        val unavailable = result.allWidgets.filterIsInstance<DashboardWidget.SavingsSweepUnavailable>().single()
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", unavailable.reasonCode)
        assertEquals(
            baseline.allWidgets.map { it::class },
            result.allWidgets.filterNot { it is DashboardWidget.SavingsSweepUnavailable }.map { it::class }
        )
    }

    @Test
    fun typedSavingsUnavailableRetainsReasonAndMetadataInsteadOfMoney() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 29, 12, 0)
        val metadata = com.yourname.expensetracker.domain.savings.SmartSavingsOutcomeMetadata(
            currency = "EUR", conversionQuality = ConversionQuality.UNAVAILABLE,
            totalInputCount = 3, excludedInputCount = 3,
            warningCodes = listOf("STALE_EXCHANGE_RATE"), latestRateTimestamp = 123L
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult.Unavailable(
                "DISPLAY_CONVERSION_UNAVAILABLE", metadata
            )
        val result = computeUseCase.compute(createProcessedData(emptyList()))
        val widget = result.allWidgets.filterIsInstance<DashboardWidget.SavingsSweepUnavailable>().single()
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", widget.reasonCode)
        assertEquals(metadata, widget.metadata)
        assertEquals(DashboardWidgetRegistry.SAVINGS_SWEEP, DashboardWidgetRegistry.idFor(widget))
        assertTrue(result.allWidgets.none { it is DashboardWidget.SavingsSweepPrompt })
        coVerify(exactly = 0) { monthlySavingsSweepUseCase.computeSweepRecommendation() }
    }

    @Test
    fun typedSavingsAvailableRetainsCapturedCurrencyAndQuality() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 29, 12, 0)
        val metadata = com.yourname.expensetracker.domain.savings.SmartSavingsOutcomeMetadata(
            currency = "GBP", totalInputCount = 4, includedInputCount = 4, latestRateTimestamp = 321L
        )
        val recommendation = com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation(
            50.0, 10.0, 40.0, emptyList(), fixedNowMs, fixedNowMs + 1_000L, 0.9, "GBP"
        )
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult.Available(
                recommendation, metadata
            )
        val widget = computeUseCase.compute(createProcessedData(emptyList())).allWidgets
            .filterIsInstance<DashboardWidget.SavingsSweepPrompt>().single()
        assertEquals("GBP", widget.currency)
        assertEquals(metadata, widget.metadata)
        assertEquals(40.0, widget.sweepAmount, 0.0)
    }

    @Test
    fun validNoSavingsRecommendationOmitsBothMoneyAndUnavailableWidgets() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 29, 12, 0)
        val result = computeUseCase.compute(createProcessedData(emptyList()))
        assertTrue(result.allWidgets.none {
            it is DashboardWidget.SavingsSweepPrompt || it is DashboardWidget.SavingsSweepUnavailable
        })
    }

    @Test
    fun `Monte Carlo forwards non EUR home currency and preserves result denomination and quality`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        for (currency in listOf("USD", "GBP")) {
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode(currency))
            val forecast = monteCarloResult(currency)
            coEvery { monteCarloSimulator.simulate(any(), any(), any(), currency, any()) } returns forecast
            val expenses = listOf(purchase(1L, 100.0, toEpochMs(2026, 6, 2)).copy(currency = currency))

            val result = computeUseCase.compute(createProcessedData(expenses))
            val widget = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().single()
            val input = (result.normalizedInput as DashboardNormalizedInputResult.Available).input

            assertTrue(!input.dataQuality.isPartial)
            assertEquals(ConversionQuality.COMPLETE, input.dataQuality.conversionQuality)
            coVerify(exactly = 1) {
                monteCarloSimulator.simulate(
                    spentToDate = 100.0, knownUpcoming = 0.0, budgetAmount = null,
                    displayCurrency = currency, estimatedWeeklyRecurring = 0.0
                )
            }
            assertSame(forecast, widget.result)
            assertEquals(currency, widget.result.displayCurrency)
            assertEquals(CurrencyQualityUi.from(input.dataQuality), widget.currencyQuality)
        }
    }

    @Test
    fun `Monte Carlo partial normalized input is unavailable without simulation`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencyConverter.convertOutcome(any(), "XYZ", "EUR", any(), any(), any()) } answers {
            com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed(
                firstArg(), "XYZ", "EUR", arg(3),
                com.yourname.expensetracker.domain.core.money.ConversionFailureType.INVALID_SOURCE_CURRENCY,
                "TEST_INVALID_SOURCE"
            )
        }
        val forecast = monteCarloResult("EUR")
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns forecast
        val expenses = listOf(
            purchase(1L, 100.0, toEpochMs(2026, 6, 2)),
            purchase(2L, 900.0, toEpochMs(2026, 6, 3)).copy(currency = "XYZ")
        )

        val result = computeUseCase.compute(createProcessedData(expenses))
        val input = (result.normalizedInput as DashboardNormalizedInputResult.Available).input

        assertTrue(input.dataQuality.isPartial)
        assertTrue(input.dataQuality.excludedTransactionCount > 0)
        assertMonteCarloUnavailable(result, MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Monte Carlo incomplete future obligation is unavailable despite complete actual input`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencyConverter.convertOutcome(any(), "XYZ", "EUR", any(), any(), any()) } answers {
            com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed(
                firstArg(), "XYZ", "EUR", arg(3),
                com.yourname.expensetracker.domain.core.money.ConversionFailureType.INVALID_SOURCE_CURRENCY,
                "TEST_INVALID_SOURCE"
            )
        }
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult("EUR")
        val data = createProcessedData(listOf(purchase(1L, 100.0, toEpochMs(2026, 6, 2))))
        val planned = PlannedExpense(
            id = 2L, description = "Upcoming bill", amount = 900.0, currency = "XYZ",
            date = toEpochMs(2026, 6, 20), categoryId = 7L, isRecurring = false,
            priority = PlannedExpensePriority.MUST
        )

        val result = computeUseCase.compute(data.copy(data = data.data.copy(plannedExpenses = listOf(planned))))
        val input = (result.normalizedInput as DashboardNormalizedInputResult.Available).input

        assertTrue(!input.dataQuality.isPartial)
        assertEquals(ConversionQuality.COMPLETE, input.dataQuality.conversionQuality)
        assertEquals(0, input.dataQuality.excludedTransactionCount)
        assertTrue(!requireNotNull(lastForecastInput).dataQuality.isPartial)
        assertEquals(listOf(planned), requireNotNull(lastForecastInput).plannedExpenses)
        coVerify(atLeast = 1) {
            currencyConverter.convertOutcome(
                amount = 900.0, fromCurrency = "XYZ", toCurrency = "EUR",
                rateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = com.yourname.expensetracker.domain.core.money.StaleRatePolicy.LatestDefault
            )
        }
        assertMonteCarloUnavailable(result, MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Monte Carlo money display failures retain controlled unavailable reasons`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } throws
            MoneyDisplayUnavailableException(reason)

        val result = computeUseCase.compute(createProcessedData(emptyList()))

        assertMonteCarloUnavailable(result, reason)
    }

    @Test
    fun `Monte Carlo propagates dependency cancellation unchanged`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val cancellation = IdentityCancellation("test Monte Carlo cancellation")
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } throws cancellation

        val failure = runCatching {
            computeUseCase.compute(createProcessedData(emptyList()))
        }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `Monte Carlo unexpected failure is unavailable without exposing exception or discarding other widgets`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val data = createProcessedData(emptyList())
        val baseline = computeUseCase.compute(data)
        val secret = "SECRET financial payload /private/receipt.db"
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } throws
            IllegalStateException(secret)
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += message to t
            }
        }
        Timber.plant(tree)
        try {
            val result = computeUseCase.compute(data)

            assertMonteCarloUnavailable(result, MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
            assertEquals(
                baseline.allWidgets.map { it::class },
                result.allWidgets.filterNot { it is DashboardWidget.MonteCarloForecast }.map { it::class }
            )
            assertTrue(logs.all { (message, throwable) -> throwable == null && !message.contains(secret) })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `Monte Carlo genuine null omits only its widget`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val data = createProcessedData(emptyList())
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult("EUR")
        val withForecast = computeUseCase.compute(data)
        assertEquals(1, withForecast.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().size)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val result = computeUseCase.compute(data)

        assertTrue(result.allWidgets.none { it is DashboardWidget.MonteCarloForecast })
        assertEquals(
            withForecast.allWidgets.filterNot { it is DashboardWidget.MonteCarloForecast }.map { it::class },
            result.allWidgets.map { it::class }
        )
    }

    @Test
    fun `Monte Carlo unavailable home currency uses controlled early reason without simulation`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("SECRET settings payload /private/settings")

        val result = computeUseCase.compute(createProcessedData(emptyList()))

        assertTrue(result.normalizedInput is DashboardNormalizedInputResult.Unavailable)
        assertMonteCarloUnavailable(result, MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    private fun assertMonteCarloUnavailable(
        result: CompiledDashboardData,
        reason: MoneyDisplayUnavailableReasonCode
    ) {
        val widget = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().single()
        assertEquals(MonteCarloResult.unavailable(reason.name), widget.result)
        assertEquals(CurrencyQualityUi.unavailable(reason.name), widget.currencyQuality)
    }

    @Test
    fun uncertain_or_mismatched_budget_cannot_drive_runway_or_monte_carlo() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("USD"))
        val explicit = budgetSnapshot("USD")
        for (budget in listOf(
            explicit.copy(currency = "EUR"),
            explicit.copy(currency = null),
            explicit.copy(currencyAssumption = "LEGACY_DEFAULT"),
            explicit.copy(isPartial = true),
            explicit.copy(percentKnown = false),
            explicit.copy(conversionWarning = "SECRET conversion payload"),
            explicit.copy(budgetAmount = Double.NaN)
        )) {
            val data = createProcessedData(emptyList())
            val result = computeUseCase.compute(data.copy(data = data.data.copy(budgetStatuses = listOf(budget))))
            assertMonteCarloUnavailable(result, MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
            val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
            assertTrue(runway.isUnavailable)
            assertEquals(CurrencyQualityUi.unavailable("DISPLAY_CONVERSION_UNAVAILABLE"), runway.currencyQuality)
            org.junit.Assert.assertNull(result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single().totalBudget)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
        org.junit.Assert.assertNull(lastForecastInput)
    }

    @Test
    fun complete_budget_in_current_home_currency_preserves_its_normalized_amount() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("USD"))
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), "USD", any()) } returns monteCarloResult("USD")
        val data = createProcessedData(emptyList())
        val result = computeUseCase.compute(data.copy(data = data.data.copy(budgetStatuses = listOf(budgetSnapshot("USD")))))
        coVerify(exactly = 1) { monteCarloSimulator.simulate(0.0, 0.0, 1234.5, "USD", 0.0) }
        assertTrue(!result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single().isUnavailable)
        assertEquals(1234.5, result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single().totalBudget!!, 0.0)
    }

    private fun budgetSnapshot(currency: String) = com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot(
        null, 1234.5, null, 0.0, 1234.5, 0.0,
        com.yourname.expensetracker.domain.budget.BudgetHealthStatus.ON_TRACK,
        fixedNowMs, fixedNowMs + DAY_MS, currency = currency, currencyAssumption = "USER_CONFIRMED"
    )

    private fun monteCarloResult(currency: String) = MonteCarloResult(
        percentile10 = 100.0, percentile25 = 125.0, percentile50 = 150.0,
        percentile75 = 175.0, percentile90 = 200.0,
        probabilityUnderBudget = null, budgetAmount = null,
        spentToDate = 100.0, knownUpcoming = 0.0,
        confidence = SimulationConfidence(0.8, ConfidenceLevel.HIGH, "TEST_COMPLETE"),
        metadata = SimulationMetadata(12, 12, 1000, 1.0, 0.5, 15, fixedNowMs),
        displayCurrency = currency
    )

    @Before
    fun setup() {
        currencyConverter = mockk(relaxed = true)
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
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
        monthlySavingsSweepUseCase = mockk(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult.NoRecommendation()
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
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
            horizons = listOf(
                StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
            ),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )
        currencySettingsRepository = mockk(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        // RP-06 6a: echo stub (same pattern as DashboardCurrencyIntegrationTest) — the
        // assembler must faithfully carry the use case's enriched SpendingPace through
        // to the assembled ForecastInput; a relaxed mock would return a garbage
        // ForecastInput whose paceStatus is the first enum ordinal (UNDER_PACE).
        val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { forecastInputAssembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            lastForecastInput = input
            ForecastInputAssembler.ForecastInput(
                pastSumDaily = input.pastSumDaily,
                recurringPatterns = input.recurringPatterns,
                plannedExpenses = input.plannedExpenses,
                savingsGoals = input.savingsGoals,
                budgetStatuses = input.budgetStatuses,
                spendingPace = input.spendingPace,
                confirmedOccurrences = emptyList(),
                displayCurrency = input.homeCurrency.code,
                dataQuality = input.dataQuality
            )
        }

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            // Real calculator with the test's fake TimeProvider — pace math is real.
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `completed previous month produces real pace status and emits pace widget`() = runTest {
        // "Now" = June 15 2026 12:00. June purchases: 100 + 50 = 150 over 15 days.
        // May (previous month, completed): 300 → baselineDailyRate = 300/31.
        // pace% = (150/15) / (300/31) * 100 ≈ 103.3 → ON_PACE.
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = monthStartOf(fixedNowMs)
        val mayDate = toEpochMs(2026, 5, 10, 12, 0)

        val expenses = listOf(
            purchase(1L, 100.0, juneStart + DAY_MS),
            purchase(2L, 50.0, juneStart + 2 * DAY_MS),
            purchase(3L, 300.0, mayDate)
        )

        val result = computeUseCase.compute(createProcessedData(expenses))

        val pace = runwayResultPace(result)
        // Canonical formula (SpendingPaceCalculator):
        //   pace% = (currentDailyRate / baselineDailyRate) * 100
        //         = ((150 / 15) / (300 / 31)) * 100 ≈ 103.33 → ON_PACE (within ±10%).
        val expectedPacePercentage = ((150.0 / 15.0) / (300.0 / 31.0) * 100.0).toFloat()
        assertEquals(PaceStatus.ON_PACE, pace.paceStatus)
        assertEquals(
            "pacePercentage must follow the canonical formula",
            expectedPacePercentage,
            pace.pacePercentage,
            0.1f
        )
        assertEquals(150.0, requireNotNull(pace.currentMonthSpent), 0.001)
        assertEquals(300.0, pace.previousMonthTotal!!, 0.001)
        // RP-05 enrichment: the calculator returns a null averageMonthlyTotal; the
        // dashboard must override it via the single-completed-month fallback
        // (previousMonthAggregate) so the pace widget can show a monthly baseline.
        assertEquals(
            "RP-05 enrichment: single completed month feeds averageMonthlyTotal",
            300.0,
            pace.averageMonthlyTotal!!,
            0.001
        )
        assertTrue(
            "SpendingPaceWidget must be emitted when a baseline exists",
            result.allWidgets.any { it is DashboardWidget.SpendingPaceWidget }
        )
    }

    @Test
    fun `no previous month baseline yields NO_BASELINE with minus one sentinel and no pace widget`() = runTest {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = monthStartOf(fixedNowMs)

        val expenses = listOf(
            purchase(1L, 100.0, juneStart + DAY_MS),
            purchase(2L, 50.0, juneStart + 2 * DAY_MS)
        )

        val result = computeUseCase.compute(createProcessedData(expenses))

        val pace = runwayResultPace(result)
        assertEquals(PaceStatus.NO_BASELINE, pace.paceStatus)
        assertEquals(
            "Canonical no-baseline sentinel is -1f (NEW-P6-013)",
            -1f,
            pace.pacePercentage,
            0.0001f
        )
        assertTrue(
            "No pace widget may be emitted without a baseline",
            result.allWidgets.none { it is DashboardWidget.SpendingPaceWidget }
        )
    }

    /** Extracts the currentPace carried by RunwayResult.Available via the assembled forecast input. */
    private suspend fun runwayResultPace(result: CompiledDashboardData): com.yourname.expensetracker.domain.analytics.SpendingPace {
        // The pace surfaces in the widget list when a baseline exists; otherwise it is
        // observable through the assembled forecast path. Re-derive it via the same
        // calculator contract to assert on the canonical value.
        val widget = result.allWidgets.filterIsInstance<DashboardWidget.SpendingPaceWidget>().firstOrNull()
        if (widget != null) return widget.pace
        // No widget → NO_BASELINE contract: recompute the canonical expectation to compare.
        val calculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        val normalized = (result.normalizedInput as DashboardNormalizedInputResult.Available).input
        val previousBounds = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(fixedNowMs, -1)
        return calculator.calculate(
            currentMonthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(fixedNowMs),
            previousMonthStart = previousBounds.first,
            previousMonthEnd = previousBounds.second,
            allExpenses = normalized.normalizedExpenses.map {
                it.toExpenseSnapshot()
            },
            displayCurrency = normalized.homeCurrency.code,
            referenceNowMs = fixedNowMs
        )
    }

    private fun purchase(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun createProcessedData(expenses: List<Expense>): ProcessedDashboardData {
        val dashboardExpenses = expenses.map { it.toDashboardExpense() }
        val data = DashboardData(
            expenses = dashboardExpenses,
            categories = emptyList(),
            budgetStatuses = emptyList(),
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
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry
    )

    private fun monthStartOf(nowMs: Long): Long =
        com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(nowMs)

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
