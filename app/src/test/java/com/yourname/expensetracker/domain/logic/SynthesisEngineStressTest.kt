package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress / boundary suite for SynthesisEngine.
 *
 * Consolidation note (B6-5): `SynthesisEngine.synthesize` swallows every non-cancellation
 * exception and returns a zero-confidence fallback forecast, so "assertNotNull(forecast)" can
 * never fail on a logic error. Every scenario here therefore asserts exact `components.*`
 * values (or the exact non-fallback confidence), and scenario families that previously were
 * separate smoke tests are now table-driven with row-naming messages.
 *
 * Time handling: the engine derives calendar fields with `ZoneId.systemDefault()`. The class
 * pins the JVM zone to UTC ([PinnedUtcTimeZoneRule]) so Calendar/java.time fixtures built in
 * this file and the engine agree regardless of the host zone. (This also means no test here
 * exercises a real DST transition; DST behaviour belongs to TimePeriodUtils tests.)
 *
 * Default fixture time is Feb 15 2024 12:00 UTC: 29-day month, day 15, 14 days remaining,
 * month window [Feb 1 00:00, Mar 1 00:00), start of today Feb 15 00:00.
 */
class SynthesisEngineStressTest : AnalyticsEngineTestBase() {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private lateinit var engine: SynthesisEngine

    // ============================================================================
    // FIXTURE HELPERS
    // ============================================================================

    /**
     * Table-row money assertion: a row whose expected amount is the literal 0.0 (zero-fill / clamp)
     * must be bit-exact; every other row is a computed amount compared at cent precision.
     */
    private fun assertRowMoney(expected: Double, actual: Double, message: String) {
        if (expected == 0.0) assertMoneyExact(expected, actual, message)
        else assertMoneyEquals(expected, actual, message)
    }

    private fun createTimeProvider(timestamp: Long) {
        every { timeProvider.now() } returns timestamp
    }

    /** Fixed default "now": Feb 15 2024 12:00 (month argument is 0-based, as in Calendar). */
    private val defaultNow: Long get() = getTimestampForDayOfMonth(2024, 1, 15)

    /** java.time fixture with a 1-based month, in the (pinned) default zone the engine uses. */
    private fun epochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
        milli: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second, milli * 1_000_000)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun engineAt(now: Long): SynthesisEngine {
        createTimeProvider(now)
        return SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
    }

    private fun createRecurringPattern(
        amount: Double = 100.0,
        confidence: Float = 0.95f,
        date: Long = defaultNow,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        merchantName: String = "TestMerchant"
    ) = RecurringPattern(
        merchantName = merchantName,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = date,
        confidence = confidence,
        previousDates = emptyList()
    )

    private fun createPlannedExpense(
        amount: Double = 100.0,
        priority: PlannedExpensePriority = PlannedExpensePriority.MUST,
        date: Long = defaultNow,
        status: String = "PLANNED"
    ) = PlannedExpense(
        id = 0,
        description = "Planned",
        amount = amount,
        date = date,
        categoryId = null,
        isRecurring = false,
        priority = priority,
        status = status
    )

    private fun createSavingsGoal(
        target: Double = 1000.0,
        current: Double = 0.0,
        protection: GoalProtectionLevel = GoalProtectionLevel.STRICT,
        targetDate: Long? = defaultNow + 30L * DAY_MS
    ) = SavingsGoal(
        id = 0,
        name = "TestGoal",
        targetAmount = target,
        currentAmount = current,
        targetDate = targetDate,
        protectionLevel = protection,
        currencyAssumption = "EXPLICIT", // fixture: confirmed provenance so STRICT pro-rating is exercised
        createdAt = 0L,
    )

    private fun createBudgetStatus(
        health: BudgetHealthStatus = BudgetHealthStatus.ON_TRACK,
        limit: Double = 1000.0,
        categoryId: Long? = null,
        spent: Double = 0.0
    ) = BudgetStatusSnapshot(
        budgetCategoryId = categoryId,
        budgetAmount = limit,
        categoryName = null,
        spentAmount = spent,
        remainingAmount = limit - spent,
        percentUsed = if (limit > 0) (spent / limit * 100) else 0.0,
        healthStatus = health,
        periodStart = 0,
        periodEnd = 0
    )

    private fun createPace(
        currentSpent: Double = 500.0,
        daysElapsed: Int = 15,
        daysInMonth: Int = 30,
        paceStatus: PaceStatus = PaceStatus.ON_PACE,
        previousTotal: Double? = 1000.0,
        averageTotal: Double? = 1000.0
    ) = SpendingPace(
        currentMonthSpent = currentSpent,
        daysElapsed = daysElapsed,
        daysInMonth = daysInMonth,
        projectedTotal = currentSpent / (daysElapsed.toDouble() / daysInMonth),
        previousMonthTotal = previousTotal,
        averageMonthlyTotal = averageTotal,
        pacePercentage = if (averageTotal != null && averageTotal > 0) (currentSpent / averageTotal * 100).toFloat() else 0f,
        paceStatus = paceStatus,
        displayCurrency = "EUR",
    )

    @Before
    override fun setUp() {
        super.setUp()
        // Default to middle of month (Feb 15 2024 12:00)
        createTimeProvider(defaultNow)
        engine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
    }

    /**
     * Get timestamp for specific day of month at 12:00 (month is 0-based, as in Calendar).
     */
    private fun getTimestampForDayOfMonth(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ============================================================================
    // SECTION 1: CALENDAR BOUNDARIES (month length, leap year, month window)
    // ============================================================================

    private data class MonthLengthRow(
        val label: String,
        val year: Int,
        val month0: Int,
        val day: Int,
        val expectedProjectedPointCount: Int,
        val expectedPredictedDiscretionary: Double,
        val expectedLastProjectedPoint: Double
    )

    /**
     * Replaces: first day of month, last day of 31-day month, last day of 30-day month,
     * Feb 28 non-leap, Feb 29 leap. History [100, 50] => lastKnownTotal 50. With no recurring
     * patterns and average baseline 1000: typical daily = 1000 / daysInMonth (SynthesisEngine
     * typicalDailyDiscretionary), predicted = typical * remaining days, one projected point per
     * day from today to month end.
     */
    @Test
    fun `month length boundaries - remaining days drive projection size and predicted discretionary`() = runTest {
        val rows = listOf(
            // 1000/31*30 = 967.74; last point 50 + 967.74
            MonthLengthRow("first day of 31-day month", 2024, 0, 1, 31, 967.74, 1017.74),
            MonthLengthRow("last day of 31-day month", 2024, 0, 31, 1, 0.0, 50.0),
            MonthLengthRow("last day of 30-day month", 2024, 3, 30, 1, 0.0, 50.0),
            MonthLengthRow("Feb 28 non-leap year is last day", 2023, 1, 28, 1, 0.0, 50.0),
            MonthLengthRow("Feb 29 leap year is last day", 2024, 1, 29, 1, 0.0, 50.0),
            // Leap year: Feb 28 still has one remaining day; 1000/29 = 34.48; last point 50 + 34.48
            MonthLengthRow("Feb 28 leap year has one day remaining", 2024, 1, 28, 2, 34.48, 84.48)
        )
        assertEquals("row guard", 6, rows.size)

        for (row in rows) {
            val forecast = engineAt(getTimestampForDayOfMonth(row.year, row.month0, row.day)).synthesize(
                pastSumDaily = listOf(100.0, 50.0),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            val c = forecast.components
            assertEquals("${row.label}: past points", listOf(100.0, 50.0), c.pastSpendingPoints)
            assertEquals("${row.label}: projected point count", row.expectedProjectedPointCount, c.projectedSpendingPoints.size)
            assertEquals("${row.label}: first projected point is last known total", 50.0, c.projectedSpendingPoints.first(), 0.0)
            assertMoneyEquals(row.expectedLastProjectedPoint, c.projectedSpendingPoints.last(), "${row.label}: last projected point")
            assertRowMoney(row.expectedPredictedDiscretionary, c.predictedDiscretionary, "${row.label}: predicted discretionary")
            assertEquals("${row.label}: nothing committed", 0.0, c.totalCommitted, 0.0)
        }
    }

    private data class RecurringWindowRow(
        val label: String,
        val nowMs: Long,
        val patternDateMs: Long,
        val expectedCommitted: Double
    )

    /**
     * Replaces: month transition edge case, DST spring forward (Mar 31, last day of month),
     * first-day-of-month recurring. A confidence-0.95 pattern is committed only when its
     * nextExpectedDate is in [startOfToday, endOfMonthExclusive).
     */
    @Test
    fun `recurring committed window - includes start of today and last ms of month, excludes outside`() = runTest {
        val rows = listOf(
            RecurringWindowRow("first day, due today", epochMs(2024, 1, 1), epochMs(2024, 1, 1), 100.0),
            RecurringWindowRow("last second of month, due earlier today", epochMs(2024, 1, 31, 23, 59, 59), epochMs(2024, 1, 31), 100.0),
            RecurringWindowRow("last second of month, due next month", epochMs(2024, 1, 31, 23, 59, 59), epochMs(2024, 2, 5), 0.0),
            RecurringWindowRow("due yesterday", epochMs(2024, 1, 31), epochMs(2024, 1, 30), 0.0),
            RecurringWindowRow("last day of March, due today", epochMs(2024, 3, 31), epochMs(2024, 3, 31), 100.0),
            RecurringWindowRow("due exactly at exclusive month end", epochMs(2024, 1, 15), epochMs(2024, 2, 1, 0, 0, 0, 0), 0.0),
            RecurringWindowRow("due 1ms before month end", epochMs(2024, 1, 15), epochMs(2024, 1, 31, 23, 59, 59, 999), 100.0),
            RecurringWindowRow("due exactly at start of today", epochMs(2024, 1, 15), epochMs(2024, 1, 15, 0, 0, 0, 0), 100.0),
            RecurringWindowRow("due 1ms before start of today", epochMs(2024, 1, 15), epochMs(2024, 1, 14, 23, 59, 59, 999), 0.0)
        )
        assertEquals("row guard", 9, rows.size)

        for (row in rows) {
            val forecast = engineAt(row.nowMs).synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.95f, date = row.patternDateMs)),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            // The fallback forecast also reports zero totals; the normal path always projects >= 1 day
            // (today..month end), so a non-empty projection proves the totals below are real.
            assertTrue("${row.label}: not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
            assertEquals("${row.label}: totalCommitted", row.expectedCommitted, forecast.components.totalCommitted, 0.0)
            assertEquals("${row.label}: totalLikely", 0.0, forecast.components.totalLikely, 0.0)
        }
    }

    private data class PlannedWindowRow(
        val label: String,
        val dateMs: Long,
        val priority: PlannedExpensePriority,
        val status: String,
        val expectedCommitted: Double,
        val expectedLikely: Double,
        val expectedListedPlannedCount: Int
    )

    /**
     * Replaces: DST fall back (planned expense the day after), planned expense on past date.
     * Planned amount 40.0; MUST counts 100% as committed, LIKELY 70% as likely, OPTIONAL
     * nothing; only [start of today, end of month) counts; non-PLANNED status is dropped
     * entirely (FCST-N4) and not listed.
     */
    @Test
    fun `planned expense window and priority weights - committed likely optional and non-planned status`() = runTest {
        val startOfToday = epochMs(2024, 2, 15, 0, 0, 0, 0)
        val rows = listOf(
            PlannedWindowRow("MUST 1ms before start of today", startOfToday - 1, PlannedExpensePriority.MUST, "PLANNED", 0.0, 0.0, 1),
            PlannedWindowRow("MUST exactly at start of today", startOfToday, PlannedExpensePriority.MUST, "PLANNED", 40.0, 0.0, 1),
            PlannedWindowRow("MUST the day after", epochMs(2024, 2, 16), PlannedExpensePriority.MUST, "PLANNED", 40.0, 0.0, 1),
            PlannedWindowRow("MUST last ms of month", epochMs(2024, 3, 1, 0, 0, 0, 0) - 1, PlannedExpensePriority.MUST, "PLANNED", 40.0, 0.0, 1),
            PlannedWindowRow("MUST exactly at exclusive month end", epochMs(2024, 3, 1, 0, 0, 0, 0), PlannedExpensePriority.MUST, "PLANNED", 0.0, 0.0, 1),
            PlannedWindowRow("LIKELY today is 70%", defaultNow, PlannedExpensePriority.LIKELY, "PLANNED", 0.0, 28.0, 1),
            PlannedWindowRow("OPTIONAL today counts nothing", defaultNow, PlannedExpensePriority.OPTIONAL, "PLANNED", 0.0, 0.0, 1),
            PlannedWindowRow("MUST today with non-PLANNED status is dropped", defaultNow, PlannedExpensePriority.MUST, "CONVERTED", 0.0, 0.0, 0)
        )
        assertEquals("row guard", 8, rows.size)

        for (row in rows) {
            val forecast = engineAt(defaultNow).synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = listOf(createPlannedExpense(amount = 40.0, priority = row.priority, date = row.dateMs, status = row.status)),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            assertTrue("${row.label}: not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
            assertEquals("${row.label}: totalCommitted", row.expectedCommitted, forecast.components.totalCommitted, 0.0)
            assertRowMoney(row.expectedLikely, forecast.components.totalLikely, "${row.label}: totalLikely")
            assertEquals("${row.label}: listed planned", row.expectedListedPlannedCount, forecast.components.plannedExpenses.size)
        }
    }

    // ============================================================================
    // SECTION 2: LARGE / HOSTILE DATASETS
    // ============================================================================

    private data class HistoryRow(
        val label: String,
        val history: List<Double>,
        val expectedSize: Int,
        val expectedLast: Double
    )

    /**
     * Replaces: 10000-day history, 5 years of data, year view history, NaN/Infinity inputs.
     * Non-finite history points are replaced by the last finite value (0.0 when none).
     * With no patterns the first projected point equals the last known total.
     */
    @Test
    fun `history sizes and non-finite sanitisation - past points preserved and last value carried`() = runTest {
        val rows = listOf(
            HistoryRow("10000 days", (1..10000).map { it * 10.0 }, 10000, 100000.0),
            HistoryRow("5 years", (1..1826).map { it * 25.0 }, 1826, 45650.0),
            HistoryRow("one year", List(365) { (it + 1) * 10.0 }, 365, 3650.0),
            HistoryRow("NaN then Infinity with no finite value", listOf(Double.NaN, Double.POSITIVE_INFINITY), 2, 0.0),
            HistoryRow("finite then NaN carries last finite", listOf(100.0, Double.NaN), 2, 100.0)
        )
        assertEquals("row guard", 5, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = row.history,
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            val c = forecast.components
            assertEquals("${row.label}: past point count", row.expectedSize, c.pastSpendingPoints.size)
            assertEquals("${row.label}: last past point", row.expectedLast, c.pastSpendingPoints.last(), 0.0)
            assertTrue("${row.label}: all past points finite", c.pastSpendingPoints.all { it.isFinite() })
            assertEquals("${row.label}: first projected point", row.expectedLast, c.projectedSpendingPoints.first(), 0.0)
            // 0.85 - 0.15 (no budget) - 0.05 (no recurring) = 0.65; the fallback forecast would be 0.0.
            assertEquals("${row.label}: not the fallback forecast", 0.65, forecast.confidence, 1e-9)
        }
    }

    /**
     * Replaces: 500 recurring patterns, 1000 planned expenses, combined large datasets,
     * Calendar instance reuse. Everything is dated "today" so every item is in the window.
     *
     * Patterns: 500 x 10.00 MONTHLY; even index 0.95 (committed), odd 0.80 (likely) => 250 each.
     * Planned: 1000 x 5.00, priority = entries[i % 3]: MUST i%3==0 -> 333, LIKELY i%3==1 -> 334,
     * OPTIONAL 333 (ignored).
     * Goals: 10 STRICT, target i*1000, current i*500, due in 30 days => reserve 500i * 29/30.
     * Budget: five category budgets i*500 (sum 7500), spent 5000, average baseline 8000.
     */
    @Test
    fun `scale - 500 patterns 1000 planned 10 goals aggregate to exact component totals`() = runTest {
        val patterns = (1..500).map { i ->
            createRecurringPattern(amount = 10.0, confidence = if (i % 2 == 0) 0.95f else 0.80f)
        }
        val planned = (1..1000).map { i ->
            createPlannedExpense(amount = 5.0, priority = PlannedExpensePriority.entries[i % 3])
        }
        val goals = (1..10).map { i ->
            createSavingsGoal(target = i * 1000.0, current = i * 500.0, protection = GoalProtectionLevel.STRICT)
        }
        val budgets = (1..5).map { i -> createBudgetStatus(limit = i * 500.0, categoryId = i.toLong()) }

        val forecast = engine.synthesize(
            pastSumDaily = List(1000) { (it + 1) * 50.0 },
            recurringPatterns = patterns,
            plannedExpenses = planned,
            savingsGoals = goals,
            budgetStatuses = budgets,
            spendingPace = createPace(currentSpent = 5000.0, averageTotal = 8000.0, previousTotal = 7500.0)
        )
        val c = forecast.components

        assertEquals("recurring listed", 500, c.recurringExpenses.size)
        assertEquals("planned listed", 1000, c.plannedExpenses.size)
        // committed: 250 x 10 patterns + 333 x 5 MUST planned
        assertMoneyEquals(4165.00, c.totalCommitted, "totalCommitted")
        // likely: 250 x 10 patterns + 334 x 5 x 0.7 LIKELY planned
        assertMoneyEquals(3669.00, c.totalLikely, "totalLikely")
        // sum_{i=1..10} 500i * 29/30
        assertMoneyEquals(26583.33, c.goalReserves, "goalReserves")
        // monthly recurring total 500 x 10 = 5000 reduces the 8000 baseline: (8000 - 5000) / 29 * 14 remaining days
        assertMoneyEquals(1448.28, c.predictedDiscretionary, "predictedDiscretionary")
        assertEquals("discretionary clamped at zero", 0.0, c.discretionaryBudget, 0.0)
        // FORE-05 rule: spent 5000 + obligations 4165 + 3669 + reserves 26583 exceed the 7500 limit => CRITICAL
        assertEquals("risk", RiskLevel.CRITICAL, c.riskLevel)
        assertEquals("past point count", 1000, c.pastSpendingPoints.size)
        assertEquals("last past point", 50000.0, c.pastSpendingPoints.last(), 0.0)
        assertEquals("projected point count (day 15..29)", 15, c.projectedSpendingPoints.size)
        // 50000 + 2500 committed patterns + 1665 MUST planned + 1169 LIKELY planned
        assertMoneyEquals(55334.00, c.projectedSpendingPoints.first(), "first projected point")
        assertMoneyEquals(56782.28, c.projectedSpendingPoints.last(), "last projected point")
        assertEquals("confidence (full data, not fallback)", 0.85, forecast.confidence, 1e-9)
        assertEquals("excluded count", 0, forecast.excludedCount)
        assertFalse("not partial", forecast.isPartial)
    }

    // ============================================================================
    // SECTION 3: BUDGET / DISCRETIONARY EDGE CASES
    // ============================================================================

    @Test
    fun `stress - zero budget with spending`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = listOf(100.0, 50.0),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(currentSpent = 200.0)
        )

        // The fallback forecast also reports discretionary 0.0 / confidence 0.0: prove the normal path.
        // 0.85 - 0.15 (no budget) - 0.05 (no recurring) = 0.65 (SynthesisEngine confidence block).
        assertEquals("not the fallback forecast", 0.65, forecast.confidence, 1e-9)
        assertTrue("not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
        // With no budget, discretionary should be 0
        assertMoneyExact(0.0, forecast.components.discretionaryBudget, "discretionaryBudget")
    }

    private data class BudgetLimitRow(
        val label: String,
        val budgets: List<BudgetStatusSnapshot>,
        val expectedDiscretionary: Double
    )

    /**
     * Replaces: zero budget but has category budgets, null budget amounts. Pace spent is 500,
     * no obligations: discretionary = limit - 500, clamped at 0, where limit is the overall
     * budget when > 0, else the sum of category budgets.
     */
    @Test
    fun `budget limit resolution - overall wins when positive otherwise category sum`() = runTest {
        val rows = listOf(
            BudgetLimitRow("overall 1000 only", listOf(createBudgetStatus(limit = 1000.0)), 500.0),
            BudgetLimitRow("overall row with zero amount only", listOf(createBudgetStatus(limit = 0.0)), 0.0),
            BudgetLimitRow(
                "categories 500 + 300 only",
                listOf(createBudgetStatus(limit = 500.0, categoryId = 1), createBudgetStatus(limit = 300.0, categoryId = 2)),
                300.0
            ),
            BudgetLimitRow(
                "overall 1000 wins over categories 500",
                listOf(createBudgetStatus(limit = 1000.0), createBudgetStatus(limit = 500.0, categoryId = 1)),
                500.0
            ),
            BudgetLimitRow(
                "overall zero falls back to categories 500 + 300",
                listOf(
                    createBudgetStatus(limit = 0.0),
                    createBudgetStatus(limit = 500.0, categoryId = 1),
                    createBudgetStatus(limit = 300.0, categoryId = 2)
                ),
                300.0
            )
        )
        assertEquals("row guard", 5, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = row.budgets,
                spendingPace = createPace()
            )
            assertTrue("${row.label}: not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
            assertRowMoney(row.expectedDiscretionary, forecast.components.discretionaryBudget, "${row.label}: discretionaryBudget")
        }
    }

    @Test
    fun `stress - spending exceeds all budgets`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = listOf(5000.0),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 100.0, spent = 200.0, health = BudgetHealthStatus.EXCEEDED)
            ),
            spendingPace = createPace(
                currentSpent = 5000.0,
                averageTotal = 1000.0,
                paceStatus = PaceStatus.OVER_PACE
            )
        )

        // Should show critical risk
        assertEquals(RiskLevel.CRITICAL, forecast.components.riskLevel)
    }

    /**
     * High committed + likely + goal reserve against a tiny budget must clamp to exactly 0.0,
     * never go negative. committed 500 + MUST 400 = 900; likely 300 + 0.7 x 300 = 510;
     * goal 1000 due in 30 days of a 29-day month => 1000 * 29/30 = 966.67.
     */
    @Test
    fun `stress - negative discretionary budget clamps to zero`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(amount = 500.0, confidence = 0.95f),
                createRecurringPattern(amount = 300.0, confidence = 0.85f)
            ),
            plannedExpenses = listOf(
                createPlannedExpense(amount = 400.0, priority = PlannedExpensePriority.MUST),
                createPlannedExpense(amount = 300.0, priority = PlannedExpensePriority.LIKELY)
            ),
            savingsGoals = listOf(
                createSavingsGoal(target = 1000.0, current = 0.0)
            ),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 100.0) // Very low budget
            ),
            spendingPace = createPace()
        )
        val c = forecast.components

        assertMoneyEquals(900.00, c.totalCommitted, "totalCommitted")
        assertMoneyEquals(510.00, c.totalLikely, "totalLikely")
        assertMoneyEquals(966.67, c.goalReserves, "goalReserves")
        assertEquals("discretionary clamped to exactly zero", 0.0, c.discretionaryBudget, 0.0)
        // FORE-05: spent 500 + obligations and reserve 2376 exceed the 100 limit, so CRITICAL even though on pace (was HIGH).
        assertEquals("obligations exceed the limit while on pace", RiskLevel.CRITICAL, c.riskLevel)
    }

    /**
     * LOG-021 discretionary pool: limit - (spent + committed + likely + goal reserve).
     * 1000 - (200 + (100 + 50) + 0 + 500 * 29/30) = 166.67; buffer 16.7% => MEDIUM.
     */
    @Test
    fun `regression - discretionary pool formula`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.95f)),
            plannedExpenses = listOf(createPlannedExpense(amount = 50.0, priority = PlannedExpensePriority.MUST)),
            savingsGoals = listOf(createSavingsGoal(target = 1000.0, current = 500.0, protection = GoalProtectionLevel.STRICT)),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = createPace(currentSpent = 200.0)
        )
        val c = forecast.components

        assertMoneyEquals(150.00, c.totalCommitted, "totalCommitted")
        assertMoneyExact(0.0, c.totalLikely, "totalLikely")
        assertMoneyEquals(483.33, c.goalReserves, "goalReserves")
        assertMoneyEquals(166.67, c.discretionaryBudget, "discretionaryBudget")
        assertEquals(RiskLevel.MEDIUM, c.riskLevel)
    }

    @Test
    fun `stress - extremely large amounts stay finite and clamp discretionary to zero`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(amount = Double.MAX_VALUE / 2)
            ),
            plannedExpenses = listOf(
                createPlannedExpense(amount = Double.MAX_VALUE / 4)
            ),
            savingsGoals = listOf(
                createSavingsGoal(target = Double.MAX_VALUE / 10, current = 0.0)
            ),
            budgetStatuses = listOf(
                createBudgetStatus(limit = Double.MAX_VALUE / 100)
            ),
            spendingPace = createPace(currentSpent = Double.MAX_VALUE / 1000)
        )
        val c = forecast.components

        // A non-fallback forecast proves no overflow reached the finite-value invariants
        // (ForecastComponents would throw and the engine would return confidence 0.0).
        assertEquals("not the fallback forecast", 0.85, forecast.confidence, 1e-9)
        assertTrue("totalCommitted finite and positive", c.totalCommitted.isFinite() && c.totalCommitted > 0.0)
        assertTrue("goalReserves finite and positive", c.goalReserves.isFinite() && c.goalReserves > 0.0)
        assertTrue("projected points finite", c.projectedSpendingPoints.all { it.isFinite() })
        assertEquals("discretionary", 0.0, c.discretionaryBudget, 0.0)
        // FORE-05: spent + obligations + goal reserve exceed the limit while on pace, so CRITICAL (was HIGH).
        assertEquals("risk", RiskLevel.CRITICAL, c.riskLevel)
    }

    @Test
    fun `stress - extremely small amounts aggregate to cents`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = listOf(0.01, 0.02),
            recurringPatterns = listOf(
                createRecurringPattern(amount = 0.01)
            ),
            plannedExpenses = listOf(
                createPlannedExpense(amount = 0.01)
            ),
            savingsGoals = listOf(
                createSavingsGoal(target = 0.01, current = 0.0)
            ),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 0.01)
            ),
            spendingPace = createPace(currentSpent = 0.01)
        )
        val c = forecast.components

        assertEquals("not the fallback forecast", 0.85, forecast.confidence, 1e-9)
        assertEquals("past points", listOf(0.01, 0.02), c.pastSpendingPoints)
        assertMoneyEquals(0.02, c.totalCommitted, "totalCommitted (0.01 pattern + 0.01 MUST)")
        assertEquals("discretionary", 0.0, c.discretionaryBudget, 0.0)
        // FORE-05: spent 0.01 + obligations 0.02 + goal reserve 0.01 exceed the 0.01 limit while on pace (was HIGH).
        assertEquals("risk", RiskLevel.CRITICAL, c.riskLevel)
    }

    // ============================================================================
    // SECTION 4: GOAL RESERVES
    // ============================================================================

    private data class GoalRow(
        val label: String,
        val target: Double,
        val current: Double,
        val protection: GoalProtectionLevel,
        val targetDateOffsetMs: Long?,
        val expectedReserve: Double,
        /** True when the reserve is the unmodified remaining amount (no pro-rating arithmetic). */
        val passThrough: Boolean = false
    )

    /**
     * Replaces: extreme goal reserves (past due), far-future goal, null targetDate.
     * Reserve = remaining / max(1, daysToTarget / daysInMonth) for STRICT unfinished goals; due now,
     * past due or undated goals reserve the full remaining amount. 29-day month.
     */
    @Test
    fun `goal reserves - past due undated far future pro-rated and excluded goals`() = runTest {
        val rows = listOf(
            GoalRow("past due: full remaining 9000", 10000.0, 1000.0, GoalProtectionLevel.STRICT, -DAY_MS, 9000.00, passThrough = true),
            // 120000 / (3650 / 29) = 953.42
            GoalRow("10 years out: heavily pro-rated", 120000.0, 0.0, GoalProtectionLevel.STRICT, 3650L * DAY_MS, 953.42),
            GoalRow("no target date: full remaining", 1000.0, 0.0, GoalProtectionLevel.STRICT, null, 1000.00, passThrough = true),
            // 500 / (30 / 29) = 483.33
            GoalRow("due in 30 days", 1000.0, 500.0, GoalProtectionLevel.STRICT, 30L * DAY_MS, 483.33),
            GoalRow("WARNING protection is not reserved", 1000.0, 0.0, GoalProtectionLevel.WARNING, 30L * DAY_MS, 0.0),
            GoalRow("TRACKING protection is not reserved", 1000.0, 0.0, GoalProtectionLevel.TRACKING, 30L * DAY_MS, 0.0),
            GoalRow("already funded goal is not reserved", 1000.0, 1000.0, GoalProtectionLevel.STRICT, 30L * DAY_MS, 0.0)
        )
        assertEquals("row guard", 7, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = listOf(
                    createSavingsGoal(
                        target = row.target,
                        current = row.current,
                        protection = row.protection,
                        targetDate = row.targetDateOffsetMs?.let { defaultNow + it }
                    )
                ),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            assertTrue("${row.label}: not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
            if (row.passThrough) {
                // remaining = target - current, returned unmodified (SynthesisEngine due-now / undated branch)
                assertMoneyExact(row.expectedReserve, forecast.components.goalReserves, "${row.label}: goalReserves")
            } else {
                assertRowMoney(row.expectedReserve, forecast.components.goalReserves, "${row.label}: goalReserves")
            }
            assertEquals("${row.label}: no currency exclusions", 0, forecast.components.goalReserveExclusions)
        }
    }

    // ============================================================================
    // SECTION 5: SPENDING PACE BASELINES
    // ============================================================================

    private data class PaceRow(
        val label: String,
        val daysElapsed: Int,
        val daysInMonth: Int,
        val spent: Double,
        val average: Double?,
        val previous: Double?,
        val expectedPredicted: Double,
        val expectedConfidence: Double
    )

    /**
     * Replaces: all nullable fields null, partially null pace, empty lists for all collections,
     * zero days in month. The engine ignores pace.daysElapsed/daysInMonth and derives the month from
     * the clock; typical daily = (average ?: previous ?: 0) / 29, predicted = typical * 14.
     * Confidence = 0.85 - 0.15 (no budget) - 0.10 (no average) - 0.05 (no recurring).
     */
    @Test
    fun `pace baselines - average then previous then none drive predicted discretionary and confidence`() = runTest {
        val rows = listOf(
            PaceRow("all nullable baselines null", 0, 30, 0.0, null, null, 0.0, 0.55),
            PaceRow("partially null pace", 15, 30, 500.0, null, null, 0.0, 0.55),
            PaceRow("single day elapsed, empty inputs", 1, 30, 0.0, null, null, 0.0, 0.55),
            PaceRow("invalid zero days in month", 0, 0, 0.0, null, null, 0.0, 0.55),
            // 1400 / 29 * 14 = 675.86
            PaceRow("previous month is the fallback baseline", 15, 30, 500.0, null, 1400.0, 675.86, 0.55),
            // 2000 / 29 * 14 = 965.52; average present => no baseline penalty
            PaceRow("average wins over previous month", 15, 30, 500.0, 2000.0, 1400.0, 965.52, 0.65)
        )
        assertEquals("row guard", 6, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = SpendingPace(
                    currentMonthSpent = row.spent,
                    daysElapsed = row.daysElapsed,
                    daysInMonth = row.daysInMonth,
                    projectedTotal = 0.0,
                    previousMonthTotal = row.previous,
                    averageMonthlyTotal = row.average,
                    pacePercentage = 0f,
                    paceStatus = PaceStatus.ON_PACE,
                    displayCurrency = "EUR",
                )
            )
            val c = forecast.components
            assertRowMoney(row.expectedPredicted, c.predictedDiscretionary, "${row.label}: predictedDiscretionary")
            assertEquals("${row.label}: confidence", row.expectedConfidence, forecast.confidence, 1e-9)
            assertEquals("${row.label}: discretionary without budget", 0.0, c.discretionaryBudget, 0.0)
            assertEquals("${row.label}: risk without budget", RiskLevel.LOW, c.riskLevel)
        }
    }

    // ============================================================================
    // SECTION 6: CONCURRENT ACCESS / REPEATED CALLS
    // ============================================================================

    @Test
    fun `stress - 10 concurrent synthesize calls`() = runTest {
        val latch = CountDownLatch(10)
        val errors = AtomicInteger(0)
        val committed = DoubleArray(10) { Double.NaN }

        val executor = Executors.newFixedThreadPool(10)
        try {
            repeat(10) { i ->
                executor.submit {
                    try {
                        runBlocking {
                            val forecast = engine.synthesize(
                                pastSumDaily = listOf(100.0 * i),
                                recurringPatterns = listOf(
                                    createRecurringPattern(amount = 100.0 + i * 10.0)
                                ),
                                plannedExpenses = emptyList(),
                                savingsGoals = emptyList(),
                                budgetStatuses = emptyList(),
                                spendingPace = createPace()
                            )
                            committed[i] = forecast.components.totalCommitted
                        }
                    } catch (e: Exception) {
                        // Counted (not swallowed): any failure, including cancellation, fails the test below.
                        errors.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            // Every task counts down in `finally`; the timeout is only a hang guard and its result
            // is asserted (an ignored timeout result could pass vacuously).
            assertTrue("latch timed out", latch.await(60, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }

        assertEquals(0, errors.get())
        // Each thread must see its own result (no shared mutable state between calls).
        for (i in 0 until 10) {
            assertEquals("thread $i committed", 100.0 + i * 10.0, committed[i], 0.0)
        }
    }

    @Test
    fun `stress - 50 concurrent synthesize calls`() = runTest {
        // No try/catch: any failure (including cancellation) propagates through awaitAll and fails the test.
        val results = withContext(Dispatchers.Default) {
            (1..50).map { i ->
                async {
                    i to engine.synthesize(
                        pastSumDaily = listOf(100.0 * i),
                        recurringPatterns = listOf(createRecurringPattern(amount = 100.0 + i)),
                        plannedExpenses = emptyList(),
                        savingsGoals = emptyList(),
                        budgetStatuses = emptyList(),
                        spendingPace = createPace()
                    )
                }
            }.awaitAll()
        }

        assertEquals(50, results.size)
        for ((i, forecast) in results) {
            assertEquals("call $i committed", 100.0 + i, forecast.components.totalCommitted, 0.0)
            assertEquals("call $i past points", listOf(100.0 * i), forecast.components.pastSpendingPoints)
        }
    }

    @Test
    fun `stress - rapid sequential calls re-read the clock every call`() = runTest {
        repeat(100) { i ->
            val timestamp = defaultNow + i * 1000L
            every { timeProvider.now() } returns timestamp

            val forecast = engine.synthesize(
                pastSumDaily = listOf(100.0 + i),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )

            assertEquals("call $i generatedAt", Instant.ofEpochMilli(timestamp), forecast.generatedAt)
            assertEquals("call $i past points", listOf(100.0 + i), forecast.components.pastSpendingPoints)
            assertEquals("call $i first projected point", 100.0 + i, forecast.components.projectedSpendingPoints.first(), 0.0)
        }
    }

    // ============================================================================
    // SECTION 7: RECURRING FREQUENCY
    // ============================================================================

    private data class FrequencyRow(
        val frequency: RecurrenceFrequency,
        val amount: Double,
        val expectedPredictedDiscretionary: Double,
        /** Committed total; defaults to one raw occurrence (every frequency except WEEKLY/BIWEEKLY). */
        val expectedCommitted: Double = amount
    )

    /**
     * One pattern dated today, confidence 0.95, against average baseline 1000 in a 29-day month with
     * 14 days remaining. The monthly-equivalent (RecurrenceCalculator multipliers: weekly 4.33,
     * biweekly 2.17, quarterly 1/3, semi-annual 1/6, annual 1/12; IRREGULAR excluded) reduces the
     * baseline: predicted = (1000 - monthly) / 29 * 14. The committed total is the raw per-occurrence
     * amount per in-window occurrence (one for every frequency except WEEKLY/BIWEEKLY, see FORE-02).
     */
    @Test
    fun `recurring frequency - monthly equivalent reduces predicted discretionary`() = runTest {
        val rows = listOf(
            // FORE-02: predicted discretionary is unchanged; committed now projects every in-window occurrence
            // (today Feb 15: weekly 15/22/29 = 3 x 100, biweekly 15/29 = 2 x 100) instead of one raw occurrence.
            FrequencyRow(RecurrenceFrequency.WEEKLY, 100.0, 273.72, expectedCommitted = 300.0), // monthly 433 -> 567/29*14
            FrequencyRow(RecurrenceFrequency.BIWEEKLY, 100.0, 378.00, expectedCommitted = 200.0), // monthly 217 -> 783/29*14
            FrequencyRow(RecurrenceFrequency.MONTHLY, 100.0, 434.48), // monthly 100 -> 900/29*14
            FrequencyRow(RecurrenceFrequency.QUARTERLY, 300.0, 434.48), // 300/3 = 100
            FrequencyRow(RecurrenceFrequency.SEMI_ANNUALLY, 600.0, 434.48), // 600/6 = 100
            FrequencyRow(RecurrenceFrequency.ANNUALLY, 1200.0, 434.48), // 1200/12 = 100
            FrequencyRow(RecurrenceFrequency.IRREGULAR, 100.0, 482.76) // excluded -> 1000/29*14
        )
        assertEquals("row guard", RecurrenceFrequency.entries.size, rows.size)
        assertEquals("every frequency has a row", RecurrenceFrequency.entries.toSet(), rows.map { it.frequency }.toSet())

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = listOf(
                    createRecurringPattern(amount = row.amount, frequency = row.frequency)
                ),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            assertMoneyEquals(row.expectedPredictedDiscretionary, forecast.components.predictedDiscretionary, "${row.frequency}: predictedDiscretionary")
            assertEquals("${row.frequency}: committed", row.expectedCommitted, forecast.components.totalCommitted, 0.0)
        }
    }

    // ============================================================================
    // SECTION 8: RISK LEVEL DETERMINATION
    // ============================================================================

    @Test
    fun `stress - risk level with zero budget but on pace`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(), // No budget
            spendingPace = createPace(
                paceStatus = PaceStatus.ON_PACE,
                currentSpent = 500.0,
                averageTotal = 1000.0
            )
        )

        // No budget = LOW risk if on pace
        assertEquals(RiskLevel.LOW, forecast.components.riskLevel)
    }

    @Test
    fun `stress - risk level with zero budget and over pace`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(), // No budget
            spendingPace = createPace(
                paceStatus = PaceStatus.OVER_PACE,
                currentSpent = 2000.0,
                averageTotal = 1000.0
            )
        )

        // The fallback forecast is also MEDIUM: prove the normal path (0.85 - 0.15 no budget - 0.05 no recurring).
        assertEquals("not the fallback forecast", 0.65, forecast.confidence, 1e-9)
        assertTrue("not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
        // No budget but over pace = MEDIUM risk
        assertEquals(RiskLevel.MEDIUM, forecast.components.riskLevel)
    }

    private data class RiskBoundaryRow(
        val label: String,
        val spent: Double,
        val paceStatus: PaceStatus,
        val expectedDiscretionary: Double,
        val expectedRisk: RiskLevel
    )

    /**
     * determineRiskLevel with a 1000 overall budget, no obligations and no goals, so
     * discretionary = 1000 - spent and bufferRatio = discretionary / 1000. Comparisons are
     * inclusive (<=): over pace && ratio <= 0.05 -> CRITICAL, else over pace -> HIGH;
     * ratio <= 0.10 -> HIGH; ratio <= 0.20 -> MEDIUM; else LOW. Each threshold is hit exactly
     * (50/1000, 100/1000, 200/1000 are the nearest doubles to 0.05, 0.1, 0.2) and one unit beyond.
     */
    @Test
    fun `risk level - exact buffer boundaries 0-05 0-10 and 0-20 are inclusive`() = runTest {
        val rows = listOf(
            RiskBoundaryRow("over pace, buffer 0.051 is above critical", 949.0, PaceStatus.OVER_PACE, 51.0, RiskLevel.HIGH),
            RiskBoundaryRow("over pace, buffer exactly 0.05 is critical", 950.0, PaceStatus.OVER_PACE, 50.0, RiskLevel.CRITICAL),
            RiskBoundaryRow("over pace, zero buffer is critical", 1000.0, PaceStatus.OVER_PACE, 0.0, RiskLevel.CRITICAL),
            RiskBoundaryRow("over pace, ample buffer is high", 600.0, PaceStatus.OVER_PACE, 400.0, RiskLevel.HIGH),
            RiskBoundaryRow("on pace, buffer exactly 0.05 is only high", 950.0, PaceStatus.ON_PACE, 50.0, RiskLevel.HIGH),
            RiskBoundaryRow("on pace, buffer 0.101 is medium", 899.0, PaceStatus.ON_PACE, 101.0, RiskLevel.MEDIUM),
            RiskBoundaryRow("on pace, buffer exactly 0.10 is high", 900.0, PaceStatus.ON_PACE, 100.0, RiskLevel.HIGH),
            RiskBoundaryRow("on pace, buffer 0.201 is low", 799.0, PaceStatus.ON_PACE, 201.0, RiskLevel.LOW),
            RiskBoundaryRow("on pace, buffer exactly 0.20 is medium", 800.0, PaceStatus.ON_PACE, 200.0, RiskLevel.MEDIUM),
            RiskBoundaryRow("on pace, ample buffer is low", 600.0, PaceStatus.ON_PACE, 400.0, RiskLevel.LOW)
        )
        assertEquals("row guard", 10, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
                spendingPace = createPace(paceStatus = row.paceStatus, currentSpent = row.spent)
            )
            // 0.85 - 0.05 (no recurring) = 0.80; the fallback forecast is confidence 0.0 / MEDIUM.
            assertEquals("${row.label}: not the fallback forecast", 0.80, forecast.confidence, 1e-9)
            assertMoneyExact(row.expectedDiscretionary, forecast.components.discretionaryBudget, "${row.label}: discretionaryBudget")
            assertEquals("${row.label}: risk", row.expectedRisk, forecast.components.riskLevel)
        }
    }

    @Test
    fun `stress - risk level at buffer thresholds`() = runTest {
        // Budget 1000, spent 900, obligations 40 + 5 = 45 => discretionary 55 = 5.5% buffer
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 40.0, confidence = 0.95f)),
            plannedExpenses = listOf(createPlannedExpense(amount = 5.0, priority = PlannedExpensePriority.MUST)),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = createPace(
                paceStatus = PaceStatus.OVER_PACE,
                currentSpent = 900.0,
                averageTotal = 1000.0
            )
        )

        assertMoneyEquals(55.00, forecast.components.discretionaryBudget, "discretionaryBudget")
        // Buffer remains above critical threshold, so over-pace maps to HIGH.
        assertEquals(RiskLevel.HIGH, forecast.components.riskLevel)
    }

    @Test
    fun `stress - multiple critical budgets`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 100.0, spent = 150.0, health = BudgetHealthStatus.CRITICAL),
                createBudgetStatus(limit = 100.0, spent = 120.0, health = BudgetHealthStatus.EXCEEDED)
            ),
            spendingPace = createPace()
        )

        // Multiple critical budgets = CRITICAL
        assertEquals(RiskLevel.CRITICAL, forecast.components.riskLevel)
    }

    // ============================================================================
    // SECTION 9: BLOCK PARTY
    // ============================================================================

    @Test
    fun `stress - BlockParty at month start`() = runTest {
        val engine = engineAt(getTimestampForDayOfMonth(2024, 0, 1))

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(daysElapsed = 1, daysInMonth = 31)
        )
        // 0.85 - 0.05 (no recurring) = 0.80; the fallback forecast is confidence 0.0.
        assertEquals("not the fallback forecast", 0.80, forecast.confidence, 1e-9)
        assertTrue("not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        assertEquals(31, blockParty.size)
        // First day should be TODAY
        assertTrue(blockParty.first().isToday)
    }

    @Test
    fun `stress - BlockParty at month end`() = runTest {
        val engine = engineAt(getTimestampForDayOfMonth(2024, 0, 31))

        val forecast = engine.synthesize(
            pastSumDaily = List(31) { (it + 1) * 50.0 },
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(daysElapsed = 31, daysInMonth = 31)
        )
        // 0.85 - 0.05 (no recurring) = 0.80; the fallback forecast is confidence 0.0.
        assertEquals("not the fallback forecast", 0.80, forecast.confidence, 1e-9)

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { (it + 1) * 50.0f },
            budgetLimit = 2000.0
        )

        assertEquals(31, blockParty.size)
        assertEquals(31, blockParty.single { it.isToday }.dayOfMonth)
    }

    /**
     * Replaces: BlockParty with recurring on specific days (previously only `recurringImpact > 0`).
     * April 15 2024 (30 days), budget 1500, rent 500 on the 1st and power 80 on the 15th (MONTHLY):
     * monthly recurring 580 => base rate (1500 - 580) / 30 = 30.67 per day; bill days add their amount.
     */
    @Test
    fun `stress - BlockParty with recurring on specific days`() = runTest {
        val engine = engineAt(getTimestampForDayOfMonth(2024, 3, 15)) // April 15

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                // Rent due on 1st
                createRecurringPattern(
                    merchantName = "LANDLORD",
                    amount = 500.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    date = getTimestampForDayOfMonth(2024, 3, 1)
                ),
                // Electric due on 15th
                createRecurringPattern(
                    merchantName = "POWER",
                    amount = 80.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    date = getTimestampForDayOfMonth(2024, 3, 15)
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1500.0)),
            spendingPace = createPace()
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(30) { 0f },
            budgetLimit = 1500.0
        )

        assertEquals(30, blockParty.size)
        val day1 = blockParty.single { it.dayOfMonth == 1 }
        val day15 = blockParty.single { it.dayOfMonth == 15 }
        val day16 = blockParty.single { it.dayOfMonth == 16 }

        assertEquals("day 1 recurring impact", 500.0, day1.recurringImpact, 0.0)
        assertEquals("day 1 recurring items", listOf("LANDLORD"), day1.recurringItems)
        assertMoneyEquals(30.67, day1.baseTarget, "day 1 base target")
        assertMoneyEquals(530.67, day1.targetBudget, "day 1 target budget")
        assertEquals("day 1 past day within target", BlockPartyStatus.UNDER_BUDGET, day1.status)

        assertEquals("day 15 recurring impact", 80.0, day15.recurringImpact, 0.0)
        assertEquals("day 15 recurring items", listOf("POWER"), day15.recurringItems)
        assertMoneyEquals(110.67, day15.targetBudget, "day 15 target budget")
        assertEquals("day 15 is today", BlockPartyStatus.TODAY, day15.status)
        assertTrue(day15.isToday)

        assertEquals("day 16 has no bill", 0.0, day16.recurringImpact, 0.0)
        assertMoneyEquals(30.67, day16.targetBudget, "day 16 target budget")
        assertEquals("day 16 is a plain future day", BlockPartyStatus.FUTURE, day16.status)

        assertEquals("only the two bill days carry recurring impact", 580.0, blockParty.sumOf { it.recurringImpact }, 0.0)
    }

    @Test
    fun `stress - BlockParty empty daily spending`() = runTest {
        val engine = engineAt(getTimestampForDayOfMonth(2024, 5, 15))

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = createPace()
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = emptyList(), // Empty!
            budgetLimit = 1000.0
        )

        // June 2024: 30 days, today = 15. No recurring patterns => no BILL_DAY.
        assertEquals(30, blockParty.size)
        val today = blockParty.single { it.isToday }
        assertEquals("today", 15, today.dayOfMonth)
        assertEquals("today status", BlockPartyStatus.TODAY, today.status)

        // Future days (16..30) are forecast states, never NO_DATA: exactly 15 FUTURE days.
        val futureDays = blockParty.filter { it.dayOfMonth > 15 }
        assertEquals("future day count", 15, futureDays.size)
        for (day in futureDays) {
            assertEquals("day ${day.dayOfMonth} is FUTURE", BlockPartyStatus.FUTURE, day.status)
        }

        // Past days (1..14) have no expenses and empty daily history => no recorded data.
        val pastDays = blockParty.filter { it.dayOfMonth < 15 }
        assertEquals("past day count", 14, pastDays.size)
        for (day in pastDays) {
            assertEquals("day ${day.dayOfMonth} is NO_DATA", BlockPartyStatus.NO_DATA, day.status)
            assertMoneyExact(0.0, day.actualSpent, "day ${day.dayOfMonth} actualSpent")
        }
    }

    /**
     * Replaces: calculateBlockParty with large dataset (1000 expenses, previously only non-empty).
     * The forecast has a blank display currency, so raw expense items cannot be aggregated (no typed
     * currency basis): the day uses the normalized daily history and reports each excluded raw item in
     * conversionFailureCount; topTransactions still lists the three largest by amount.
     * June 15 2024 (30 days), budget 2000 => base 66.67; history day d = 25 * d.
     */
    @Test
    fun `stress - BlockParty raw expenses without currency fall back to daily history and count exclusions`() = runTest {
        val engine = engineAt(getTimestampForDayOfMonth(2024, 5, 15)) // June 15

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace()
        )
        val june3 = getTimestampForDayOfMonth(2024, 5, 3)
        val expenses = listOf(10.0, 30.0, 20.0).mapIndexed { index, amount ->
            TransactionSummary(
                id = index + 1L,
                amount = amount,
                effectiveAmount = amount,
                merchant = "Merchant$index",
                date = june3,
                categoryId = null
            )
        }

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = expenses,
            dailySpending = List(30) { (it + 1) * 25f },
            budgetLimit = 2000.0
        )

        assertEquals(30, blockParty.size)
        val day1 = blockParty.single { it.dayOfMonth == 1 }
        val day3 = blockParty.single { it.dayOfMonth == 3 }

        assertMoneyEquals(66.67, day3.baseTarget, "day 3 base target")
        assertEquals("day 3 uses normalized history (3 x 25)", 75.0, day3.actualSpent, 0.0)
        assertEquals("day 3 over its target", BlockPartyStatus.OVER_BUDGET, day3.status)
        assertEquals("day 3 excluded raw items", 3, day3.conversionFailureCount)
        assertEquals("day 3 top transactions by amount", listOf(30.0, 20.0, 10.0), day3.topTransactions.map { it.amount })

        assertEquals("day 1 history", 25.0, day1.actualSpent, 0.0)
        assertEquals("day 1 within target", BlockPartyStatus.UNDER_BUDGET, day1.status)
        assertEquals("day 1 no exclusions", 0, day1.conversionFailureCount)
    }

    // ============================================================================
    // SECTION 10: CONFIDENCE AND INSIGHTS
    // ============================================================================

    private data class ConfidenceRow(
        val label: String,
        val hasBudget: Boolean,
        val hasAverage: Boolean,
        val hasRecurring: Boolean,
        val expectedConfidence: Double
    )

    /**
     * Replaces: confidence with no budget no baseline (<0.6), confidence with full data (>=0.80).
     * Base 0.85; -0.15 without a budget limit, -0.10 without an average baseline, -0.05 without
     * recurring patterns.
     */
    @Test
    fun `confidence - each missing input applies its exact penalty`() = runTest {
        val rows = listOf(
            ConfidenceRow("full data", hasBudget = true, hasAverage = true, hasRecurring = true, expectedConfidence = 0.85),
            ConfidenceRow("no budget", hasBudget = false, hasAverage = true, hasRecurring = true, expectedConfidence = 0.70),
            ConfidenceRow("no average baseline", hasBudget = true, hasAverage = false, hasRecurring = true, expectedConfidence = 0.75),
            ConfidenceRow("no recurring patterns", hasBudget = true, hasAverage = true, hasRecurring = false, expectedConfidence = 0.80),
            ConfidenceRow("no budget, no baseline, no recurring", hasBudget = false, hasAverage = false, hasRecurring = false, expectedConfidence = 0.55)
        )
        assertEquals("row guard", 5, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = List(30) { it * 10.0 },
                recurringPatterns = if (row.hasRecurring) listOf(createRecurringPattern(amount = 100.0)) else emptyList(),
                plannedExpenses = listOf(createPlannedExpense(amount = 50.0)),
                savingsGoals = listOf(createSavingsGoal(target = 1000.0, current = 100.0)),
                budgetStatuses = if (row.hasBudget) listOf(createBudgetStatus(limit = 2000.0)) else emptyList(),
                spendingPace = createPace(
                    averageTotal = if (row.hasAverage) 1500.0 else null,
                    previousTotal = 1400.0,
                    currentSpent = 800.0
                )
            )
            assertEquals("${row.label}: confidence", row.expectedConfidence, forecast.confidence, 1e-9)
        }
    }

    @Test
    fun `insights - exceeded budget over pace strict goal and must planned produce four ordered insights`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = listOf(
                createPlannedExpense(amount = 100.0, priority = PlannedExpensePriority.MUST),
                createPlannedExpense(amount = 100.0, priority = PlannedExpensePriority.MUST)
            ),
            savingsGoals = listOf(
                createSavingsGoal(target = 1000.0, current = 0.0, protection = GoalProtectionLevel.STRICT)
            ),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 100.0, spent = 150.0, health = BudgetHealthStatus.EXCEEDED)
            ),
            spendingPace = createPace(paceStatus = PaceStatus.OVER_PACE)
        )

        assertEquals(
            listOf(
                UiText.fromKey(DomainTextKeys.SYNTHESIS_SPENDING_PACE_HIGHER),
                UiText.fromKey(DomainTextKeys.SYNTHESIS_BUDGETS_EXCEEDED_FORMAT, 1),
                UiText.fromKey(DomainTextKeys.SYNTHESIS_STRICT_SAVINGS_GOALS_ACTIVE_FORMAT, 1),
                UiText.fromKey(DomainTextKeys.SYNTHESIS_MUST_PAY_PLANNED_EXPENSES_FORMAT, 2)
            ),
            forecast.actionableInsights
        )
    }

    @Test
    fun `insights - on-track budget under pace without goals or must planned produce none`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 1000.0, spent = 200.0, health = BudgetHealthStatus.ON_TRACK)
            ),
            spendingPace = createPace(paceStatus = PaceStatus.UNDER_PACE)
        )

        // The fallback forecast also has no insights. Exact non-fallback confidence:
        // 0.85 - 0.05 (no recurring patterns); budget and average baseline present.
        assertEquals("not the fallback forecast", 0.80, forecast.confidence, 1e-9)
        assertEquals(emptyList<UiText>(), forecast.actionableInsights)
    }

    // ============================================================================
    // SECTION 11: RECURRING CONFIDENCE TIERS
    // ============================================================================

    private data class ConfidenceTierRow(
        val label: String,
        val confidence: Float,
        val expectedCommitted: Double,
        val expectedLikely: Double
    )

    /**
     * Replaces: recurring pattern confidence boundaries. Committed when confidence >= 0.90,
     * likely when 0.70 <= confidence < 0.90, ignored below 0.70. Amount 100, dated today.
     */
    @Test
    fun `recurring confidence tiers - committed at 0-90 likely from 0-70 ignored below`() = runTest {
        val rows = listOf(
            ConfidenceTierRow("0.95 committed", 0.95f, 100.0, 0.0),
            ConfidenceTierRow("0.90 committed boundary", 0.90f, 100.0, 0.0),
            ConfidenceTierRow("0.89 likely just below committed", 0.89f, 0.0, 100.0),
            ConfidenceTierRow("0.70 likely lower boundary", 0.70f, 0.0, 100.0),
            ConfidenceTierRow("0.69 ignored just below likely", 0.69f, 0.0, 0.0)
        )
        assertEquals("row guard", 5, rows.size)

        for (row in rows) {
            val forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = row.confidence)),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )
            assertTrue("${row.label}: not fallback", forecast.components.projectedSpendingPoints.isNotEmpty())
            assertEquals("${row.label}: totalCommitted", row.expectedCommitted, forecast.components.totalCommitted, 0.0)
            assertEquals("${row.label}: totalLikely", row.expectedLikely, forecast.components.totalLikely, 0.0)
        }
    }

    @Test
    fun `regression - confidence interval gap 0-89 to 0-90`() = runTest {
        // Previously there was a gap between 0.89 and 0.90
        // 0.70 to < 0.90 should be LIKELY
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.80f)),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Should be in likely, not committed
        assertEquals(0.0, forecast.components.totalCommitted, 0.0)
        assertEquals(100.0, forecast.components.totalLikely, 0.0)
    }

    // ============================================================================
    // SECTION 12: DETERMINISTIC HOSTILE MIX
    // ============================================================================

    /**
     * Fixed (deterministic) replacement for the deleted random chaos test. Default fixture time:
     * Feb 15 2024 (29 days, today = 15, 14 days remaining), display currency blank, no conversion.
     *
     * Inputs and hand-derived expectations:
     * - Patterns (all MONTHLY, dated today): 100 @0.95 (committed), 30 @0.80 (likely), 70 @0.60 (ignored,
     *   below the 0.70 floor). Monthly recurring total (>=0.70 only) = 130.
     * - Planned: MUST 50 PLANNED (committed), LIKELY 100 PLANNED (x0.7 = 70 likely), OPTIONAL 60 PLANNED
     *   (weight 0), MUST 400 "CONVERTED" (non-PLANNED, dropped by the in-engine filter, not listed).
     * - committed = 100 + 50 = 150; likely = 30 + 70 = 100.
     * - Goals: WARNING and TRACKING (unfinished, but only STRICT reserves), STRICT funded above target
     *   (1200 / 1000, reserves nothing), STRICT 500 / 1000 due in 30 days => 500 / (30 / 29) = 483.33.
     * - Budgets: three overall rows (null category: 1000, 5000, 250) plus a 700 category row; the first
     *   overall row wins (find), so the limit is 1000. All ON_TRACK, so no critical-budget escalation.
     * - Pace: spent 200, average 1000 (previous 1400), UNDER_PACE.
     * - discretionary = 1000 - (200 + (100 + 50 + 30 + 70) + 483.33) = 66.67; buffer 0.0667 <= 0.10 and
     *   not over pace => HIGH.
     * - predicted = (1000 - 130) / 29 * 14 = 420.00; projected points day 15..29 = 15,
     *   first = 100 + 50 + 70 = 220, last = 220 + 420.00 = 640.00.
     * - Confidence: budget present, average present, patterns present => 0.85 (fallback would be 0.0).
     * - Insights: no pace/exceeded insight; strict goals = 1 (funded 1200/1000 excluded); MUST planned (PLANNED only) = 1.
     */
    @Test
    fun `hostile mix - warning tracking funded low-confidence multiple overall budgets under pace and non-planned status`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(amount = 100.0, confidence = 0.95f, merchantName = "Committed"),
                createRecurringPattern(amount = 30.0, confidence = 0.80f, merchantName = "Likely"),
                createRecurringPattern(amount = 70.0, confidence = 0.60f, merchantName = "TooWeak")
            ),
            plannedExpenses = listOf(
                createPlannedExpense(amount = 50.0, priority = PlannedExpensePriority.MUST),
                createPlannedExpense(amount = 100.0, priority = PlannedExpensePriority.LIKELY),
                createPlannedExpense(amount = 60.0, priority = PlannedExpensePriority.OPTIONAL),
                createPlannedExpense(amount = 400.0, priority = PlannedExpensePriority.MUST, status = "CONVERTED")
            ),
            savingsGoals = listOf(
                createSavingsGoal(target = 1000.0, current = 0.0, protection = GoalProtectionLevel.WARNING),
                createSavingsGoal(target = 1000.0, current = 0.0, protection = GoalProtectionLevel.TRACKING),
                createSavingsGoal(target = 1000.0, current = 1200.0, protection = GoalProtectionLevel.STRICT),
                createSavingsGoal(target = 1000.0, current = 500.0, protection = GoalProtectionLevel.STRICT)
            ),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 1000.0),
                createBudgetStatus(limit = 5000.0),
                createBudgetStatus(limit = 250.0),
                createBudgetStatus(limit = 700.0, categoryId = 1)
            ),
            spendingPace = createPace(
                currentSpent = 200.0,
                paceStatus = PaceStatus.UNDER_PACE,
                averageTotal = 1000.0,
                previousTotal = 1400.0
            )
        )
        val c = forecast.components

        assertEquals("exact non-fallback confidence", 0.85, forecast.confidence, 1e-9)
        assertEquals("risk", RiskLevel.HIGH, c.riskLevel)
        assertMoneyExact(150.0, c.totalCommitted, "totalCommitted")
        assertMoneyEquals(100.00, c.totalLikely, "totalLikely")
        assertMoneyEquals(483.33, c.goalReserves, "goalReserves")
        assertMoneyEquals(66.67, c.discretionaryBudget, "discretionaryBudget")
        // FORE-02 / FORE-06: low-confidence (0.60) pattern is no longer subtracted: (1000-130)/29*14
        assertMoneyEquals(420.00, c.predictedDiscretionary, "predictedDiscretionary")
        assertEquals("goal reserve exclusions", 0, c.goalReserveExclusions)
        assertEquals("recurring listed (unfiltered)", 3, c.recurringExpenses.size)
        assertEquals("planned listed (non-PLANNED dropped)", 3, c.plannedExpenses.size)
        assertEquals("projected point count (day 15..29)", 15, c.projectedSpendingPoints.size)
        assertMoneyEquals(220.00, c.projectedSpendingPoints.first(), "first projected point")
        // FORE-02 / FORE-06: last point = 220 + 420.00 (predictedDiscretionary no longer reduced by low-confidence pattern)
        assertMoneyEquals(640.00, c.projectedSpendingPoints.last(), "last projected point")
        assertEquals("excluded count", 0, forecast.excludedCount)
        assertFalse("not partial", forecast.isPartial)
        assertEquals(
            listOf(
                // FORE-02 / FORE-06: funded STRICT goal (1200/1000) is no longer counted as active
                UiText.fromKey(DomainTextKeys.SYNTHESIS_STRICT_SAVINGS_GOALS_ACTIVE_FORMAT, 1),
                UiText.fromKey(DomainTextKeys.SYNTHESIS_MUST_PAY_PLANNED_EXPENSES_FORMAT, 1)
            ),
            forecast.actionableInsights
        )
    }
}
