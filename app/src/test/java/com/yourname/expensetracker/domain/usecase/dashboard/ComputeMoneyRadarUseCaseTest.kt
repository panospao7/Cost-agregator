package com.yourname.expensetracker.domain.usecase.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.asString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P-05-5: the Money Radar never simulates. Its budget part is derived from a [RadarBudgetInput]
 * whose [RadarBudgetInput.Simulated] case carries the SAME Monte Carlo result object the Home card
 * shows. Mapping of budget state to the input (weekly, unreadable, unavailable card) is owned by
 * the widgets use case and pinned in BudgetSurfaceAgreementTest and HomeVerdictParityTest; this
 * class pins what the Radar does with each input.
 */
@RunWith(RobolectricTestRunner::class)
class ComputeMoneyRadarUseCaseTest {

    private lateinit var mergedRecurringPatternsProvider: MergedRecurringPatternsProvider
    private lateinit var context: Context
    private lateinit var anomalyAlertRepository: AnomalyAlertRepository
    private lateinit var getMonteCarloBudgetImpact: GetMonteCarloBudgetImpactUseCase
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var useCase: ComputeMoneyRadarUseCase

    private val now = 1_710_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    // ---------------------------------------------------------------- fail-closed required signals

    @Test
    fun failedRequiredSignalsNeverProduceHealthyZeroOrAdvice() = runTest {
        // source 2: the budget read failed (Unreadable). Before P-05-5 the Radar read the budget
        // repository itself; the failure now arrives as an input.
        for (source in 0..2) {
            stubHealthyRadarSignals()
            val failure = IllegalStateException("SECRET financial payload")
            var input: RadarBudgetInput = RadarBudgetInput.None
            when (source) {
                0 -> coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } throws failure
                1 -> coEvery { anomalyAlertRepository.getActiveAlerts() } throws failure
                2 -> input = RadarBudgetInput.Unreadable
            }
            assertRadarUnavailable(useCase.compute(input))
        }
    }

    @Test
    fun requiredSignalCancellationIsNotAnUnavailableResult() = runTest {
        // Sources 0 and 1 only: the budget flow cancellation is no longer read inside the Radar.
        for (source in 0..1) {
            stubHealthyRadarSignals()
            val cancellation = kotlinx.coroutines.CancellationException("test cancellation")
            when (source) {
                0 -> coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } throws cancellation
                1 -> coEvery { anomalyAlertRepository.getActiveAlerts() } throws cancellation
            }
            val failure = runCatching { useCase.compute(RadarBudgetInput.None) }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
        }
    }

    @Test
    fun unsuccessfulBudgetImpactIsUnavailableNotNoRisk() = runTest {
        stubHealthyRadarSignals()
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5)
        for (outcome in listOf(Result.Loading, Result.Duplicate, Result.Error(message = "SECRET payload"))) {
            every { getMonteCarloBudgetImpact(1000.0, simulation) } returns outcome
            assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(simulation)))
        }
    }

    @Test
    fun failedIncomeSignalDoesNotBecomeZeroIncome() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns
            listOf(recurring("Bill", 10.0, now + dayMs))
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } throws IllegalStateException("private")
        assertRadarUnavailable(useCase.compute(RadarBudgetInput.None))
    }

    private fun stubHealthyRadarSignals() {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
    }

    @Test
    fun `invalid home currency cannot make an existing budget look healthy`() = runTest {
        stubHealthyRadarSignals()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("")

        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(monteCarloResult(0.5))))
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    // ---------------------------------------------------------------- P-05-5 fail-closed inputs

    @Test
    fun `unreadable budget is UNAVAILABLE even with bills and anomalies and never reads impact`() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 20.0, now + 2 * dayMs),
            recurring("Bill C", 30.0, now + 3 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Charge", 40.0, now - dayMs),
            anomaly("Odd Charge 2", 41.0, now - 2 * dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Unreadable))
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `unknown budget with otherwise GREEN signals is UNAVAILABLE not All Clear`() = runTest {
        stubHealthyRadarSignals()

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertRadarUnavailable(result)
        assertNotEquals(UrgencyLevel.GREEN, result.urgencyLevel)
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    /**
     * Changed expectation (P-05-5): this fixture (2 bills + 1 anomaly, no budget risk) scores 21,
     * which is GREEN. With a known "no budget" (None) it stays a GREEN verdict; with an Unknown
     * budget part GREEN is not a verdict, so the Radar is UNAVAILABLE.
     */
    @Test
    fun `unknown budget with two bills and one anomaly scoring GREEN flips to UNAVAILABLE while None stays GREEN`() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 20.0, now + 2 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Charge", 40.0, now - dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val none = useCase.compute(RadarBudgetInput.None)
        val unknown = useCase.compute(RadarBudgetInput.Unknown)

        assertEquals(21, none.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, none.urgencyLevel)
        assertRadarUnavailable(unknown)
    }

    @Test
    fun `unknown budget with bills due stays non-GREEN and keeps bills and anomalies`() = runTest {
        stubHealthyRadarSignals()
        // 3 bills = 60 * 0.4 = 24, 1 anomaly = 30 * 0.3 = 9: score 33, YELLOW.
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 20.0, now + 2 * dayMs),
            recurring("Bill C", 30.0, now + 3 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Charge", 40.0, now - dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertEquals(33, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
        assertEquals(listOf("Bill A", "Bill B", "Bill C"), result.dueBills.map { it.merchant })
        assertEquals(listOf("Odd Charge"), result.anomalyAlerts.map { it.merchant })
        assertNull(result.budgetRisk)
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `boundary unknown budget scoring exactly 30 is still GREEN so UNAVAILABLE and 31 is kept`() = runTest {
        stubHealthyRadarSignals()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        // 2 anomalies = 60 * 0.3 = 18 and one bill = 30 * 0.4 = 12: exactly 30 (GREEN upper bound).
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd 1", 8.0, now - dayMs),
            anomaly("Odd 2", 9.0, now - 2 * dayMs)
        )
        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Unknown))

        // 3 bills (24) + 2 anomalies (18) = 42, above the GREEN bound.
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 10.0, now + 2 * dayMs),
            recurring("Bill C", 10.0, now + 3 * dayMs)
        )
        val kept = useCase.compute(RadarBudgetInput.Unknown)
        assertEquals(UrgencyLevel.YELLOW, kept.urgencyLevel)
        assertEquals(42, kept.urgencyScore)
    }

    @Test
    fun `simulated with non positive or non finite limit is treated as unknown`() = runTest {
        val badLimits = listOf(0.0, -50.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (limit in badLimits) {
            val simulation = monteCarloResult(probabilityUnderBudget = 0.5, budgetAmount = limit)

            // GREEN otherwise: Unknown semantics, so UNAVAILABLE (never None's GREEN "All Clear").
            stubHealthyRadarSignals()
            assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(simulation)))

            // Non-GREEN otherwise: kept, with no budget risk and no impact call.
            coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
                recurring("Bill A", 10.0, now + dayMs),
                recurring("Bill B", 10.0, now + 2 * dayMs),
                recurring("Bill C", 10.0, now + 3 * dayMs)
            )
            coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
                anomaly("Odd Charge", 40.0, now - dayMs)
            )
            coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
                expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
            )
            val result = useCase.compute(RadarBudgetInput.Simulated(simulation))
            assertEquals("limit $limit", UrgencyLevel.YELLOW, result.urgencyLevel)
            assertNull("limit $limit", result.budgetRisk)
        }
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `boundary simulated with a tiny positive limit is a real comparison not unknown`() = runTest {
        stubHealthyRadarSignals()
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5, budgetAmount = 0.01)
        every { getMonteCarloBudgetImpact(0.01, simulation) } returns Result.Success(
            MonteCarloBudgetImpact(0.01, 1000.0, 999.99, 0.5, RiskTier.CRITICAL)
        )

        val result = useCase.compute(RadarBudgetInput.Simulated(simulation))

        assertNotNull(result.budgetRisk)
        assertEquals(RiskTier.CRITICAL, result.budgetRisk!!.riskTier)
        assertNotEquals(UrgencyLevel.UNAVAILABLE, result.urgencyLevel)
    }

    @Test
    fun `simulated with no limit or the unavailable placeholder result is treated as unknown`() = runTest {
        stubHealthyRadarSignals()

        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(monteCarloResult(0.5, budgetAmount = null))))
        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(MonteCarloResult.unavailable("TEST"))))
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    // ---------------------------------------------------------------- single source: the card result

    @Test
    fun `budget risk is derived from the very result object passed in with its own limit`() = runTest {
        stubHealthyRadarSignals()
        // Rollover limit 600 carried by the result: the Radar compares with the result's limit.
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5, budgetAmount = 600.0)
        every { getMonteCarloBudgetImpact(600.0, simulation) } returns Result.Success(
            MonteCarloBudgetImpact(600.0, 600.0, 0.0, 0.5, RiskTier.MEDIUM)
        )

        val result = useCase.compute(RadarBudgetInput.Simulated(simulation))

        assertNotNull(result.budgetRisk)
        assertEquals(RiskTier.MEDIUM, result.budgetRisk!!.riskTier)
        verify(exactly = 1) { getMonteCarloBudgetImpact(600.0, match { it === simulation }) }
        verify(exactly = 1) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `mixed currency home USD simulated result gives a budget risk`() = runTest {
        stubHealthyRadarSignals()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5, displayCurrency = "USD")
        every { getMonteCarloBudgetImpact(1000.0, simulation) } returns Result.Success(
            MonteCarloBudgetImpact(1000.0, 1000.0, 0.0, 0.5, RiskTier.MEDIUM)
        )

        assertNotNull(useCase.compute(RadarBudgetInput.Simulated(simulation)).budgetRisk)
        verify(exactly = 1) { getMonteCarloBudgetImpact(1000.0, simulation) }
    }

    @Test
    fun `none input has no budget risk and never reads impact and is not unavailable`() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertNull(result.budgetRisk)
        assertNotEquals(UrgencyLevel.UNAVAILABLE, result.urgencyLevel)
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mergedRecurringPatternsProvider = mockk()
        anomalyAlertRepository = mockk()
        getMonteCarloBudgetImpact = mockk()
        expenseRepository = mockk()
        currencyConverter = mockk()
        currencySettingsRepository = mockk()
        timeProvider = mockk()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        useCase = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = mergedRecurringPatternsProvider,
            anomalyAlertRepository = anomalyAlertRepository,
            getMonteCarloBudgetImpact = getMonteCarloBudgetImpact,
            expenseRepository = expenseRepository,
            analyticsCurrencyNormalizer = AnalyticsCurrencyNormalizer(currencyConverter),
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider
        )

        every { timeProvider.now() } returns now
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
    }

    // ---------------------------------------------------------------- scoring (rebased on Simulated)

    @Test
    fun `compute applies weighted urgency factors and emits RED with critical budget CTA`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Rent", 600.0, now + dayMs),
            recurring("Internet", 50.0, now + 2 * dayMs),
            recurring("Gym", 40.0, now + 3 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Coffee", 8.0, now - dayMs),
            anomaly("Unknown Charge", 120.0, now - 2 * dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.20)
        val impact = MonteCarloBudgetImpact(
            budgetAmount = 1000.0,
            p50Forecast = 1150.0,
            expectedOverrun = 150.0,
            probabilityOfOverrun = 0.80,
            riskTier = RiskTier.CRITICAL
        )
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(impact)

        val result = useCase.compute(RadarBudgetInput.Simulated(mcResult))

        // Due bills: 3 with large-bill bonus => 80, anomalies: 2 => 60,
        // budget risk: probability 80 + magnitude 10 + critical 20 => capped at 100.
        // Weighted = 80*0.4 + 60*0.3 + 100*0.3 = 80
        assertEquals(80, result.urgencyScore)
        assertEquals(UrgencyLevel.RED, result.urgencyLevel)
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_BILLS_DUE_FORMAT,
                listOf(3, 7)
            )
        })
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_ANOMALIES_FORMAT,
                listOf(2)
            )
        })
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_CRITICAL_FORMAT,
                listOf(80)
            )
        })
        assertTrue(result.primaryCta is MoneyRadarAction.AdjustBudget)
    }

    @Test
    fun `compute includes only bills due within next seven days`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("DueToday", 20.0, now),
            recurring("DueInSeven", 30.0, now + 7 * dayMs),
            recurring("TooLate", 40.0, now + 8 * dayMs),
            recurring("AlreadyPast", 50.0, now - dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(listOf("DueToday", "DueInSeven"), result.dueBills.map { it.merchant })
        assertEquals(listOf(0, 7), result.dueBills.map { it.daysUntilDue })
    }

    @Test
    fun `compute includes recurring bill due earlier today as due today`() = runTest {
        val noonToday = millis(2026, java.util.Calendar.MARCH, 9, 12)
        val earlierToday = millis(2026, java.util.Calendar.MARCH, 9, 8)
        every { timeProvider.now() } returns noonToday
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Morning Bill", 20.0, earlierToday)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(listOf("Morning Bill"), result.dueBills.map { it.merchant })
        assertEquals(listOf(0), result.dueBills.map { it.daysUntilDue })
    }

    @Test
    fun `compute aggregates unresolved anomalies from last thirty days only`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Recent", 12.0, now - dayMs),
            anomaly("OlderButValid", 25.0, now - 10 * dayMs),
            anomaly("TooOld", 99.0, now - 40 * dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(2, result.anomalyAlerts.size)
        assertEquals(listOf("Recent", "OlderButValid"), result.anomalyAlerts.map { it.merchant })
        assertEquals(listOf(1, 10), result.anomalyAlerts.map { it.daysAgo })
    }

    /**
     * Rebased (P-05-5): this test used to assert the Radar passed spent-to-date, the 7-day recurring
     * sum and the limit to ITS OWN simulator. The Radar no longer simulates; it must hand the card's
     * result object to the impact use case unchanged, and the bills list stays the 7-day window.
     */
    @Test
    fun `compute hands the shared result object to the impact use case and keeps the seven day bill list`() = runTest {
        val r1 = recurring("Rent", 100.0, now + dayMs)
        val r2 = recurring("Insurance", 50.0, now + 5 * dayMs)
        val outside = recurring("LateBill", 200.0, now + 12 * dayMs)
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(r1, r2, outside)
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        val mcResult = monteCarloResult(probabilityUnderBudget = 0.6)
        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 980.0,
                expectedOverrun = 0.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.MEDIUM
            )
        )

        val result = useCase.compute(RadarBudgetInput.Simulated(mcResult))

        assertNotNull(result.budgetRisk)
        assertEquals(listOf("Rent", "Insurance"), result.dueBills.map { it.merchant })
        verify(exactly = 1) { getMonteCarloBudgetImpact(1000.0, match { it === mcResult }) }
    }

    @Test
    fun `compute ignores unconfirmed detected recurring suggestions`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()

        val result = useCase.compute(RadarBudgetInput.None)

        assertTrue(result.dueBills.isEmpty())
        assertEquals(0, result.urgencyScore)
        coVerify(exactly = 0) { mergedRecurringPatternsProvider.getPatterns() }
    }

    @Test
    fun `compute includes confirmed recurring obligations in money radar`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Confirmed Rent", 700.0, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(2_000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(listOf("Confirmed Rent"), result.dueBills.map { it.merchant })
        assertTrue(requireNotNull(result.urgencyScore) > 0)
    }

    @Test
    fun `compute includes recurring bill due earlier today in budget risk urgency path`() = runTest {
        val noonToday = millis(2026, java.util.Calendar.MARCH, 9, 12)
        val earlierToday = millis(2026, java.util.Calendar.MARCH, 9, 8)
        every { timeProvider.now() } returns noonToday
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Morning Bill", 120.0, earlierToday)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.4)
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1120.0,
                expectedOverrun = 120.0,
                probabilityOfOverrun = 0.6,
                riskTier = RiskTier.HIGH
            )
        )

        val result = useCase.compute(RadarBudgetInput.Simulated(mcResult))

        assertEquals(listOf("Morning Bill"), result.dueBills.map { it.merchant })
        assertNotNull(result.budgetRisk)
        // €120 is below the large-bill threshold (50% of €1,000 income), so
        // the due-bills large-bill bonus (27d85092a) does not apply.
        assertEquals(36, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_HIGH_FORMAT,
                listOf(60)
            )
        })
    }

    @Test
    fun `compute returns GREEN with healthy message when no bills alerts or budget`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(0, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
        assertEquals(emptyList<UpcomingBill>(), result.dueBills)
        assertEquals(emptyList<AnomalyAlertSummary>(), result.anomalyAlerts)
        assertNull(result.budgetRisk)
        assertEquals(
            listOf(UiText.MessageKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)),
            result.topReasons
        )
        assertNull(result.primaryCta)

        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `compute budget urgency changes with magnitude and risk tier`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        val mcResult = monteCarloResult(probabilityUnderBudget = 0.6)

        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1000.0,
                expectedOverrun = 5.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.LOW
            )
        ) andThen Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1300.0,
                expectedOverrun = 250.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.CRITICAL
            )
        )

        val lowRisk = useCase.compute(RadarBudgetInput.Simulated(mcResult))
        val highRisk = useCase.compute(RadarBudgetInput.Simulated(mcResult))

        assertTrue(requireNotNull(highRisk.urgencyScore) > requireNotNull(lowRisk.urgencyScore))
        assertTrue(highRisk.urgencyLevel >= lowRisk.urgencyLevel)
    }

    /** Returns the Radar input for a stubbed budget risk, or None when [tier] is null. */
    private fun stubBudgetRiskOnly(
        tier: RiskTier?,
        anomalies: List<AnomalyAlertRecord> = emptyList(),
        probabilityOfOverrun: Double = 0.80,
        expectedOverrun: Double = if (tier == RiskTier.CRITICAL) 150.0 else 0.0
    ): RadarBudgetInput {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns anomalies
        if (tier == null) return RadarBudgetInput.None
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        val mcResult = monteCarloResult(probabilityUnderBudget = 0.2)
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1150.0,
                expectedOverrun = expectedOverrun,
                probabilityOfOverrun = probabilityOfOverrun,
                riskTier = tier
            )
        )
        return RadarBudgetInput.Simulated(mcResult)
    }

    @Test
    fun `critical budget risk alone floors at 31 YELLOW and never RED`() = runTest {
        val input = stubBudgetRiskOnly(RiskTier.CRITICAL)

        val result = useCase.compute(input)

        // Budget part: min(100, 80+10+20) * 0.3 = 30, which would be GREEN without the floor.
        assertEquals(31, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
    }

    @Test
    fun `YELLOW cap holds for CRITICAL budget risk even with an extreme overrun and certain probability`() = runTest {
        val input = stubBudgetRiskOnly(RiskTier.CRITICAL, probabilityOfOverrun = 1.0, expectedOverrun = 5_000.0)

        val result = useCase.compute(input)

        // Budget part saturates at 100 * 0.3 = 30: the YELLOW floor (31) applies, never RED.
        assertEquals(31, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
        assertNotEquals(UrgencyLevel.RED, result.urgencyLevel)
        assertTrue(result.primaryCta is MoneyRadarAction.AdjustBudget)
    }

    @Test
    fun `high budget risk alone floors at 31 YELLOW`() = runTest {
        val input = stubBudgetRiskOnly(RiskTier.HIGH)

        val result = useCase.compute(input)

        // Budget part: (80+0+10) * 0.3 = 27, floored to 31.
        assertEquals(31, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
    }

    @Test
    fun `medium budget risk alone is not floored`() = runTest {
        val input = stubBudgetRiskOnly(RiskTier.MEDIUM)

        val result = useCase.compute(input)

        // Budget part: (80+0+5) * 0.3 = 25.
        assertEquals(25, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
    }

    @Test
    fun `weighted score above the floor is kept with critical budget risk`() = runTest {
        val input = stubBudgetRiskOnly(
            RiskTier.CRITICAL,
            anomalies = listOf(
                anomaly("Odd Coffee", 8.0, now - dayMs),
                anomaly("Unknown Charge", 120.0, now - 2 * dayMs)
            )
        )

        val result = useCase.compute(input)

        // Anomalies 60*0.3 = 18 plus budget 30 = 48, above the floor of 31.
        assertEquals(48, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
    }

    @Test
    fun `null budget risk is not floored`() = runTest {
        val input = stubBudgetRiskOnly(
            null,
            anomalies = listOf(
                anomaly("Odd Coffee", 8.0, now - dayMs),
                anomaly("Unknown Charge", 120.0, now - 2 * dayMs)
            )
        )

        val result = useCase.compute(input)

        assertNull(result.budgetRisk)
        assertEquals(18, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
    }

    @Test
    fun `compute formats string placeholder reason with scalar merchant arg`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Netflix", 19.99, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(
            "1 bill due soon: Netflix",
            result.topReasons.first().asString(context)
        )
    }

    @Test
    fun `compute uses merged recurring result to avoid duplicate stale bill inflation`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Netflix", 15.0, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.None)

        assertEquals(listOf("Netflix"), result.dueBills.map { it.merchant })
        assertApproxEquals(15.0, result.dueBills.single().amount, 0.0001)
        assertEquals(
            "1 bill due soon: Netflix",
            result.topReasons.first().asString(context)
        )
    }

    @Test
    fun `compute formats integer placeholder reasons with scalar numeric args`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Coffee Shop", 8.0, now - dayMs),
            anomaly("Gas Station", 60.0, now - 2 * dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.4)
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1100.0,
                expectedOverrun = 100.0,
                probabilityOfOverrun = 0.65,
                riskTier = RiskTier.HIGH
            )
        )

        val formattedReasons = useCase.compute(RadarBudgetInput.Simulated(mcResult))
            .topReasons.map { it.asString(context) }

        assertTrue(formattedReasons.contains("2 unusual charges need review"))
        assertTrue(formattedReasons.contains("High risk of exceeding budget (65%)"))
    }

    private fun recurring(merchant: String, amount: Double, date: Long): RecurringPattern {
        return RecurringPattern(
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = date,
            confidence = 0.9f,
            previousDates = emptyList(),
            categoryId = null,
            id = null
        )
    }

    private fun anomaly(merchant: String, amount: Double, alertedAt: Long): AnomalyAlertRecord {
        return AnomalyAlertRecord(
            merchant = merchant,
            amount = amount,
            anomalyReason = "Unexpected amount",
            alertedAt = alertedAt
        )
    }

    // ---------------------------------------------------------------- fix-1.0.4 null probability

    @Test
    fun `null probabilityUnderBudget gives no budget risk, no impact call, keeps bills and anomalies`() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 20.0, now + 2 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Charge", 40.0, now - dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(
            RadarBudgetInput.Simulated(monteCarloResult(probabilityUnderBudget = null, budgetAmount = 500.0))
        )

        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNotNull(result.urgencyScore)
        assertNull(result.budgetRisk)
        assertEquals(listOf("Bill A", "Bill B"), result.dueBills.map { it.merchant })
        assertEquals(listOf("Odd Charge"), result.anomalyAlerts.map { it.merchant })
        verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    @Test
    fun `impact Error with a non-null probability is still unavailable`() = runTest {
        stubHealthyRadarSignals()
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5, budgetAmount = 500.0)
        every { getMonteCarloBudgetImpact(500.0, simulation) } returns Result.Error(message = "SECRET payload")

        assertRadarUnavailable(useCase.compute(RadarBudgetInput.Simulated(simulation)))
    }

    private fun assertRadarUnavailable(result: MoneyRadarData) {
        assertEquals(UrgencyLevel.UNAVAILABLE, result.urgencyLevel)
        assertNull(result.urgencyScore)
        assertTrue(result.topReasons.isEmpty())
        assertNull(result.primaryCta)
        assertNull(result.budgetRisk)
        assertTrue(result.dueBills.isEmpty())
        assertTrue(result.anomalyAlerts.isEmpty())
    }

    private fun expense(
        amount: Double,
        type: TransactionType,
        date: Long,
        isNotMine: Boolean = false
    ): Expense {
        return Expense(
            id = date,
            amount = amount,
            currency = "EUR",
            merchant = "M",
            transactionType = type,
            date = date,
            categoryId = null,
            paymentMethod = PaymentMethod.CARD,
            isNotMine = isNotMine
        )
    }

    private fun monteCarloResult(
        probabilityUnderBudget: Double?,
        budgetAmount: Double? = 1000.0,
        displayCurrency: String? = "EUR"
    ): MonteCarloResult {
        return MonteCarloResult(
            percentile10 = 900.0,
            percentile25 = 950.0,
            percentile50 = 1000.0,
            percentile75 = 1100.0,
            percentile90 = 1200.0,
            probabilityUnderBudget = probabilityUnderBudget,
            budgetAmount = budgetAmount,
            spentToDate = 300.0,
            knownUpcoming = 150.0,
            confidence = SimulationConfidence(
                score = 0.7,
                level = ConfidenceLevel.HIGH,
                reason = "sufficient history"
            ),
            metadata = SimulationMetadata(
                qualifyingWeeks = 10,
                totalWeeksExamined = 12,
                iterations = 1000,
                logNormalMu = 0.0,
                logNormalSigma = 1.0,
                daysRemaining = 10,
                computedAt = now
            ),
            displayCurrency = displayCurrency
        )
    }

    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int): Long {
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
