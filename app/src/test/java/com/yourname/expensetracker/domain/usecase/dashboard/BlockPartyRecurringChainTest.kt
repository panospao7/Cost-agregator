package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
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
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * BL-02 real chain (fix-1.0.4-block-party-plan, reviewer-strict REJECT on 92346ad0).
 *
 * Runs the REAL [ForecastInputAssembler.assembleNormalized] and the REAL [SynthesisEngine]
 * (with an occurrence DAO, as in production) behind [ComputeDashboardWidgetsUseCase]. The
 * planned rows are shaped like the ones RecurringPlanProjectionService writes for each
 * PLANNED occurrence: description = merchant, amount/currency/date from the occurrence,
 * MUST priority, isRecurring = true, sourceOccurrenceKey = occurrenceKey. No earlier test fed
 * such a row, so the double count (bill placed once from the occurrence and once from its
 * projected planned row) was invisible.
 *
 * "Now" is 2026-06-15 12:00 in the JVM default zone (June has 30 days); every fixture date is
 * built in that same zone. Only the currency converter and peripheral use cases are mocks.
 *
 * Would fail on 92346ad0: every test that includes a keyed planned row and asserts a base or
 * target number or a runway committed total.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BlockPartyRecurringChainTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }

    /** Occurrences the DAO mock holds; it returns those whose dueDate is inside the requested range. */
    private var storedOccurrences: List<RecurringOccurrence> = emptyList()

    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var assembler: ForecastInputAssembler
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer

    // ------------------------------------------------ Block Party, real chain

    /**
     * Annual 1200 PLANNED on day 15 plus its projected planned row, limit 3000, 30-day month.
     * Counted once: pool 3000 - 1200 = 1800, base 60, day 15 = 1260, targets sum to 3000.
     * On 92346ad0 the keyed row was placed again: base 20, day 15 = 2420.
     */
    @Test
    fun `BL-02 annual bill with its projected planned row is counted once`() = runTest {
        fixedNowMs = at(15, 12)
        val annual = occurrence(1L, 15, 1200.0, RecurrenceFrequency.ANNUALLY, "Annual insurance")
        storedOccurrences = listOf(annual)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(1L, "Annual insurance", 1200.0, RecurrenceFrequency.ANNUALLY, at(15, 0))),
                planned = listOf(projectedRow(10L, annual)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach { day ->
            assertEquals("base day ${day.dayOfMonth}", 60.0, day.baseTarget, TOL)
            if (day.dayOfMonth != 15) {
                assertEquals("target day ${day.dayOfMonth}", 60.0, day.targetBudget, TOL)
            }
        }
        val day15 = days.single { it.dayOfMonth == 15 }
        assertEquals(1260.0, day15.targetBudget, TOL)
        assertEquals(1200.0, day15.recurringImpact, TOL)
        assertEquals(0.0, day15.plannedImpact, TOL)
        assertTrue(day15.plannedItems.isEmpty())
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
    }

    /**
     * Runway: the keyed row no longer adds to totalCommitted. Annual 1200 is committed once.
     * On 92346ad0 this was 2400 (pattern plus MUST planned row).
     */
    @Test
    fun `BL-02 runway committed total does not double count a projected recurring row`() = runTest {
        fixedNowMs = at(15, 12)
        val annual = occurrence(1L, 15, 1200.0, RecurrenceFrequency.ANNUALLY, "Annual insurance")
        storedOccurrences = listOf(annual)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(1L, "Annual insurance", 1200.0, RecurrenceFrequency.ANNUALLY, at(15, 0))),
                planned = listOf(projectedRow(10L, annual)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertEquals(1200.0, runway.committedExpenses, TOL)
    }

    /**
     * June, limit 3000: annual 1200 on day 15, weekly 50 on days 3/10/17/24, PAID rent 800,
     * unkeyed planned item 100 on day 20, strict reserve 150. Projected rows exist only where
     * production creates them (PLANNED occurrences: annual and the four weekly ones, not the
     * PAID rent). Pool = 3000 - 1200 - 200 - 800 - 100 - 150 = 550, base 18.333; targets sum to
     * limit - reserves - PAID = 2050 (the corrected invariant, not limit - reserves).
     */
    @Test
    fun `BL-02 June scenario pool 550 base 18_333 and targets sum to limit minus reserves minus PAID`() = runTest {
        fixedNowMs = at(15, 12)
        val annual = occurrence(1L, 15, 1200.0, RecurrenceFrequency.ANNUALLY, "Annual insurance")
        val weekly = listOf(3, 10, 17, 24).map {
            occurrence(2L, it, 50.0, RecurrenceFrequency.WEEKLY, "Weekly cleaner")
        }
        val rentPaid = occurrence(3L, 1, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = listOf(annual) + weekly + rentPaid
        val projected = (listOf(annual) + weekly).mapIndexed { index, occ -> projectedRow(20L + index, occ) }
        val unkeyedItem = PlannedExpense(
            id = 99L, description = "Dentist", amount = 100.0, currency = "EUR",
            date = at(20, 0), categoryId = null, isRecurring = false,
            priority = PlannedExpensePriority.MUST
        )
        val reserve = SavingsGoal(
            id = 1L, name = "Reserve", targetAmount = 1000.0, currentAmount = 850.0,
            targetDate = null, protectionLevel = GoalProtectionLevel.STRICT,
            currency = "EUR", currencyAssumption = "EXPLICIT", createdAt = 0L
        )

        val result = computeUseCase.compute(
            data(
                patterns = listOf(
                    pattern(1L, "Annual insurance", 1200.0, RecurrenceFrequency.ANNUALLY, at(15, 0)),
                    pattern(2L, "Weekly cleaner", 50.0, RecurrenceFrequency.WEEKLY, at(17, 0)),
                    pattern(3L, "Rent", 800.0, RecurrenceFrequency.MONTHLY, at(1, 0, month = 7))
                ),
                planned = projected + unkeyedItem,
                goals = listOf(reserve),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        val base = 550.0 / 30.0
        val placedOnDay = mapOf(3 to 50.0, 10 to 50.0, 17 to 50.0, 24 to 50.0, 15 to 1200.0, 20 to 100.0)
        days.forEach { day ->
            assertEquals("base day ${day.dayOfMonth}", base, day.baseTarget, TOL)
            assertEquals(
                "target day ${day.dayOfMonth}",
                base + (placedOnDay[day.dayOfMonth] ?: 0.0),
                day.targetBudget,
                TOL
            )
        }
        assertEquals(2050.0, days.sumOf { it.targetBudget }, TOL)
        // Day 20 is the unkeyed planned item (control: unkeyed rows are still placed); day 15 is
        // recurring only.
        assertEquals(100.0, days.single { it.dayOfMonth == 20 }.plannedImpact, TOL)
        assertEquals(0.0, days.single { it.dayOfMonth == 15 }.plannedImpact, TOL)
        // Runway committed: annual 1200 + weekly 17th and 24th 100 + planned item 100 = 1400.
        // On 92346ad0 the keyed annual row and the two future weekly rows added 1300 more.
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertEquals(1400.0, runway.committedExpenses, TOL)
    }

    // ------------------------------------------------ rule pattern or occurrence absent

    /**
     * The rule is gone (deleted, paused or ended: the pattern list is active-only): no pattern
     * and no occurrence row, but a leftover PLANNED row with a sourceOccurrenceKey remains.
     * Nothing else represents the bill, so the keyed row stays an ordinary planned row and
     * counts once: pool 3000 - 700 = 2300, base 76.667, day 20 = base + 700, runway 700.
     */
    @Test
    fun `BL-02 absent rule with leftover keyed planned row counts the bill once`() = runTest {
        fixedNowMs = at(15, 12)
        val gone = occurrence(9L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Deleted rule bill")
        storedOccurrences = emptyList()
        val result = computeUseCase.compute(
            data(patterns = emptyList(), planned = listOf(projectedRow(30L, gone)), budgets = listOf(overallBudget(3000.0)))
        )

        assertCountsOnce700OnDay20(result)
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertEquals(700.0, runway.committedExpenses, TOL)
    }

    /**
     * The rule's pattern is absent (disabled rule), but its PLANNED occurrence row still exists.
     * Occurrences are only read for rules that have a pattern, so the keyed planned row is the
     * only representation of the bill and counts once.
     */
    @Test
    fun `BL-02 absent pattern with leftover occurrence and keyed row counts the bill once`() = runTest {
        fixedNowMs = at(15, 12)
        val orphan = occurrence(9L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Disabled rule bill")
        storedOccurrences = listOf(orphan)
        val result = computeUseCase.compute(
            data(patterns = emptyList(), planned = listOf(projectedRow(30L, orphan)), budgets = listOf(overallBudget(3000.0)))
        )

        assertCountsOnce700OnDay20(result)
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertEquals(700.0, runway.committedExpenses, TOL)
    }

    /**
     * A keyed row with a null sourceRecurringRuleId cannot be linked to any pattern, even when
     * another rule's pattern (rule 5, 100 on day 25) is present: the row is kept and counts once.
     * Pool 3000 - 700 - 100 = 2200, base 73.333, day 20 = base + 700, day 25 = base + 100.
     */
    @Test
    fun `BL-02 keyed row with null rule id is kept and counts once`() = runTest {
        fixedNowMs = at(15, 12)
        val unlinked = occurrence(9L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Unlinked bill")
        storedOccurrences = emptyList()
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(5L, "Gym", 100.0, RecurrenceFrequency.MONTHLY, at(25, 0))),
                planned = listOf(projectedRow(30L, unlinked).copy(sourceRecurringRuleId = null)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        val base = 2200.0 / 30.0
        days.forEach { assertEquals("base day ${it.dayOfMonth}", base, it.baseTarget, TOL) }
        val day20 = days.single { it.dayOfMonth == 20 }
        assertEquals(700.0, day20.plannedImpact, TOL)
        assertEquals(base + 700.0, day20.targetBudget, TOL)
        assertEquals(base + 100.0, days.single { it.dayOfMonth == 25 }.targetBudget, TOL)
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertEquals(800.0, runway.committedExpenses, TOL)
    }

    private fun assertCountsOnce700OnDay20(result: CompiledDashboardData) {
        val days = blockDays(result)
        assertEquals(30, days.size)
        val base = 2300.0 / 30.0
        days.forEach { assertEquals("base day ${it.dayOfMonth}", base, it.baseTarget, TOL) }
        val day20 = days.single { it.dayOfMonth == 20 }
        assertEquals(0.0, day20.recurringImpact, TOL)
        assertEquals(700.0, day20.plannedImpact, TOL)
        assertEquals(base + 700.0, day20.targetBudget, TOL)
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
    }

    /**
     * Pattern present, occurrence rows absent: the pattern alone places the bill once (legacy
     * matching for a rule with zero occurrence rows) and the keyed row is not added again.
     * Pool 3000 - 700 = 2300, base 76.667, day 20 = 776.667.
     */
    @Test
    fun `BL-02 present pattern without occurrence rows counts the bill once`() = runTest {
        fixedNowMs = at(15, 12)
        val bill = occurrence(9L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent two")
        storedOccurrences = emptyList()
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(9L, "Rent two", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0))),
                planned = listOf(projectedRow(30L, bill)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach { assertEquals(2300.0 / 30.0, it.baseTarget, TOL) }
        val day20 = days.single { it.dayOfMonth == 20 }
        assertEquals(700.0, day20.recurringImpact, TOL)
        assertEquals(0.0, day20.plannedImpact, TOL)
        assertEquals(2300.0 / 30.0 + 700.0, day20.targetBudget, TOL)
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
    }

    // ------------------------------------------------ occurrence range, status, failure (m3)

    /**
     * Control: the rule's PLANNED occurrence is due this month, so the pattern (id = the row's
     * sourceRecurringRuleId) places the bill and the keyed row is dropped. The pattern's
     * nextExpectedDate is in July so only the occurrence read can cause the drop.
     */
    @Test
    fun `BL-02 occurrence inside the current month places the bill and the keyed row is dropped`() = runTest {
        fixedNowMs = at(15, 12)
        val inJune = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        storedOccurrences = listOf(inJune)

        val out = assembler.assembleNormalized(
            normalizedInput(
                listOf(projectedRow(30L, inJune), plannedRow(31L)),
                listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
            )
        )

        assertEquals(listOf(31L), out.plannedExpenses.map { it.id })
    }

    /**
     * The DAO honours the requested range. The rule's only occurrence is due in July, outside the
     * current month, so it does not place the bill this month and the June keyed row is kept.
     */
    @Test
    fun `BL-02 occurrence outside the current month does not place the bill so the keyed row is kept`() = runTest {
        fixedNowMs = at(15, 12)
        val inJune = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        val inJuly = inJune.copy(dueDate = at(20, 0, month = 7))
        storedOccurrences = listOf(inJuly)

        val out = assembler.assembleNormalized(
            normalizedInput(
                listOf(projectedRow(30L, inJune)),
                listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
            )
        )

        assertEquals(listOf(30L), out.plannedExpenses.map { it.id })
    }

    /**
     * A PAID occurrence (cash already out) with a stray PLANNED keyed row: the rule has an
     * occurrence row this month, so the row is dropped. Bill 700 counts once, as PAID: pool
     * 3000 - 700 = 2300, base 76.667 every day, nothing placed, targets sum to limit - PAID = 2300.
     * If the row were kept it would be placed again on day 20 and the pool would be 1600.
     */
    @Test
    fun `BL-02 PAID occurrence with a PLANNED keyed row drops the row and counts the bill once`() = runTest {
        fixedNowMs = at(15, 12)
        val paid = occurrence(3L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = listOf(paid)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(3L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(1, 0, month = 7))),
                planned = listOf(projectedRow(30L, paid)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach {
            assertEquals("base day ${it.dayOfMonth}", 2300.0 / 30.0, it.baseTarget, TOL)
            assertEquals("planned day ${it.dayOfMonth}", 0.0, it.plannedImpact, TOL)
        }
        assertEquals(2300.0, days.sumOf { it.targetBudget }, TOL)
    }

    /**
     * A SKIPPED occurrence is an explicit user removal; a stray PLANNED keyed row is dropped and
     * the bill is neither placed nor subtracted: pool 3000, base 100, targets sum to 3000.
     * If the row were kept it would be placed on day 20 and the pool would be 2300.
     */
    @Test
    fun `BL-02 SKIPPED occurrence with a PLANNED keyed row drops the row and does not count the bill`() = runTest {
        fixedNowMs = at(15, 12)
        val skipped = occurrence(3L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent", status = "SKIPPED")
        storedOccurrences = listOf(skipped)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(3L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(1, 0, month = 7))),
                planned = listOf(projectedRow(30L, skipped)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach {
            assertEquals("base day ${it.dayOfMonth}", 100.0, it.baseTarget, TOL)
            assertEquals("planned day ${it.dayOfMonth}", 0.0, it.plannedImpact, TOL)
        }
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
    }

    /**
     * The occurrence read fails: the row is kept (fail towards counting), so the bill counts once
     * in the assembled planned list. The pattern's nextExpectedDate is in July, so the legacy
     * month match cannot drop it either. Assembler level only: the engine shares the DAO and
     * would also hit the failure.
     */
    @Test
    fun `BL-02 occurrence DAO failure keeps the keyed row so the bill counts once`() = runTest {
        fixedNowMs = at(15, 12)
        val bill = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        coEvery { occurrenceDao.getByDateRange(any(), any()) } throws IllegalStateException("synthetic read failure")

        val out = assembler.assembleNormalized(
            normalizedInput(
                listOf(projectedRow(30L, bill)),
                listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
            )
        )

        assertEquals(listOf(30L), out.plannedExpenses.map { it.id })
    }

    @Test
    fun `BL-02 occurrence DAO cancellation is rethrown and not swallowed`() = runTest {
        fixedNowMs = at(15, 12)
        val bill = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        coEvery { occurrenceDao.getByDateRange(any(), any()) } throws
            kotlinx.coroutines.CancellationException("synthetic cancellation")

        var thrown: Throwable? = null
        try {
            assembler.assembleNormalized(
                normalizedInput(
                    listOf(projectedRow(30L, bill)),
                    listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }

        assertTrue("CancellationException must propagate", thrown is kotlinx.coroutines.CancellationException)
    }

    // ------------------------------------------------ m1: PAID occurrences read for any rule

    /**
     * m1, paid then paused: rent 800 PAID on day 1, then the rule is paused, so there is no
     * pattern; the projected row is FULFILLED and the assembler filters it. The PAID occurrence
     * is still read (any rule id) and leaves the pool: 3000 - 800 = 2200, base 73.333, nothing
     * placed, targets sum to 2200. Before the fix the occurrence went unread and the base was 100.
     */
    @Test
    fun `m1 paid then paused rule still subtracts the PAID occurrence`() = runTest {
        fixedNowMs = at(15, 12)
        val paid = occurrence(3L, 1, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = listOf(paid)
        val result = computeUseCase.compute(
            data(
                patterns = emptyList(),
                planned = listOf(projectedRow(30L, paid).copy(status = "FULFILLED")),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        assertUniformBaseNothingPlaced(result, 2200.0)
    }

    /**
     * m1, all rules paused with PAID rows: two paused rules each have a PAID occurrence this
     * month (rent 800 with a cash amount 750 actually paid, gym 50 without a paid amount).
     * Both are read with no active pattern: the cash paid 750 and the expected 50 are
     * subtracted once each: 3000 - 750 - 50 = 2200, base 73.333.
     */
    @Test
    fun `m1 all rules paused PAID occurrences are still read and subtracted once each`() = runTest {
        fixedNowMs = at(15, 12)
        val rent = occurrence(3L, 1, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
            .copy(paidAmount = 750.0, paidCurrency = "EUR")
        val gym = occurrence(4L, 5, 50.0, RecurrenceFrequency.MONTHLY, "Gym", status = "PAID")
            .copy(paidAmount = null, paidCurrency = null)
        storedOccurrences = listOf(rent, gym)
        val result = computeUseCase.compute(
            data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0)))
        )

        assertUniformBaseNothingPlaced(result, 2200.0)
    }

    /**
     * m1, paused rule's PLANNED occurrence: rule 7 is paused (no pattern) with a PLANNED
     * occurrence on day 20 (700) and its keyed PLANNED row. The occurrence is read but not
     * placed and does not leave the pool; the keyed row is kept by the keep-the-row rule and is
     * the only thing counting the bill. Active rule 2 (gym 100, day 25) is placed from its
     * occurrence and its own keyed row is dropped. Real numbers: pool 3000 - 700 - 100 = 2200,
     * base 73.333, day 20 = base + 700 as PLANNED impact (recurring impact 0), day 25 = base +
     * 100 as recurring impact. The 700 bill counts exactly once; it is not counted again.
     */
    @Test
    fun `m1 paused rule PLANNED occurrence is not placed and its keyed row counts the bill once`() = runTest {
        fixedNowMs = at(15, 12)
        val pausedBill = occurrence(7L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Paused rent")
        val gym = occurrence(2L, 25, 100.0, RecurrenceFrequency.MONTHLY, "Gym")
        storedOccurrences = listOf(pausedBill, gym)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(2L, "Gym", 100.0, RecurrenceFrequency.MONTHLY, at(25, 0))),
                planned = listOf(projectedRow(30L, pausedBill), projectedRow(31L, gym)),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        val days = blockDays(result)
        assertEquals(30, days.size)
        val base = 2200.0 / 30.0
        days.forEach { assertEquals("base day ${it.dayOfMonth}", base, it.baseTarget, TOL) }
        val day20 = days.single { it.dayOfMonth == 20 }
        assertEquals(0.0, day20.recurringImpact, TOL)
        assertEquals(700.0, day20.plannedImpact, TOL)
        assertEquals(base + 700.0, day20.targetBudget, TOL)
        val day25 = days.single { it.dayOfMonth == 25 }
        assertEquals(100.0, day25.recurringImpact, TOL)
        assertEquals(0.0, day25.plannedImpact, TOL)
        assertEquals(base + 100.0, day25.targetBudget, TOL)
        assertEquals(3000.0, days.sumOf { it.targetBudget }, TOL)
    }

    /**
     * m1, active rule with a PAID occurrence: the pattern is active (next date in July), the
     * PAID rent 800 is subtracted exactly once (not once as a PAID row and again by any other
     * path), never placed, and its FULFILLED keyed row adds nothing: 3000 - 800 = 2200, base
     * 73.333, targets sum to 2200. A double subtraction would give pool 1400.
     */
    @Test
    fun `m1 active rule PAID occurrence counts exactly once`() = runTest {
        fixedNowMs = at(15, 12)
        val paid = occurrence(3L, 1, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = listOf(paid)
        val result = computeUseCase.compute(
            data(
                patterns = listOf(pattern(3L, "Rent", 800.0, RecurrenceFrequency.MONTHLY, at(1, 0, month = 7))),
                planned = listOf(projectedRow(30L, paid).copy(status = "FULFILLED")),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        assertUniformBaseNothingPlaced(result, 2200.0)
    }

    /**
     * Recorded residual, known behaviour (not a goal): the rule was DELETED, so its occurrence
     * rows were deleted with it (RecurringRuleLifecycleCoordinator.deleteRule) and its FULFILLED
     * projected row is filtered. Nothing is read, so the paid 800 no longer leaves the pool and
     * the base rises to 100 with limit 3000.
     */
    @Test
    fun `m1 deleted rule with occurrences gone raises the base as a recorded residual`() = runTest {
        fixedNowMs = at(15, 12)
        val gone = occurrence(3L, 1, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = emptyList()
        val result = computeUseCase.compute(
            data(
                patterns = emptyList(),
                planned = listOf(projectedRow(30L, gone).copy(status = "FULFILLED")),
                budgets = listOf(overallBudget(3000.0))
            )
        )

        assertUniformBaseNothingPlaced(result, 3000.0)
    }

    /**
     * The occurrence read now always runs, even with no active manual rule, so a cancellation
     * thrown by the DAO must propagate out of the real engine (via the use case) and not be
     * swallowed.
     */
    @Test
    fun `m1 occurrence DAO cancellation propagates from the engine with no active rule`() = runTest {
        fixedNowMs = at(15, 12)
        coEvery { occurrenceDao.getByDateRange(any(), any()) } throws
            kotlinx.coroutines.CancellationException("synthetic cancellation")

        var thrown: Throwable? = null
        try {
            computeUseCase.compute(
                data(patterns = emptyList(), planned = emptyList(), budgets = listOf(overallBudget(3000.0)))
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }

        assertTrue("CancellationException must propagate", thrown is kotlinx.coroutines.CancellationException)
    }

    private fun assertUniformBaseNothingPlaced(result: CompiledDashboardData, pool: Double) {
        val days = blockDays(result)
        assertEquals(30, days.size)
        days.forEach {
            assertEquals("base day ${it.dayOfMonth}", pool / 30.0, it.baseTarget, TOL)
            assertEquals("target day ${it.dayOfMonth}", pool / 30.0, it.targetBudget, TOL)
            assertEquals("recurring day ${it.dayOfMonth}", 0.0, it.recurringImpact, TOL)
            assertEquals("planned day ${it.dayOfMonth}", 0.0, it.plannedImpact, TOL)
        }
        assertEquals(pool, days.sumOf { it.targetBudget }, TOL)
    }

    // ------------------------------------------------ m4: unavailable read is flagged

    /**
     * m4: the occurrence read throws. The keyed row is kept (existing behaviour) and now the
     * input is flagged partial with a bounded RECURRING_OCCURRENCES_UNAVAILABLE warning that
     * carries no exception text (the sentinel message must not appear anywhere).
     */
    @Test
    fun `m4 occurrence read failure flags partial with a bounded warning and no exception text`() = runTest {
        fixedNowMs = at(15, 12)
        val sentinel = "SENTINEL_SQL_DETAIL_91827"
        val bill = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        coEvery { occurrenceDao.getByDateRange(any(), any()) } throws IllegalStateException(sentinel)

        val out = assembler.assembleNormalized(
            normalizedInput(
                listOf(projectedRow(30L, bill)),
                listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
            )
        )

        assertTrue("isPartial must be true", out.dataQuality.isPartial)
        val warnings = out.dataQuality.conversionWarnings
        assertTrue(
            "warning must start with the reason code: $warnings",
            warnings.any { it.startsWith("RECURRING_OCCURRENCES_UNAVAILABLE") }
        )
        assertTrue("no exception text in warnings", warnings.none { it.contains(sentinel) })
        assertEquals(listOf(30L), out.plannedExpenses.map { it.id })
    }

    /** m4 control: a successful read leaves the input not partial and adds no warning. */
    @Test
    fun `m4 successful occurrence read is not flagged partial`() = runTest {
        fixedNowMs = at(15, 12)
        val bill = occurrence(1L, 20, 700.0, RecurrenceFrequency.MONTHLY, "Rent")
        storedOccurrences = listOf(bill)

        val out = assembler.assembleNormalized(
            normalizedInput(
                listOf(projectedRow(30L, bill)),
                listOf(pattern(1L, "Rent", 700.0, RecurrenceFrequency.MONTHLY, at(20, 0, month = 7)))
            )
        )

        assertEquals(false, out.dataQuality.isPartial)
        assertTrue(out.dataQuality.conversionWarnings.none { it.startsWith("RECURRING_OCCURRENCES_UNAVAILABLE") })
    }

    // ------------------------------------------------ assembleNormalized

    @Test
    fun `BL-02 assembleNormalized keeps keyed rows with no pattern in the input`() = runTest {
        val keyed = plannedRow(1L, key = "RECURRING_RULE|1|1|MONTHLY")
        val keyedUnmatched = plannedRow(6L, key = "RECURRING_RULE|404|2|MONTHLY")
        val unkeyed = plannedRow(2L)

        val out = assembler.assembleNormalized(normalizedInput(listOf(keyed, unkeyed, keyedUnmatched)))

        assertEquals(listOf(1L, 2L, 6L), out.plannedExpenses.map { it.id })
    }

    @Test
    fun `BL-02 assembleNormalized drops FULFILLED and CANCELLED rows and keeps PLANNED unkeyed`() = runTest {
        val rows = listOf(
            plannedRow(3L, status = "FULFILLED"),
            plannedRow(4L, status = "CANCELLED"),
            plannedRow(5L, status = "PLANNED"),
            plannedRow(7L, status = "FULFILLED", key = "RECURRING_RULE|1|1|MONTHLY")
        )

        val out = assembler.assembleNormalized(normalizedInput(rows))

        assertEquals(listOf(5L), out.plannedExpenses.map { it.id })
    }

    // ------------------------------------------------ assemble() behaviour is unchanged

    /**
     * Pins the pre-change [ForecastInputAssembler.assemble] rule (8de38722): a planned row is
     * dropped only when its sourceOccurrenceKey equals a PLANNED occurrence key in the window.
     * A key that matches a PAID occurrence, or nothing, is kept; unkeyed rows are kept; assemble
     * applied no status filter itself (SynthesisEngine filters later), so a FULFILLED unkeyed
     * row is still passed through.
     */
    @Test
    fun `BL-02 assemble drops only rows keyed to a PLANNED occurrence`() = runTest {
        fixedNowMs = at(15, 12)
        val plannedOcc = occurrence(1L, 20, 800.0, RecurrenceFrequency.MONTHLY, "Rent")
        val paidOcc = occurrence(1L, 5, 800.0, RecurrenceFrequency.MONTHLY, "Rent", status = "PAID")
        storedOccurrences = listOf(plannedOcc, paidOcc)
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        val rule = ManualRecurringExpense(
            id = 1L, merchant = "Rent", amount = 800.0,
            frequency = RecurrenceFrequency.MONTHLY, nextDate = plannedOcc.dueDate, isActive = true
        )
        val rows = listOf(
            plannedRow(1L, key = plannedOcc.occurrenceKey),
            plannedRow(2L, key = paidOcc.occurrenceKey),
            plannedRow(3L, key = "RECURRING_RULE|1|0|MONTHLY"),
            plannedRow(4L),
            plannedRow(5L, status = "FULFILLED")
        )

        val out = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = listOf(rule),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = rows,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        assertEquals(listOf(2L, 3L, 4L, 5L), out.plannedExpenses.map { it.id })
        assertEquals(1, out.dataQuality.excludedPlannedCount)
    }

    // ------------------------------------------------ harness

    private fun blockDays(result: CompiledDashboardData): List<DomainDayBudgetStatus> =
        result.allWidgets.filterIsInstance<DashboardWidget.BudgetBlockParty>().singleOrNull()?.days ?: emptyList()

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
        currencyAssumption = "USER_CONFIRMED"
    )

    private fun pattern(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        next: Long
    ) = RecurringPattern(
        merchantName = merchant,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
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
        frequency: RecurrenceFrequency,
        merchant: String,
        status: String = "PLANNED"
    ): RecurringOccurrence {
        val due = at(day, 0)
        val paid = status == "PAID"
        return RecurringOccurrence(
            sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
            sourceId = ruleId,
            occurrenceKey = "${RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE}|$ruleId|$due|${frequency.name}",
            dueDate = due,
            status = status,
            expectedAmount = amount,
            expectedCurrency = "EUR",
            paidAmount = if (paid) amount else null,
            paidCurrency = if (paid) "EUR" else null,
            frequency = frequency.name,
            merchant = merchant,
            categoryId = null
        )
    }

    /** The planned row RecurringPlanProjectionService writes for a PLANNED occurrence. */
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

    private fun plannedRow(id: Long, status: String = "PLANNED", key: String? = null) = PlannedExpense(
        id = id, description = "Item $id", amount = 100.0, currency = "EUR",
        date = at(20, 0), categoryId = null, isRecurring = key != null,
        priority = PlannedExpensePriority.MUST, sourceOccurrenceKey = key, status = status
    )

    private fun normalizedInput(
        planned: List<PlannedExpense>,
        patterns: List<RecurringPattern> = emptyList()
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
        budgets: List<BudgetStatusSnapshot>,
        goals: List<SavingsGoal> = emptyList()
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
            goals = goals
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

    @Before
    fun setup() {
        val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
        occurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)
        // Honours the requested range like the real query: dueDate >= start AND dueDate < end.
        coEvery { occurrenceDao.getByDateRange(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            storedOccurrences.filter { it.dueDate >= start && it.dueDate < end }
        }
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        coEvery { coordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        analyticsCurrencyNormalizer = mockk(relaxed = true)

        // Real assembler (assembleNormalized and assemble) and real engine, as in production.
        assembler = ForecastInputAssembler(
            timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = mockk(relaxed = true),
            currencyConverter = currencyConverter,
            recurringLifecycleCoordinator = coordinator,
            recurringOccurrenceDao = occurrenceDao,
            databaseReadBarrier = mockk(relaxed = true)
        )

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

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(
                timeProvider,
                recurringOccurrenceDao = occurrenceDao,
                currencyConverter = currencyConverter
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

    companion object {
        private const val TOL = 1e-6
    }
}
