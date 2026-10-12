package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.savings.SavingsInputStage
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class MonthlySavingsSweepUseCaseTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var timeProvider: TimeProvider
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var recurringOccurrenceDao: RecurringOccurrenceDao

    private lateinit var useCase: MonthlySavingsSweepUseCase

    private val dayMs = 24L * 60L * 60L * 1000L
    private val withinWindowNow = millis(2026, Calendar.JANUARY, 29) // last 5 days of Jan (31 days)

    @Before
    fun setup() {
        budgetRepository = mockk()
        savingsGoalRepository = mockk()
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        plannedExpenseRepository = mockk()
        monteCarloSimulator = mockk()
        timeProvider = mockk()
        currencySettingsRepository = mockk()

        every { timeProvider.now() } returns withinWindowNow
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        every { currencySettingsRepository.lastRateUpdate() } returns flowOf(0L)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarlo(p50 = 100.0, p75 = 120.0, confidence = 0.9)

        currencyConverter = mockk()
        coEvery { currencyConverter.convertMultiple(any(), any()) } answers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            MultiConversionAggregate(amounts.sumOf { it.first }, "EUR", emptyList())
        }
        recurringOccurrenceDao = mockk()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        analyticsCurrencyNormalizer = mockk()
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = emptyList(),
            latestRateTimestamp = null,
            totalInputCount = 0
        )

        useCase = MonthlySavingsSweepUseCase(
            budgetRepository = budgetRepository,
            savingsGoalRepository = savingsGoalRepository,
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            recurringOccurrenceDao = recurringOccurrenceDao,
        )
    }

    @Test
    fun `computeSweepRecommendation avoids double counting when overall budget exists`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 900.0), // remaining 100
                budgetStatus(budgetId = 2L, categoryId = 1L, amount = 600.0, spent = 520.0),     // remaining 80
                budgetStatus(budgetId = 3L, categoryId = 2L, amount = 500.0, spent = 440.0),      // remaining 60
            )
        )
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(1L, "Emergency", target = 1000.0, current = 100.0))
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        // Must use only overall remaining (100), not 100+80+60.
        assertApproxEquals(100.0, result!!.totalUnderspend, 0.01)
        assertApproxEquals(20.0, result.riskBuffer, 0.01)
        assertApproxEquals(80.0, result.safeSweepAmount, 0.01)
    }

    @Test
    fun `computeSweepRecommendation filters invalid goals targetAmount less or equal zero preventing NaN`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 500.0, spent = 300.0))
        )
        // Domain model requires targetAmount > 0. Use completed goals (current >= target)
        // which are filtered out by the use case, producing null.
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(
                goal(1L, "CompletedGoal", target = 100.0, current = 100.0),
                goal(2L, "OverfundedGoal", target = 200.0, current = 300.0)
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation allocates proportionally to urgency`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 880.0)) // underspend 120
        )
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarlo(
            p50 = 50.0,
            p75 = 70.0,
            confidence = 0.9
        ) // p75-p50=20 but capped at 30% of p50 => risk buffer 15, safe sweep 105
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(
                goal(1L, "GoalA", target = 1000.0, current = 400.0), // urgency 0.6
                goal(2L, "GoalB", target = 1000.0, current = 600.0)  // urgency 0.4
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        val byId = result!!.goalAllocations.associateBy { it.goalId }
        assertApproxEquals(105.0, result.safeSweepAmount, 0.01)
        assertApproxEquals(63.0, byId.getValue(1L).suggestedAllocation, 0.01)
        assertApproxEquals(42.0, byId.getValue(2L).suggestedAllocation, 0.01)
        assertApproxEquals(0.6, byId.getValue(1L).allocationPercentage, 0.01)
        assertApproxEquals(0.4, byId.getValue(2L).allocationPercentage, 0.01)
    }

    @Test
    fun `shouldShowSweepPrompt true only in last five days of month`() {
        every { timeProvider.now() } returns millis(2026, Calendar.JANUARY, 26)
        assertEquals(false, useCase.shouldShowSweepPrompt())

        every { timeProvider.now() } returns millis(2026, Calendar.JANUARY, 27)
        assertEquals(true, useCase.shouldShowSweepPrompt())
    }

    @Test
    fun `computeSweepRecommendation edge case no budgets returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation edge case no goals returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 800.0))
        )
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation edge case negative underspend returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(budgetId = 1L, categoryId = 1L, amount = 200.0, spent = 250.0),
                budgetStatus(budgetId = 2L, categoryId = 2L, amount = 300.0, spent = 330.0)
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation passes real upcoming obligations into Monte Carlo`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 700.0))
        )
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(goal(1L, "Emergency", 1000.0, 100.0)))
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(
            listOf(
                ManualRecurringExpense(
                    id = 1L,
                    merchant = "Rent",
                    amount = 120.0,
                    currency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = withinWindowNow + dayMs,
                    note = null
                )
            )
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(
            listOf(
                PlannedExpense(
                    id = 2L,
                    description = "Insurance",
                    amount = 80.0,
                    date = withinWindowNow + 2 * dayMs,
                    categoryId = null,
                    isRecurring = false,
                    priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
                )
            )
        )

        var knownUpcoming = -1.0
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } answers {
            knownUpcoming = secondArg()
            monteCarlo(p50 = 100.0, p75 = 120.0, confidence = 0.9)
        }

        useCase.computeSweepRecommendation()

        assertApproxEquals(200.0, knownUpcoming, 0.01)
    }

    @Test
    fun `computeSweepRecommendation derives deterministic fallback risk buffer when Monte Carlo unavailable`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 700.0))
        )
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(goal(1L, "Emergency", target = 1000.0, current = 100.0))
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            com.yourname.expensetracker.data.database.entity.Expense(
                amount = 300.0,
                merchant = "Groceries",
                transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                date = withinWindowNow - dayMs
            )
        )
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(
            listOf(
                ManualRecurringExpense(
                    id = 1L,
                    merchant = "Rent",
                    amount = 80.0,
                    currency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = withinWindowNow + dayMs,
                    note = null
                )
            )
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        assertApproxEquals(30.0, result!!.riskBuffer, 0.01)
        assertApproxEquals(270.0, result.safeSweepAmount, 0.01)
    }

    @Test
    fun `computeSweepRecommendation caps allocations by remaining goal gap before concentration cap`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 800.0))
        )
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(
            listOf(
                goal(1L, "Almost Done", target = 100.0, current = 90.0),
                goal(2L, "Large Goal", target = 1000.0, current = 100.0)
            )
        )
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarlo(
            p50 = 50.0,
            p75 = 50.0,
            confidence = 0.9
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        val byId = result!!.goalAllocations.associateBy { it.goalId }
        assertApproxEquals(10.0, byId.getValue(1L).suggestedAllocation, 0.01)
        assertTrue(byId.getValue(2L).suggestedAllocation <= result.safeSweepAmount * MonthlySavingsSweepUseCase.MAX_SINGLE_ALLOCATION_PERCENT + 0.01)
        assertTrue(byId.getValue(2L).suggestedAllocation <= 900.0)
    }

    @Test
    fun `mixed goal currencies allocate only to computation currency goals`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
            budgetStatus(1L, null, 1000.0, 800.0)
        ))
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, "Home", 1000.0, 100.0),
            goal(2L, "Foreign", 1000.0, 100.0).copy(currency = "USD", currencyAssumption = "EXPLICIT")
        ))
        val result = requireNotNull(useCase.computeSweepRecommendation())
        assertEquals("EUR", result.currency)
        assertEquals(CurrencyCode.EUR, result.moneySafeSweepAmount?.currency)
        assertEquals(listOf(1L), result.goalAllocations.map { it.goalId })
        assertTrue(result.goalAllocations.sumOf { it.suggestedAllocation } <= result.safeSweepAmount)
    }

    @Test
    fun `all foreign goals decline without relabeling a sweep`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
            goal(1L, "Foreign", 1000.0, 100.0).copy(currency = "USD", currencyAssumption = "EXPLICIT")
        ))
        assertTrue(useCase.computeSweepRecommendationResult() is SavingsSweepComputationResult.NoRecommendation)
    }

    @Test
    fun `excluded spending declines sweep rather than improving safe money`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns AnalyticsNormalizationResult(
            "EUR", emptyList(), emptyList(), emptyList(), null, totalInputCount = 2
        )
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertEquals(2, result.metadata.totalInputCount)
        assertEquals(2, result.metadata.excludedInputCount)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun invalidTargetIsUnavailableEvenWithoutBudgets() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("SECRET invalid")
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals("INVALID_TARGET_CURRENCY", result.reasonCode)
        assertNull(result.metadata.currency)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun settingsReadFailureIsControlledUnavailableNotNoRecommendation() = runTest {
        every { currencySettingsRepository.homeCurrency() } throws IllegalStateException("SECRET /private/database")
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals("HOME_CURRENCY_UNAVAILABLE", result.reasonCode)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun settingsTimeoutRemainsUnavailable() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.awaitCancellation()
        }
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals("HOME_CURRENCY_UNAVAILABLE", result.reasonCode)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun partialBudgetAndUnknownPercentRemainUnavailable() = runTest {
        for (status in listOf(
            budgetStatus(1L, null, 1000.0, 800.0).copy(isPartial = true),
            budgetStatus(1L, null, 1000.0, 800.0).copy(percentKnown = false)
        )) {
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
            val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
            assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
            assertEquals(ConversionQuality.UNAVAILABLE, result.metadata.conversionQuality)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun missingAndStaleUpcomingConversionRemainUnavailableWithControlledMetadata() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(listOf(
            ManualRecurringExpense(
                id = 1L, merchant = "SECRET merchant", amount = 20.0, currency = "USD",
                frequency = RecurrenceFrequency.MONTHLY, nextDate = withinWindowNow + dayMs, note = null
            )
        ))
        for (code in listOf("MISSING_RATE", "STALE_RATE")) {
            coEvery { currencyConverter.convertMultiple(any(), any()) } returns MultiConversionAggregate(
                0.0, "EUR", listOf(com.yourname.expensetracker.domain.currency.FailedConversion(
                    20.0, "USD", "EUR", "SECRET /private/receipt", code
                ))
            )
            val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
            assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
            assertEquals(1, result.metadata.excludedInputCount)
            assertTrue((if (code == "STALE_RATE") "STALE_EXCHANGE_RATE" else "MISSING_EXCHANGE_RATE")
                in result.metadata.warningCodes)
            assertTrue(!result.toString().contains("SECRET"))
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun unavailableForecastCannotProduceSweepMoney() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns
            MonteCarloResult.unavailable("SECRET forecast")
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals("DISPLAY_CONVERSION_UNAVAILABLE", result.reasonCode)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun staleNormalizationRetainsCountsWarningAndRateTimestamp() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns AnalyticsNormalizationResult(
            "EUR", emptyList(), emptyList(), listOf(
                AnalyticsConversionWarning(AnalyticsConversionWarningType.STALE_EXCHANGE_RATE, "SECRET row", 2)
            ), 321L, totalInputCount = 2
        )
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
        assertEquals(2, result.metadata.excludedInputCount)
        assertEquals(321L, result.metadata.latestRateTimestamp)
        assertTrue("STALE_EXCHANGE_RATE" in result.metadata.warningCodes)
        assertTrue(!result.toString().contains("SECRET"))
    }

    @Test
    fun availableSweepRetainsDistinctRatePoliciesAndSettingsVersion() = runTest {
        every { currencySettingsRepository.lastRateUpdate() } returns flowOf(999L)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(goal(1L, "Home", 1000.0, 100.0)))
        val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Available
        assertEquals("EUR", result.metadata.currency)
        assertEquals(999L, result.metadata.settingsRateTimestamp)
        assertEquals(999L, result.recommendation.rateTimestamp)
        assertEquals(setOf(RateBasis.TRANSACTION_DATE, RateBasis.LATEST_AVAILABLE), result.metadata.requestedRateBases)
        assertEquals(setOf(SavingsInputStage.CURRENT_SPENDING, SavingsInputStage.RECURRING_UPCOMING,
            SavingsInputStage.PLANNED_UPCOMING), result.metadata.inputs.map { it.stage }.toSet())
        assertEquals(ConversionQuality.COMPLETE, result.metadata.conversionQuality)
    }

    @Test
    fun noBudgetsAndNoUnderspendAreValidNoRecommendation() = runTest {
        val empty = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.NoRecommendation
        assertEquals("EUR", empty.metadata.currency)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 1100.0)))
        assertTrue(useCase.computeSweepRecommendationResult() is SavingsSweepComputationResult.NoRecommendation)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class IdentityCancellation : kotlinx.coroutines.CancellationException("SECRET cancellation"),
        kotlinx.coroutines.CopyableThrowable<IdentityCancellation> {
        override fun createCopy(): IdentityCancellation? = null
    }

    @Test
    fun typedSweepPreservesCancellationIdentity() = runTest {
        val cancelled = IdentityCancellation()
        every { currencySettingsRepository.homeCurrency() } throws cancelled
        org.junit.Assert.assertSame(cancelled, runCatching { useCase.computeSweepRecommendationResult() }.exceptionOrNull())
    }

    @Test
    fun `sweep summary cannot relabel captured computation currency`() {
        val result = SavingsSweepRecommendation(50.0, 10.0, 40.0, emptyList(), withinWindowNow, withinWindowNow, 0.9, "USD")
        assertTrue(result.getSummary().contains("40"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { result.getSummary("EUR") }
    }

    @Test
    fun unknownActiveGoalCannotBecomeNoUnderspendOrNoEligibleGoals() = runTest {
        for (currency in listOf("EUR", "USD", "GBP")) {
            for (provenance in listOf("LEGACY_DEFAULT", "UNKNOWN", "", "ASSUMED_HOME_CURRENCY", "SECRET unknown")) {
                every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
                    goal(1L, "Goal", 1000.0, 0.0).copy(currency = currency, currencyAssumption = provenance)
                ))
                val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
                assertEquals("INVALID_SOURCE_CURRENCY", result.reasonCode)
                assertEquals(ConversionQuality.UNAVAILABLE, result.metadata.conversionQuality)
                assertEquals(listOf("INVALID_SOURCE_CURRENCY"), result.metadata.warningCodes)
                assertTrue(!result.toString().contains("SECRET"))
            }
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun mixedExplicitAndLegacyGoalsCannotReceivePartialSweep() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(1L, null, 1000.0, 800.0)))
        for (currency in listOf("EUR", "USD")) {
            every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
                goal(1L, "Known", 1000.0, 100.0),
                goal(2L, "Legacy", 1000.0, 100.0).copy(currency = currency, currencyAssumption = "LEGACY_DEFAULT")
            ))
            val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Unavailable
            assertEquals("INVALID_SOURCE_CURRENCY", result.reasonCode)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun explicitEurUsdGbpSweepsRetainOriginalAmountsAndUnits() = runTest {
        for (currency in listOf("EUR", "USD", "GBP")) {
            every { currencySettingsRepository.homeCurrency() } returns flowOf(currency)
            val status = budgetStatus(1L, null, 1000.0, 900.0)
            every { budgetRepository.getBudgetStatuses() } returns flowOf(
                listOf(status.copy(currency = currency, budget = status.budget.copy(currency = currency)))
            )
            every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(listOf(
                goal(1L, "Known", 1000.0, 100.0).copy(currency = currency, currencyAssumption = "EXPLICIT_HOME_CURRENCY")
            ))
            coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), currency) } returns
                AnalyticsNormalizationResult(currency, emptyList(), emptyList(), emptyList(), null, totalInputCount = 0)
            coEvery { currencyConverter.convertMultiple(any(), currency) } returns
                MultiConversionAggregate(0.0, currency, emptyList())
            coEvery { monteCarloSimulator.simulate(any(), any(), any(), currency, any()) } returns
                monteCarlo(100.0, 120.0, 0.9).copy(displayCurrency = currency)
            val result = useCase.computeSweepRecommendationResult() as SavingsSweepComputationResult.Available
            assertApproxEquals(80.0, result.recommendation.safeSweepAmount)
            assertEquals(currency, result.recommendation.currency)
            assertEquals(currency, result.metadata.currency)
            assertEquals(listOf(currency), result.recommendation.goalAllocations.map { it.currency })
        }
    }

    private fun budgetStatus(
        budgetId: Long,
        categoryId: Long?,
        amount: Double,
        spent: Double
    ): BudgetStatus {
        val budget = Budget(
            id = budgetId,
            categoryId = categoryId,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = withinWindowNow - 20 * dayMs
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = amount - spent,
            percentUsed = if (amount > 0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = withinWindowNow - 20 * dayMs,
            periodEnd = withinWindowNow + 10 * dayMs,
            effectiveLimit = 0.0,
        )
    }

    private fun goal(id: Long, name: String, target: Double, current: Double): SavingsGoal {
        return SavingsGoal(
            id = id,
            name = name,
            targetAmount = target,
            currentAmount = current,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            currencyAssumption = "EXPLICIT",
            createdAt = withinWindowNow - dayMs
        )
    }

    private fun monteCarlo(p50: Double, p75: Double, confidence: Double): MonteCarloResult {
        return MonteCarloResult(
            percentile10 = p50 - 20,
            percentile25 = p50 - 10,
            percentile50 = p50,
            percentile75 = p75,
            percentile90 = p75 + 20,
            probabilityUnderBudget = 0.7,
            budgetAmount = 1000.0,
            spentToDate = 0.0,
            knownUpcoming = 0.0,
            confidence = SimulationConfidence(
                score = confidence,
                level = ConfidenceLevel.HIGH,
                reason = "test"
            ),
            metadata = SimulationMetadata(
                qualifyingWeeks = 10,
                totalWeeksExamined = 12,
                iterations = 1000,
                logNormalMu = 0.0,
                logNormalSigma = 1.0,
                daysRemaining = 2,
                computedAt = withinWindowNow
            ),
            displayCurrency = "EUR"
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
