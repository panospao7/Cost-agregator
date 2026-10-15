package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.ForecastComponents
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * R9 (RG16A-2, owner option B): STRICT goal reserves must be currency-safe.
 *
 * - T1: a STRICT unfinished goal with unknown currency provenance is EXCLUDED (never treated as
 *   display currency) and counted in goalReserveExclusions; forecast.isPartial/excludedCount
 *   (which gate Monte Carlo) are NOT touched.
 * - T2: a known same-currency goal is pro-rated and reserved without any conversion.
 * - T3: a known foreign-currency goal is pro-rated in ITS OWN currency, then converted.
 * - T4: a conversion failure excludes the goal and counts it.
 * - T5: mixed goals reserve only the successfully resolved ones.
 * - T8: non-STRICT / finished goals are ignored (no exclusion); blank display currency keeps
 *   the goal amount as-is while unknown provenance is still excluded.
 *
 * Fixed now = 2026-03-15 12:00 (31-day month). Hand oracles: a goal due in exactly 62 days has
 * targetMonthsRemaining = (62 / 31) = 2.0, so the monthly reserve is remaining / 2.
 */
class SynthesisEngineGoalReserveCurrencyTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var engine: SynthesisEngine

    private val now: Long by lazy {
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private val sixtyTwoDaysFromNow: Long get() = now + TimeUnit.DAYS.toMillis(62)

    @Before
    fun setUp() {
        timeProvider = mockk()
        every { timeProvider.now() } returns now
        currencyConverter = mockk()
        engine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)
    }

    // -- fixtures -----------------------------------------------------------

    private fun pace() = SpendingPace(
        currentMonthSpent = 0.0,
        daysElapsed = 15,
        daysInMonth = 31,
        projectedTotal = 0.0,
        previousMonthTotal = 1200.0,
        averageMonthlyTotal = 1200.0,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR",
    )

    private fun budget(amount: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = amount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 0L,
        periodEnd = 0L
    )

    private fun goal(
        id: Long,
        target: Double,
        current: Double,
        currency: String,
        assumption: String,
        protection: GoalProtectionLevel = GoalProtectionLevel.STRICT,
        targetDate: Long? = null
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        targetAmount = target,
        currentAmount = current,
        targetDate = targetDate,
        protectionLevel = protection,
        currency = currency,
        currencyAssumption = assumption,
        createdAt = 0L
    )

    private fun converted(amount: Double, from: String, to: String, convertedAmount: Double) =
        ConversionOutcome.Converted(
            originalAmount = amount,
            originalCurrency = CurrencyCode(from),
            convertedAmount = convertedAmount,
            targetCurrency = CurrencyCode(to),
            rateUsed = convertedAmount / amount,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = null,
            rateLastUpdated = now,
            rateSource = "test",
            conversionPath = ConversionPath.DIRECT
        )

    private fun failed(amount: Double, from: String, to: String) = ConversionOutcome.Failed(
        originalAmount = amount,
        originalCurrency = from,
        targetCurrency = to,
        rateBasis = RateBasis.LATEST_AVAILABLE,
        failureType = ConversionFailureType.MISSING_RATE,
        message = "test failure"
    )

    /** Any conversion attempt is a test failure (Error, not swallowed by the engine's catch). */
    private fun stubNoConversionAllowed() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("no currency conversion expected")
    }

    /** USD converts at 0.5 into EUR; every other pair fails. */
    private fun stubUsdHalfOthersFail() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            val amount: Double = firstArg()
            val from: String = secondArg()
            val to: String = thirdArg()
            if (from == "USD" && to == "EUR") converted(amount, from, to, amount * 0.5)
            else failed(amount, from, to)
        }
    }

    private suspend fun synthesize(goals: List<SavingsGoal>, displayCurrency: String = "EUR") =
        engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = goals,
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = displayCurrency
        )

    // -- T1 -----------------------------------------------------------------

    @Test
    fun `T1 strict goal with unknown currency provenance is excluded not reserved`() = runTest {
        stubNoConversionAllowed()

        // LEGACY_DEFAULT provenance: the "EUR" label is an assumption, never proof.
        val forecast = synthesize(
            listOf(goal(1, 1000.0, 500.0, "EUR", "LEGACY_DEFAULT"))
        )

        assertEquals(0.0, forecast.components.goalReserves, 0.0)
        assertEquals(1, forecast.components.goalReserveExclusions)
        assertTrue(forecast.components.goalReservesPartial)
        // Reserve was not guessed: 2000 budget - 0 spent - 0 obligations - 0 reserve.
        assertEquals(2000.0, forecast.components.discretionaryBudget, 0.0001)
        // Monte Carlo gating signals are untouched by goal exclusions.
        assertFalse(forecast.isPartial)
        assertEquals(0, forecast.excludedCount)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // -- T2 -----------------------------------------------------------------

    @Test
    fun `T2 explicit same-currency goal is prorated and reserved without conversion`() = runTest {
        stubNoConversionAllowed()

        // remaining 500, 62 days => 2.0 months => 250 per month.
        val forecast = synthesize(
            listOf(goal(1, 1000.0, 500.0, "EUR", "EXPLICIT", targetDate = sixtyTwoDaysFromNow))
        )

        assertEquals(250.0, forecast.components.goalReserves, 0.0001)
        assertEquals(0, forecast.components.goalReserveExclusions)
        assertFalse(forecast.components.goalReservesPartial)
        assertEquals(1750.0, forecast.components.discretionaryBudget, 0.0001)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // -- T3 -----------------------------------------------------------------

    @Test
    fun `T3 foreign-currency goal is prorated in its own currency then converted`() = runTest {
        stubUsdHalfOthersFail()

        // remaining 500 USD, 62 days => 250 USD this month => 0.5 rate => 125 EUR.
        val forecast = synthesize(
            listOf(goal(1, 1000.0, 500.0, "USD", "EXPLICIT", targetDate = sixtyTwoDaysFromNow))
        )

        assertEquals(125.0, forecast.components.goalReserves, 0.0001)
        assertEquals(0, forecast.components.goalReserveExclusions)
        assertFalse(forecast.components.goalReservesPartial)
        assertEquals(1875.0, forecast.components.discretionaryBudget, 0.0001)
        // The pro-rated GOAL-currency amount (250 USD) is what reaches the converter.
        coVerify(exactly = 1) {
            currencyConverter.convertOutcome(
                250.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, any(), StaleRatePolicy.LatestDefault
            )
        }
    }

    // -- T4 -----------------------------------------------------------------

    @Test
    fun `T4 conversion failure excludes the goal and counts it`() = runTest {
        stubUsdHalfOthersFail()

        // GBP -> EUR fails in the stub; the raw 500 must never be reserved as EUR.
        val forecast = synthesize(
            listOf(goal(1, 1000.0, 500.0, "GBP", "EXPLICIT"))
        )

        assertEquals(0.0, forecast.components.goalReserves, 0.0)
        assertEquals(1, forecast.components.goalReserveExclusions)
        assertTrue(forecast.components.goalReservesPartial)
        assertEquals(2000.0, forecast.components.discretionaryBudget, 0.0001)
        assertFalse(forecast.isPartial)
        assertEquals(0, forecast.excludedCount)
    }

    // -- T5 -----------------------------------------------------------------

    @Test
    fun `T5 mixed goals reserve only resolved goals and count the rest`() = runTest {
        stubUsdHalfOthersFail()

        val forecast = synthesize(
            listOf(
                goal(1, 1000.0, 500.0, "EUR", "EXPLICIT"),        // due now: 500 EUR reserved
                goal(2, 500.0, 300.0, "USD", "USER_CONFIRMED"),    // due now: 200 USD -> 100 EUR
                goal(3, 1000.0, 0.0, "GBP", "EXPLICIT"),           // conversion fails -> excluded
                goal(4, 1000.0, 0.0, "EUR", "LEGACY_DEFAULT")      // unknown provenance -> excluded
            )
        )

        // 500 + 100 = 600 reserved; two exclusions.
        assertEquals(600.0, forecast.components.goalReserves, 0.0001)
        assertEquals(2, forecast.components.goalReserveExclusions)
        assertTrue(forecast.components.goalReservesPartial)
        assertEquals(1400.0, forecast.components.discretionaryBudget, 0.0001)
        assertFalse(forecast.isPartial)
        assertEquals(0, forecast.excludedCount)
    }

    // -- T8 -----------------------------------------------------------------

    @Test
    fun `T8 non-strict and finished goals are ignored without exclusion`() = runTest {
        stubNoConversionAllowed()

        val forecast = synthesize(
            listOf(
                goal(1, 1000.0, 0.0, "EUR", "LEGACY_DEFAULT", GoalProtectionLevel.WARNING),
                goal(2, 1000.0, 0.0, "EUR", "LEGACY_DEFAULT", GoalProtectionLevel.TRACKING),
                goal(3, 1000.0, 1000.0, "EUR", "LEGACY_DEFAULT", GoalProtectionLevel.STRICT), // finished
                goal(4, 1000.0, 1200.0, "USD", "LEGACY_DEFAULT", GoalProtectionLevel.STRICT)  // over-funded
            )
        )

        assertEquals(0.0, forecast.components.goalReserves, 0.0)
        assertEquals(0, forecast.components.goalReserveExclusions)
        assertFalse(forecast.components.goalReservesPartial)
        assertEquals(2000.0, forecast.components.discretionaryBudget, 0.0001)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `T8b blank display currency keeps known goal as-is and still excludes unknown`() = runTest {
        stubNoConversionAllowed()

        val forecast = synthesize(
            goals = listOf(
                goal(1, 1000.0, 500.0, "USD", "EXPLICIT"),         // 500 as-is, no conversion
                goal(2, 1000.0, 0.0, "EUR", "LEGACY_DEFAULT")      // unknown provenance -> excluded
            ),
            displayCurrency = ""
        )

        assertEquals(500.0, forecast.components.goalReserves, 0.0001)
        assertEquals(1, forecast.components.goalReserveExclusions)
        assertTrue(forecast.components.goalReservesPartial)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // -- Model contract -----------------------------------------------------

    private fun components(goalReserveExclusions: Int) = ForecastComponents(
        recurringExpenses = emptyList(),
        pastSpendingPoints = emptyList(),
        projectedSpendingPoints = emptyList(),
        totalCommitted = 0.0,
        totalLikely = 0.0,
        predictedDiscretionary = 0.0,
        discretionaryBudget = 0.0,
        riskLevel = RiskLevel.LOW,
        goalReserveExclusions = goalReserveExclusions
    )

    @Test
    fun `ForecastComponents derives goalReservesPartial and rejects negative exclusions`() {
        assertFalse(components(0).goalReservesPartial)
        assertTrue(components(3).goalReservesPartial)
        assertThrows(IllegalArgumentException::class.java) { components(-1) }
    }
}
