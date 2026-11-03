package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.BlockPartyDay
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.util.Calendar

/**
 * BL-02 (cash basis) for the Block Party calendar.
 *
 * Rule under test: the discretionary pool subtracts exactly the bills that are PLACED on a
 * day of THIS month (at their full cash amount, on their due day), plus PAID occurrences due
 * this month, plus weighted planned items and goal reserves. Therefore, when the pool is not
 * clamped at zero, the sum of the daily targets equals limit - reserves - PAID bills, and an
 * annual/quarterly bill leaves the pool only in its due month.
 *
 * Every expected number below comes from the spec examples or from plain arithmetic on the
 * fixture amounts, never from the engine. The former 1/12 accrual pool (toMonthlyAmount)
 * would fail every month/bill-shaped test below; the test comments name which.
 */
class SynthesisEngineBlockPartyCashBasisTest {

    private val timeProvider = mockk<TimeProvider>()
    private val occurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)

    private class Result(val forecast: FinancialForecast, val days: List<BlockPartyDay>) {
        fun day(n: Int): BlockPartyDay = days.first { it.dayOfMonth == n }
        val targetSum: Double get() = days.sumOf { it.targetBudget }
    }

    // -- fixtures -----------------------------------------------------------

    private fun dayMs(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun rule(
        id: Long,
        frequency: RecurrenceFrequency,
        amount: Double,
        anchor: Long,
        currency: String = "EUR"
    ) = RecurringPattern(
        merchantName = "RULE$id", averageAmount = amount, currency = currency,
        frequency = frequency, periodVarianceDays = 0, amountVariancePercent = 0.0,
        nextExpectedDate = anchor, confidence = 1.0f, previousDates = emptyList(), id = id
    )

    private fun occurrence(
        ruleId: Long,
        dueDate: Long,
        frequency: RecurrenceFrequency,
        amount: Double,
        status: String = "PLANNED",
        currency: String = "EUR",
        paidAmount: Double? = null,
        paidCurrency: String? = null
    ) = RecurringOccurrence(
        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
        sourceId = ruleId,
        occurrenceKey = "$ruleId|$dueDate|$status",
        dueDate = dueDate,
        status = status,
        expectedAmount = amount,
        expectedCurrency = currency,
        paidAmount = paidAmount,
        paidCurrency = paidCurrency,
        frequency = frequency.name,
        merchant = "RULE$ruleId"
    )

    private fun planned(
        id: Long,
        amount: Double,
        date: Long,
        priority: PlannedExpensePriority = PlannedExpensePriority.MUST
    ) = PlannedExpense(
        id = id, description = "PLAN$id", amount = amount, currency = "EUR", date = date,
        categoryId = null, isRecurring = false, priority = priority
    )

    private fun strictGoal(target: Double) = SavingsGoal(
        id = 1L, name = "RESERVE", targetAmount = target, currentAmount = 0.0, targetDate = null,
        protectionLevel = GoalProtectionLevel.STRICT, currency = "EUR",
        currencyAssumption = "EXPLICIT", createdAt = 1L
    )

    private fun overallBudget(limit: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null, budgetAmount = limit, categoryName = null,
        spentAmount = 0.0, remainingAmount = limit, percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK, periodStart = 0, periodEnd = 0
    )

    private fun categoryBudget(categoryId: Long?, amount: Double) = BudgetStatusSnapshot(
        budgetCategoryId = categoryId, budgetAmount = amount, categoryName = null,
        spentAmount = 0.0, remainingAmount = amount, percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK, periodStart = 0, periodEnd = 0
    )

    private fun pace(daysInMonth: Int) = SpendingPace(
        currentMonthSpent = 0.0, daysElapsed = 1, daysInMonth = daysInMonth,
        projectedTotal = 0.0, previousMonthTotal = 0.0, averageMonthlyTotal = 0.0,
        pacePercentage = 0f, paceStatus = PaceStatus.ON_PACE, displayCurrency = "EUR"
    )

    private fun converted(amount: Double, from: String, to: String, rate: Double, now: Long) =
        ConversionOutcome.Converted(
            originalAmount = amount, originalCurrency = CurrencyCode(from),
            convertedAmount = amount * rate, targetCurrency = CurrencyCode(to),
            rateUsed = rate, rateBasis = RateBasis.LATEST_AVAILABLE, rateValidDate = null,
            rateLastUpdated = now, rateSource = "test", conversionPath = ConversionPath.DIRECT
        )

    private fun failed(amount: Double, from: String, to: String) = ConversionOutcome.Failed(
        originalAmount = amount, originalCurrency = from, targetCurrency = to,
        rateBasis = RateBasis.LATEST_AVAILABLE, failureType = ConversionFailureType.MISSING_RATE,
        message = "test failure"
    )

    /**
     * Runs synthesize() then calculateBlockPartyData() exactly as production wires them
     * (same engine, same forecast). The block-party limit defaults to the resolved limit of
     * [statuses]. displayCurrency is EUR so currency conversion is active (not the blank bypass).
     */
    private suspend fun run(
        year: Int,
        month: Int,
        nowDay: Int,
        patterns: List<RecurringPattern>,
        occurrences: List<RecurringOccurrence>,
        plannedItems: List<PlannedExpense> = emptyList(),
        goals: List<SavingsGoal> = emptyList(),
        statuses: List<BudgetStatusSnapshot> = listOf(overallBudget(2000.0)),
        converter: CurrencyConverter = mockk(relaxed = true)
    ): Result {
        val now = dayMs(year, month, nowDay)
        every { timeProvider.now() } returns now
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns occurrences
        val engine = SynthesisEngine(timeProvider, occurrenceDao, currencyConverter = converter)
        val daysInMonth = YearMonth.of(year, month + 1).lengthOfMonth()
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = patterns,
            plannedExpenses = plannedItems,
            savingsGoals = goals,
            budgetStatuses = statuses,
            spendingPace = pace(daysInMonth),
            displayCurrency = "EUR"
        )
        val days = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(daysInMonth) { 0f },
            budgetLimit = SynthesisEngine.resolveBudgetLimit(statuses)
        )
        return Result(forecast, days)
    }

    /** Each day target must be base + the placed amount of that day; the sum is checked by callers. */
    private fun assertTargets(result: Result, base: Double, placedByDay: Map<Int, Double>) {
        result.days.forEach { d ->
            assertEquals("baseTarget day ${d.dayOfMonth}", base, d.baseTarget, 0.001)
            assertEquals(
                "targetBudget day ${d.dayOfMonth}",
                base + (placedByDay[d.dayOfMonth] ?: 0.0),
                d.targetBudget,
                0.001
            )
        }
    }

    // -- golden examples (BL-02) --------------------------------------------

    /** Old accrual pool: 1200/12 = 100 subtracted, base 63.333, sum 3100 -> fails. */
    @Test
    fun `annual bill planned in its due month is subtracted in full and sum equals limit`() = runTest {
        val apr15 = dayMs(2024, Calendar.APRIL, 15)
        val result = run(
            2024, Calendar.APRIL, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, apr15)),
            occurrences = listOf(occurrence(1L, apr15, RecurrenceFrequency.ANNUALLY, 1200.0))
        )
        assertEquals(30, result.days.size)
        assertTargets(result, base = 800.0 / 30.0, placedByDay = mapOf(15 to 1200.0))
        assertEquals(26.667, result.day(15).baseTarget, 0.001)
        assertEquals(1226.667, result.day(15).targetBudget, 0.001)
        assertEquals(2000.0, result.targetSum, 1e-6)
    }

    /**
     * The same annual rule in a month where it is not due (no occurrence row; its anchor month
     * is April): nothing is subtracted. June has 30 days, giving base 66.667. Old accrual still
     * subtracted 1200/12 = 100 every month, giving a 1900 sum -> fails.
     */
    @Test
    fun `annual bill is not subtracted in a month where it is not due`() = runTest {
        val result = run(
            2024, Calendar.JUNE, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, dayMs(2024, Calendar.APRIL, 15))),
            occurrences = emptyList()
        )
        assertEquals(30, result.days.size)
        assertTargets(result, base = 2000.0 / 30.0, placedByDay = emptyMap())
        assertEquals(66.667, result.day(1).baseTarget, 0.001)
        assertEquals(2000.0, result.targetSum, 1e-6)
    }

    /** Same, in a 31-day month (the plan quotes May as 66.667, but 2000/31 is 64.516). */
    @Test
    fun `annual bill is not subtracted in a 31 day month where it is not due`() = runTest {
        val result = run(
            2024, Calendar.MAY, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, dayMs(2024, Calendar.APRIL, 15))),
            occurrences = emptyList()
        )
        assertEquals(31, result.days.size)
        assertTargets(result, base = 2000.0 / 31.0, placedByDay = emptyMap())
        assertEquals(2000.0, result.targetSum, 1e-6)
    }

    /** Old accrual used 50 * 4.33 = 216.5 (not 200) -> pool/base/sum all differ -> fails. */
    @Test
    fun `weekly bill on four days is subtracted four times`() = runTest {
        val days = listOf(3, 10, 17, 24)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(rule(1L, RecurrenceFrequency.WEEKLY, 50.0, dayMs(2024, Calendar.APRIL, 3))),
            occurrences = days.map {
                occurrence(1L, dayMs(2024, Calendar.APRIL, it), RecurrenceFrequency.WEEKLY, 50.0)
            }
        )
        assertTargets(result, base = 60.0, placedByDay = days.associateWith { 50.0 })
        assertEquals(2000.0, result.targetSum, 1e-6)
    }

    /**
     * Annual 1500 (old accrual 125) plus planned 800 exceed the 2000 limit: pool clamps to 0 and
     * the targets sum to bills + planned. Old pool = 2000 - 125 - 800 = 1075 > 0 -> fails.
     */
    @Test
    fun `bills plus planned above the limit leave base zero and sum equals bills plus planned`() = runTest {
        val apr5 = dayMs(2024, Calendar.APRIL, 5)
        val apr20 = dayMs(2024, Calendar.APRIL, 20)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1500.0, apr5)),
            occurrences = listOf(occurrence(1L, apr5, RecurrenceFrequency.ANNUALLY, 1500.0)),
            plannedItems = listOf(planned(1L, 800.0, apr20))
        )
        assertTargets(result, base = 0.0, placedByDay = mapOf(5 to 1500.0, 20 to 800.0))
        assertEquals(2300.0, result.targetSum, 1e-6)
    }

    // -- PAID occurrences (P6-CURRENT-014 pin + cash-basis pool) --------------

    /**
     * PAID rent 800 on day 1: not placed (pin P6-CURRENT-014) yet it leaves the pool.
     * NOTE: a monthly 800 accrues to 800, so the old pool also gave 1200 here; the annual PAID
     * test below is the one that fails on the old pool.
     */
    @Test
    fun `PAID rent is subtracted from the pool but not placed on its day`() = runTest {
        val apr1 = dayMs(2024, Calendar.APRIL, 1)
        val result = run(
            2024, Calendar.APRIL, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.MONTHLY, 800.0, apr1)),
            occurrences = listOf(occurrence(1L, apr1, RecurrenceFrequency.MONTHLY, 800.0, status = "PAID"))
        )
        assertTargets(result, base = 40.0, placedByDay = emptyMap())
        assertEquals(1200.0, result.targetSum, 1e-6)
        assertEquals(0.0, result.day(1).recurringImpact, 1e-9)
        assertTrue(result.day(1).recurringItems.isEmpty())
    }

    /** PAID annual 1200 due this month: pool 800. Old accrual subtracted 100 -> base 63.333 -> fails. */
    @Test
    fun `PAID annual bill leaves the pool at its cash amount`() = runTest {
        val apr1 = dayMs(2024, Calendar.APRIL, 1)
        val result = run(
            2024, Calendar.APRIL, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, apr1)),
            occurrences = listOf(occurrence(1L, apr1, RecurrenceFrequency.ANNUALLY, 1200.0, status = "PAID"))
        )
        assertTargets(result, base = 800.0 / 30.0, placedByDay = emptyMap())
        assertEquals(800.0, result.targetSum, 1e-6)
    }

    /** paidAmount null: PAID falls back to expectedAmount (300). Old accrual for an annual rule was 25 -> fails. */
    @Test
    fun `PAID occurrence with null paidAmount falls back to expectedAmount`() = runTest {
        val apr1 = dayMs(2024, Calendar.APRIL, 1)
        val result = run(
            2024, Calendar.APRIL, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 300.0, apr1)),
            occurrences = listOf(
                occurrence(1L, apr1, RecurrenceFrequency.ANNUALLY, 300.0, status = "PAID", paidAmount = null)
            )
        )
        assertTargets(result, base = 1700.0 / 30.0, placedByDay = emptyMap())
        assertEquals(1700.0, result.targetSum, 1e-6)
    }

    /** paidAmount 250 beats expectedAmount 300: pool 1750. Old accrual used the rule amount -> fails. */
    @Test
    fun `PAID occurrence with paidAmount uses paidAmount over expectedAmount`() = runTest {
        val apr1 = dayMs(2024, Calendar.APRIL, 1)
        val result = run(
            2024, Calendar.APRIL, 15,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 300.0, apr1)),
            occurrences = listOf(
                occurrence(
                    1L, apr1, RecurrenceFrequency.ANNUALLY, 300.0, status = "PAID",
                    paidAmount = 250.0, paidCurrency = "EUR"
                )
            )
        )
        assertTargets(result, base = 1750.0 / 30.0, placedByDay = emptyMap())
        assertEquals(1750.0, result.targetSum, 1e-6)
    }

    // -- invariant -----------------------------------------------------------

    /**
     * Invariant, independent of the examples: sum(targetBudget) = limit - reserves while the
     * pool is not clamped. Mixed April: monthly 900 (d1), annual 1200 (d15), weekly 50 (d3,10,17,24),
     * MUST planned 120 (d8), LIKELY planned 100 at 70% (d22), OPTIONAL planned 500 (ignored),
     * STRICT goal reserve 300, limit 3000. Hand sum: bills 2300 + planned 190 = 2490; pool = 3000 - 300
     * - 2490 = 210; targets = 210 + 2490 = 2700 = limit - reserve. The old accrual pool (1/12 annual,
     * 4.33x weekly) gives a different pool and a sum different from 2700 -> fails.
     */
    @Test
    fun `daily targets sum to limit minus reserves for a mixed month`() = runTest {
        val apr = Calendar.APRIL
        val weeklyDays = listOf(3, 10, 17, 24)
        val result = run(
            2024, apr, 1,
            patterns = listOf(
                rule(1L, RecurrenceFrequency.MONTHLY, 900.0, dayMs(2024, apr, 1)),
                rule(2L, RecurrenceFrequency.ANNUALLY, 1200.0, dayMs(2024, apr, 15)),
                rule(3L, RecurrenceFrequency.WEEKLY, 50.0, dayMs(2024, apr, 3))
            ),
            occurrences = listOf(
                occurrence(1L, dayMs(2024, apr, 1), RecurrenceFrequency.MONTHLY, 900.0),
                occurrence(2L, dayMs(2024, apr, 15), RecurrenceFrequency.ANNUALLY, 1200.0)
            ) + weeklyDays.map {
                occurrence(3L, dayMs(2024, apr, it), RecurrenceFrequency.WEEKLY, 50.0)
            },
            plannedItems = listOf(
                planned(1L, 120.0, dayMs(2024, apr, 8), PlannedExpensePriority.MUST),
                planned(2L, 100.0, dayMs(2024, apr, 22), PlannedExpensePriority.LIKELY),
                planned(3L, 500.0, dayMs(2024, apr, 25), PlannedExpensePriority.OPTIONAL)
            ),
            goals = listOf(strictGoal(300.0)),
            statuses = listOf(overallBudget(3000.0))
        )
        // Harness precondition: the reserve really reached the forecast (synthesize swallows errors).
        assertEquals(300.0, result.forecast.components.goalReserves, 1e-6)
        assertEquals(3000.0 - 300.0, result.targetSum, 1e-6)
        assertEquals(210.0 / 30.0, result.day(2).baseTarget, 0.001)
        assertTrue(result.days.all { it.baseTarget >= 0.0 })
        // Placed amounts sit on their own due days.
        assertEquals(7.0 + 900.0, result.day(1).targetBudget, 0.001)
        assertEquals(7.0 + 1200.0, result.day(15).targetBudget, 0.001)
        assertEquals(7.0 + 70.0, result.day(22).targetBudget, 0.001)
        assertEquals(7.0, result.day(25).targetBudget, 0.001)
    }

    // -- currency conversion ---------------------------------------------------

    /**
     * A bill whose conversion FAILS is counted once and excluded from both the pool and its
     * day, so the invariant still holds without it: EUR annual 1200 (d10) is placed, USD monthly
     * 500 (d20) fails. Pool 800, sum 2000. Old accrual: 100 for the annual -> fails.
     */
    @Test
    fun `failed conversion excludes the item from pool and day and counts it once`() = runTest {
        val converter = mockk<CurrencyConverter>()
        coEvery {
            converter.convertOutcome(any(), eq("USD"), eq("EUR"), any(), any(), any())
        } answers { failed(firstArg(), "USD", "EUR") }
        val apr10 = dayMs(2024, Calendar.APRIL, 10)
        val apr20 = dayMs(2024, Calendar.APRIL, 20)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(
                rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, apr10),
                rule(2L, RecurrenceFrequency.MONTHLY, 500.0, apr20, currency = "USD")
            ),
            occurrences = listOf(
                occurrence(1L, apr10, RecurrenceFrequency.ANNUALLY, 1200.0),
                occurrence(2L, apr20, RecurrenceFrequency.MONTHLY, 500.0, currency = "USD")
            ),
            converter = converter
        )
        assertTargets(result, base = 800.0 / 30.0, placedByDay = mapOf(10 to 1200.0))
        assertEquals(0.0, result.day(20).recurringImpact, 1e-9)
        assertEquals(2000.0, result.targetSum, 1e-6)
        assertEquals(1, result.day(20).conversionFailureCount)
        assertEquals(1, result.days.sumOf { it.conversionFailureCount })
    }

    /** Control for the failure test: a converted bill (rate 2) is placed AND subtracted at the converted amount. */
    @Test
    fun `converted foreign bill is placed and subtracted at the converted amount`() = runTest {
        val apr1 = dayMs(2024, Calendar.APRIL, 1)
        val converter = mockk<CurrencyConverter>()
        coEvery {
            converter.convertOutcome(any(), eq("USD"), eq("EUR"), any(), any(), any())
        } answers { converted(firstArg(), "USD", "EUR", rate = 2.0, now = apr1) }
        val apr10 = dayMs(2024, Calendar.APRIL, 10)
        val apr20 = dayMs(2024, Calendar.APRIL, 20)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(
                rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, apr10),
                rule(2L, RecurrenceFrequency.MONTHLY, 500.0, apr20, currency = "USD")
            ),
            occurrences = listOf(
                occurrence(1L, apr10, RecurrenceFrequency.ANNUALLY, 1200.0),
                occurrence(2L, apr20, RecurrenceFrequency.MONTHLY, 500.0, currency = "USD")
            ),
            statuses = listOf(overallBudget(3000.0)),
            converter = converter
        )
        assertTargets(result, base = 800.0 / 30.0, placedByDay = mapOf(10 to 1200.0, 20 to 1000.0))
        assertEquals(3000.0, result.targetSum, 1e-6)
        assertEquals(0, result.days.sumOf { it.conversionFailureCount })
    }

    // -- IRREGULAR manual rule ---------------------------------------------------

    /**
     * An IRREGULAR manual rule with a PLANNED occurrence is both placed on its day and subtracted
     * from the pool. Pool 1700, base 56.667, day 12 = 356.667, sum 2000. A pool that skips
     * IRREGULAR (as the old accrual did) leaves 2000/30 = 66.667 base and sum 2300 -> fails.
     */
    @Test
    fun `IRREGULAR manual rule with a PLANNED occurrence is placed and subtracted`() = runTest {
        val apr12 = dayMs(2024, Calendar.APRIL, 12)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(rule(1L, RecurrenceFrequency.IRREGULAR, 300.0, apr12)),
            occurrences = listOf(occurrence(1L, apr12, RecurrenceFrequency.IRREGULAR, 300.0))
        )
        assertTargets(result, base = 1700.0 / 30.0, placedByDay = mapOf(12 to 300.0))
        assertEquals(356.667, result.day(12).targetBudget, 0.001)
        assertEquals(2000.0, result.targetSum, 1e-6)
    }

    // -- resolveBudgetLimit ----------------------------------------------------------

    @Test
    fun `resolveBudgetLimit uses the overall budget when it is above zero`() {
        val limit = SynthesisEngine.resolveBudgetLimit(
            listOf(overallBudget(2000.0), categoryBudget(1L, 300.0), categoryBudget(2L, 400.0))
        )
        assertEquals(2000.0, limit, 1e-9)
    }

    @Test
    fun `resolveBudgetLimit falls back to the sum of category budgets when overall is zero or absent`() {
        assertEquals(
            700.0,
            SynthesisEngine.resolveBudgetLimit(
                listOf(overallBudget(0.0), categoryBudget(1L, 300.0), categoryBudget(2L, 400.0))
            ),
            1e-9
        )
        assertEquals(
            700.0,
            SynthesisEngine.resolveBudgetLimit(listOf(categoryBudget(1L, 300.0), categoryBudget(2L, 400.0))),
            1e-9
        )
    }

    /** Contract when no budget is set at all: 0.0 (not null, not an exception). */
    @Test
    fun `resolveBudgetLimit returns zero when no budget is set`() {
        assertEquals(0.0, SynthesisEngine.resolveBudgetLimit(emptyList()), 1e-9)
        assertEquals(0.0, SynthesisEngine.resolveBudgetLimit(listOf(overallBudget(0.0))), 1e-9)
    }

    /**
     * synthesizeInternal and Block Party must read the same limit and agree on the pool in a due
     * month. April 1, annual 1200 due April 15, limit = categories 1500 + 500 = 2000 (no overall
     * budget): synthesize's discretionaryBudget is 2000 - 1200 = 800; Block Party's pool
     * (base * 30) must also be 800. Before the fix Block Party's pool was 1900, 1100 apart -> fails.
     */
    @Test
    fun `synthesize discretionary budget agrees with the block party pool in a due month`() = runTest {
        val apr15 = dayMs(2024, Calendar.APRIL, 15)
        val result = run(
            2024, Calendar.APRIL, 1,
            patterns = listOf(rule(1L, RecurrenceFrequency.ANNUALLY, 1200.0, apr15)),
            occurrences = listOf(occurrence(1L, apr15, RecurrenceFrequency.ANNUALLY, 1200.0)),
            statuses = listOf(categoryBudget(1L, 1500.0), categoryBudget(2L, 500.0))
        )
        val synthPool = result.forecast.components.discretionaryBudget
        val blockPartyPool = result.day(1).baseTarget * result.days.size
        assertEquals(800.0, synthPool, 1e-6)
        assertEquals(synthPool, blockPartyPool, 1e-6)
        assertEquals(2000.0, result.targetSum, 1e-6)
    }
}
