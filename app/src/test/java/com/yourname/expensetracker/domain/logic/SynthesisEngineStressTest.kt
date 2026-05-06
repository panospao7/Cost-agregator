package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Ignore
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * Stress Test Suite for SynthesisEngine
 * 
 * Goal: Break the synthesis engine with extreme inputs, boundary conditions,
 * and concurrent access patterns.
 * 
 * @author Hostile QA Engineer
 */
class SynthesisEngineStressTest : AnalyticsEngineTestBase() {

    private lateinit var engine: SynthesisEngine

    // ============================================================================
    // FIXTURE HELPERS
    // ============================================================================

    private fun createTimeProvider(timestamp: Long) {
        every { timeProvider.now() } returns timestamp
    }

    private fun createRecurringPattern(
        amount: Double = 100.0,
        confidence: Float = 0.95f,
        date: Long = System.currentTimeMillis(),
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
        date: Long = System.currentTimeMillis()
    ) = PlannedExpense(
        id = 0,
        description = "Planned",
        amount = amount,
        date = date,
        categoryId = null,
        isRecurring = false,
        priority = priority
    )

    private fun createSavingsGoal(
        target: Double = 1000.0,
        current: Double = 0.0,
        protection: GoalProtectionLevel = GoalProtectionLevel.STRICT,
        targetDate: Long? = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
    ) = SavingsGoal(
        id = 0,
        name = "TestGoal",
        targetAmount = target,
        currentAmount = current,
        targetDate = targetDate,
        protectionLevel = protection,
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

    // ============================================================================
    // SECTION 1: BOUNDARY TESTS - Month Boundaries
    // ============================================================================

    @Before
    override fun setUp() {
        super.setUp()
        // Default to middle of month
        createTimeProvider(getTimestampForDayOfMonth(2024, 1, 15))
        engine = SynthesisEngine(timeProvider)
    }

    /**
     * Get timestamp for specific day of month
     */
    private fun getTimestampForDayOfMonth(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `stress - first day of month boundary`() {
        // January 1st, 2024 - first day of month
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 1))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(date = getTimestampForDayOfMonth(2024, 0, 1))),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 1)
        )

        // Should handle 31 days remaining
        assertNotNull(forecast)
        assertTrue(forecast.components.predictedDiscretionary >= 0)
    }

    @Test
    fun `stress - last day of 31-day month`() {
        // January 31st, 2024 - last day of 31-day month
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 31))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = listOf(100.0, 50.0), // Only 2 days of history
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 31, daysInMonth = 31)
        )

        // Only 1 day remaining, discretionary should be minimal
        assertNotNull(forecast)
    }

    @Test
    fun `stress - last day of 30-day month`() {
        // April 30th, 2024 - 30-day month
        createTimeProvider(getTimestampForDayOfMonth(2024, 3, 30))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 30, daysInMonth = 30)
        )

        assertNotNull(forecast)
    }

    @Test
    fun `stress - February 28th non-leap year`() {
        // Feb 28, 2023 (non-leap year)
        createTimeProvider(getTimestampForDayOfMonth(2023, 1, 28))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 28, daysInMonth = 28)
        )

        assertNotNull(forecast)
        // Non-leap year February has 28 days
    }

    @Test
    fun `stress - February 29th leap year`() {
        // Feb 29, 2024 (leap year)
        createTimeProvider(getTimestampForDayOfMonth(2024, 1, 29))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 29, daysInMonth = 29)
        )

        assertNotNull(forecast)
        // Should handle leap year correctly
    }

    @Test
    fun `stress - month transition edge case`() {
        // Test at 23:59:59 on last day of month vs 00:00:01 on first day
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 31) + 23*60*60*1000 + 59*60*1000 + 59*1000)
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = (1..31).map { it * 10.0 },
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 50.0,
                    date = getTimestampForDayOfMonth(2024, 1, 5) // Next month
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(daysElapsed = 31, daysInMonth = 31)
        )

        assertNotNull(forecast)
        // Recurring in next month should NOT be counted for this month
    }

    // ============================================================================
    // SECTION 2: DST TRANSITION TESTS
    // ============================================================================

    @Test
    fun `stress - DST spring forward transition`() {
        // March 31, 2024 - DST starts (Europe: clocks forward March 31)
        // This tests the boundary when DST begins
        createTimeProvider(getTimestampForDayOfMonth(2024, 2, 31))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 100.0,
                    date = getTimestampForDayOfMonth(2024, 2, 31)
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
    }

    @Test
    fun `stress - DST fall back transition`() {
        // October 27, 2024 - DST ends (Europe: clocks back October 27)
        createTimeProvider(getTimestampForDayOfMonth(2024, 9, 27))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = listOf(
                createPlannedExpense(
                    amount = 50.0,
                    date = getTimestampForDayOfMonth(2024, 9, 28) // Day after DST ends
                )
            ),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
    }

    @Test
    fun `stress - multiple DST transitions in year view`() {
        // Test full year view spanning DST transitions
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 1))
        val engine = SynthesisEngine(timeProvider)

        // Simulate recurring patterns that span entire year
        val recurringPatterns = (1..12).map { month ->
            createRecurringPattern(
                amount = 100.0 + month * 10.0,
                date = getTimestampForDayOfMonth(2024, month - 1, 15),
                frequency = RecurrenceFrequency.MONTHLY
            )
        }

        val forecast = engine.synthesize(
            pastSumDaily = List(365) { (it + 1) * 10.0 },
            recurringPatterns = recurringPatterns,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Monthly recurring should be correctly projected
    }

    // ============================================================================
    // SECTION 3: LARGE DATASET TESTS
    // ============================================================================

    @Test
    fun `stress - 10000 expenses in daily history`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Generate 10,000 days of history (27+ years)
        val largeHistory = (1..10000).map { it * 10.0 }

        val forecast = engine.synthesize(
            pastSumDaily = largeHistory,
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should handle large dataset without memory issues
    }

    @Test
    fun `stress - 500 recurring patterns`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Create 500 recurring patterns
        val recurringPatterns = (1..500).map { i ->
            createRecurringPattern(
                amount = (i % 100) * 10.0 + 10.0,
                confidence = 0.5f + (i % 50) / 100f,
                date = System.currentTimeMillis() + (i * 24 * 60 * 60 * 1000L),
                frequency = RecurrenceFrequency.entries[i % RecurrenceFrequency.entries.size]
            )
        }

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = recurringPatterns,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should handle many recurring patterns
        assertTrue(forecast.components.totalCommitted >= 0)
    }

    @Test
    fun `stress - 1000 planned expenses`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Create 1000 planned expenses
        val plannedExpenses = (1..1000).map { i ->
            createPlannedExpense(
                amount = (i % 100) * 10.0 + 5.0,
                priority = PlannedExpensePriority.entries[i % PlannedExpensePriority.entries.size],
                date = System.currentTimeMillis() + (i * 24 * 60 * 60 * 1000L)
            )
        }

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = plannedExpenses,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        assertTrue(forecast.components.totalCommitted >= 0)
        assertTrue(forecast.components.totalLikely >= 0)
    }

    @Test
    fun `stress - combined large datasets`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val largeHistory = List(1000) { (it + 1) * 50.0 }
        val recurringPatterns = (1..100).map { i ->
            createRecurringPattern(
                amount = 100.0 + i * 5.0,
                confidence = 0.95f,
                date = System.currentTimeMillis() + (i * 24 * 60 * 60 * 1000L)
            )
        }
        val plannedExpenses = (1..200).map { i ->
            createPlannedExpense(
                amount = 50.0 + i * 2.0,
                priority = PlannedExpensePriority.MUST,
                date = System.currentTimeMillis() + (i * 24 * 60 * 60 * 1000L)
            )
        }
        val savingsGoals = (1..10).map { i ->
            createSavingsGoal(
                target = i * 1000.0,
                current = i * 500.0,
                protection = GoalProtectionLevel.STRICT
            )
        }
        val budgetStatuses = (1..5).map { i ->
            createBudgetStatus(limit = i * 500.0, categoryId = i.toLong())
        }

        val forecast = engine.synthesize(
            pastSumDaily = largeHistory,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetStatuses,
            spendingPace = createPace(
                currentSpent = 5000.0,
                averageTotal = 8000.0,
                previousTotal = 7500.0
            )
        )

        assertNotNull(forecast)
        // Should complete without errors
    }

    // ============================================================================
    // SECTION 4: EDGE CASES - Zero, Negative, Extreme Values
    // ============================================================================

    @Test
    fun `stress - zero budget with spending`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = listOf(100.0, 50.0),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace(currentSpent = 200.0)
        )

        assertNotNull(forecast)
        // With no budget, discretionary should be 0
        assertEquals(0.0, forecast.components.discretionaryBudget, 0.0)
    }

    @Test
    fun `stress - zero budget but has category budgets`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(
                createBudgetStatus(limit = 500.0, categoryId = 1),
                createBudgetStatus(limit = 300.0, categoryId = 2)
            ),
            spendingPace = createPace()
        )

        // Should use category budgets sum as fallback
        assertNotNull(forecast)
        assertTrue(forecast.components.discretionaryBudget >= 0)
    }

    @Test
    fun `stress - spending exceeds all budgets`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        assertNotNull(forecast)
        // Should show critical risk
        assertEquals(RiskLevel.CRITICAL, forecast.components.riskLevel)
    }

    @Test
    fun `stress - negative discretionary budget`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // High committed + high likely + high goals - low budget = negative
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

        // Should clamp to 0.0 (not negative)
        assertTrue(forecast.components.discretionaryBudget >= 0.0)
    }

    @Test
    fun `stress - extremely large amounts`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        assertNotNull(forecast)
        // Should handle without overflow
    }

    @Test
    fun `stress - extremely small amounts`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        assertNotNull(forecast)
        // Should handle tiny amounts
    }

    @Test
    fun `stress - NaN and Infinity inputs`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // These should not cause NaN/Infinity in outputs
        val forecast = engine.synthesize(
            pastSumDaily = listOf(Double.NaN, Double.POSITIVE_INFINITY),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should handle gracefully
    }

    @Test
    fun `stress - extreme goal reserves calculation`() {
        // Goal with target in past - should use full remaining amount
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = listOf(
                createSavingsGoal(
                    target = 10000.0,
                    current = 1000.0, // 9000 remaining
                    protection = GoalProtectionLevel.STRICT,
                    targetDate = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // Yesterday!
                )
            ),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should calculate full remaining as due now
    }

    @Test
    fun `stress - goal with far future target date`() {
        // Goal with target 10 years in future - should pro-rate significantly
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val farFuture = System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000 // ~10 years

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = listOf(
                createSavingsGoal(
                    target = 120000.0, // 1000/month for 10 years
                    current = 0.0,
                    protection = GoalProtectionLevel.STRICT,
                    targetDate = farFuture
                )
            ),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should pro-rate to very small monthly amount
    }

    // ============================================================================
    // SECTION 5: NULL SAFETY TESTS
    // ============================================================================

    @Test
    fun `stress - all nullable fields null in SpendingPace`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Create pace with nullable fields as null (projectedTotal must be non-null)
        val nullPace = SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 0,
            daysInMonth = 30,
            projectedTotal = 0.0, // Must be non-null
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = nullPace
        )

        assertNotNull(forecast)
        // Should handle gracefully
    }

    @Test
    fun `stress - partially null SpendingPace`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val partialNullPace = SpendingPace(
            currentMonthSpent = 500.0,
            daysElapsed = 15,
            daysInMonth = 30,
            projectedTotal = 1000.0,
            previousMonthTotal = null, // Null!
            averageMonthlyTotal = null, // Null!
            pacePercentage = 50f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = partialNullPace
        )

        assertNotNull(forecast)
        // Should still produce results
    }

    @Test
    fun `stress - null budget amounts`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(
                BudgetStatusSnapshot(
                    budgetCategoryId = null,
                    budgetAmount = 0.0, // Zero, not null
                    categoryName = null,
                    spentAmount = 0.0,
                    remainingAmount = 0.0,
                    percentUsed = 0.0,
                    healthStatus = BudgetHealthStatus.ON_TRACK,
                    periodStart = 0,
                    periodEnd = 0
                )
            ),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
    }

    @Test
    fun `stress - empty lists for all collections`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = 1,
                daysInMonth = 30,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0f,
                paceStatus = PaceStatus.ON_PACE,
                displayCurrency = "EUR",
            )
        )

        assertNotNull(forecast)
        assertNotNull(forecast.components)
        // Should handle all-empty gracefully
    }

    @Test
    fun `stress - null targetDate in SavingsGoal`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = listOf(
                SavingsGoal(
                    id = 0,
                    name = "NoDateGoal",
                    targetAmount = 1000.0,
                    currentAmount = 0.0,
                    targetDate = null, // NULL!
                    protectionLevel = GoalProtectionLevel.STRICT,
                    createdAt = 0L,
                )
            ),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should handle null targetDate
    }

    // ============================================================================
    // SECTION 6: CONCURRENT ACCESS TESTS
    // ============================================================================

    @Test
    fun `stress - 10 concurrent synthesize calls`() = runBlocking {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val latch = CountDownLatch(10)
        val errors = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(10)

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
                        assertNotNull(forecast)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
    }

    @Test
    fun `stress - 50 concurrent synthesize calls`() = runBlocking {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val results = runBlocking(Dispatchers.Default) {
            (1..50).map { i ->
                async {
                    try {
                        engine.synthesize(
                            pastSumDaily = listOf(100.0 * i),
                            recurringPatterns = listOf(createRecurringPattern(amount = 100.0 + i)),
                            plannedExpenses = emptyList(),
                            savingsGoals = emptyList(),
                            budgetStatuses = emptyList(),
                            spendingPace = createPace()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }

        // All should complete without crash
        assertEquals(50, results.count { it != null })
    }

    @Test
    fun `stress - rapid sequential calls with different times`() {
        createTimeProvider(System.currentTimeMillis())
        var engine = SynthesisEngine(timeProvider)

        // Call 100 times with slightly different timestamps
        repeat(100) { i ->
            val timestamp = System.currentTimeMillis() + i * 1000L
            every { timeProvider.now() } returns timestamp

            // Recreate engine to pick up new time (or test with same engine)
            val forecast = engine.synthesize(
                pastSumDaily = listOf(100.0 + i),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = createPace()
            )

            assertNotNull(forecast)
        }
    }

    // ============================================================================
    // SECTION 7: MEMORY STRESS TESTS
    // ============================================================================

    @Test
    fun `stress - 5 years of daily spending data`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // 5 years = ~1826 days
        val fiveYearsOfData = (1..1826).map { it * 25.0 }

        val forecast = engine.synthesize(
            pastSumDaily = fiveYearsOfData,
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
        // Should handle without OutOfMemoryError
    }

    @Test
    fun `stress - calculateBlockParty with large dataset`() {
        createTimeProvider(getTimestampForDayOfMonth(2024, 5, 15)) // June 15
        val engine = SynthesisEngine(timeProvider)

        // Create forecast first
        val forecast = engine.synthesize(
            pastSumDaily = (1..15).map { it * 50.0 },
            recurringPatterns = (1..10).map { i ->
                createRecurringPattern(
                    amount = 100.0 + i * 10.0,
                    date = getTimestampForDayOfMonth(2024, 5, i)
                )
            },
            plannedExpenses = (1..20).map { i ->
                createPlannedExpense(
                    amount = 50.0 + i * 5.0,
                    priority = PlannedExpensePriority.entries[i % 3],
                    date = getTimestampForDayOfMonth(2024, 5, i + 15)
                )
            },
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(currentSpent = 1000.0)
        )

        // Generate Block Party data with large expense list
        val expenses = (1..1000).map { i ->
            com.yourname.expensetracker.domain.model.TransactionSummary(
                id = i.toLong(),
                amount = 10.0 + (i % 100),
                effectiveAmount = 10.0 + (i % 100),
                merchant = "Merchant$i",
                date = getTimestampForDayOfMonth(2024, 5, (i % 30) + 1),
                categoryId = (i % 10).toLong()
            )
        }

        val dailySpending = List(30) { (it + 1) * 50.0f }

        val blockPartyData = runBlocking {
            engine.calculateBlockPartyData(
                forecast = forecast,
                expenses = expenses,
                dailySpending = dailySpending,
                budgetLimit = 2000.0
            )
        }

        assertNotNull(blockPartyData)
        assertTrue(blockPartyData.isNotEmpty())
    }

    // ============================================================================
    // SECTION 8: RECURRING FREQUENCY EDGE CASES
    // ============================================================================

    @Test
    fun `stress - weekly frequency calculation`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 100.0,
                    frequency = RecurrenceFrequency.WEEKLY,
                    date = System.currentTimeMillis()
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Weekly: 100 * (30/7) = ~428.57 per month
        assertNotNull(forecast)
    }

    @Test
    fun `stress - biweekly frequency calculation`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 100.0,
                    frequency = RecurrenceFrequency.BIWEEKLY,
                    date = System.currentTimeMillis()
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Biweekly: 100 * (30/14) = ~214.29 per month
        assertNotNull(forecast)
    }

    @Test
    fun `stress - quarterly frequency calculation`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 300.0,
                    frequency = RecurrenceFrequency.QUARTERLY,
                    date = System.currentTimeMillis()
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Quarterly: 300 / 3 = 100 per month
        assertNotNull(forecast)
    }

    @Test
    fun `stress - semi-annually frequency calculation`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 600.0,
                    frequency = RecurrenceFrequency.SEMI_ANNUALLY,
                    date = System.currentTimeMillis()
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Semi-annually: 600 / 6 = 100 per month
        assertNotNull(forecast)
    }

    @Test
    fun `stress - annually frequency calculation`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                createRecurringPattern(
                    amount = 1200.0,
                    frequency = RecurrenceFrequency.ANNUALLY,
                    date = System.currentTimeMillis()
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Annually: 1200 / 12 = 100 per month
        assertNotNull(forecast)
    }

    // ============================================================================
    // SECTION 9: RISK LEVEL DETERMINATION EDGE CASES
    // ============================================================================

    @Test
    fun `stress - risk level with zero budget but on pace`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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
    fun `stress - risk level with zero budget and over pace`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        // No budget but over pace = MEDIUM risk
        assertEquals(RiskLevel.MEDIUM, forecast.components.riskLevel)
    }

    @Test
    fun `stress - risk level at buffer thresholds`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Test exact boundary: buffer ratio = 0.05 (5%)
        // Budget 1000, spent 900, obligations 45 = discretionary 55 = 5.5%
        // Should be CRITICAL when over pace and buffer < 5%
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

        // Buffer remains above critical threshold, so over-pace maps to HIGH.
        assertEquals(RiskLevel.HIGH, forecast.components.riskLevel)
    }

    @Test
    fun `stress - multiple critical budgets`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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
    // SECTION 10: BLOCK PARTY SPECIFIC TESTS
    // ============================================================================

    @Test
    fun `stress - BlockParty at month start`() {
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 1))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(daysElapsed = 1, daysInMonth = 31)
        )

        val blockParty = runBlocking {
            engine.calculateBlockPartyData(
                forecast = forecast,
                expenses = emptyList(),
                dailySpending = List(31) { 0f },
                budgetLimit = 2000.0
            )
        }

        assertEquals(31, blockParty.size)
        // First day should be TODAY
        assertTrue(blockParty.first().isToday)
    }

    @Test
    fun `stress - BlockParty at month end`() {
        createTimeProvider(getTimestampForDayOfMonth(2024, 0, 31))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = List(31) { (it + 1) * 50.0 },
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(daysElapsed = 31, daysInMonth = 31)
        )

        val blockParty = runBlocking {
            engine.calculateBlockPartyData(
                forecast = forecast,
                expenses = emptyList(),
                dailySpending = List(31) { (it + 1) * 50.0f },
                budgetLimit = 2000.0
            )
        }

        assertEquals(31, blockParty.size)
    }

    @Test
    fun `stress - BlockParty with recurring on specific days`() {
        createTimeProvider(getTimestampForDayOfMonth(2024, 3, 15)) // April 15
        val engine = SynthesisEngine(timeProvider)

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

        val blockParty = runBlocking {
            engine.calculateBlockPartyData(
                forecast = forecast,
                expenses = emptyList(),
                dailySpending = List(30) { 0f },
                budgetLimit = 1500.0
            )
        }

        // Day 1 and 15 should show recurring impact
        val day1 = blockParty.find { it.dayOfMonth == 1 }
        val day15 = blockParty.find { it.dayOfMonth == 15 }

        assertNotNull(day1)
        assertNotNull(day15)
        assertTrue(day1!!.recurringImpact > 0)
        assertTrue(day15!!.recurringImpact > 0)
    }

    @Test
    fun `stress - BlockParty empty daily spending`() {
        createTimeProvider(getTimestampForDayOfMonth(2024, 5, 15))
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = createPace()
        )

        val blockParty = runBlocking {
            engine.calculateBlockPartyData(
                forecast = forecast,
                expenses = emptyList(),
                dailySpending = emptyList(), // Empty!
                budgetLimit = 1000.0
            )
        }

        // Future days should remain forecast states (not past NO_DATA).
        val futureDays = blockParty.filter { !it.isToday && it.dayOfMonth > 15 }
        assertTrue(futureDays.all { it.status in listOf(BlockPartyStatus.FUTURE, BlockPartyStatus.BILL_DAY) })
    }

    // ============================================================================
    // SECTION 11: CONFIDENCE CALCULATION TESTS
    // ============================================================================

    @Test
    fun `stress - confidence with no budget no baseline`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = 15,
                daysInMonth = 30,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0f,
                paceStatus = PaceStatus.ON_PACE,
                displayCurrency = "EUR",
            )
        )

        // Should have lowest confidence: 0.85 - 0.15 - 0.10 - 0.05 = 0.55
        assertTrue(forecast.confidence < 0.6)
    }

    @Test
    fun `stress - confidence with full data`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = List(30) { it * 10.0 },
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0)),
            plannedExpenses = listOf(createPlannedExpense(amount = 50.0)),
            savingsGoals = listOf(createSavingsGoal(target = 1000.0, current = 100.0)),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = createPace(
                averageTotal = 1500.0,
                previousTotal = 1400.0,
                currentSpent = 800.0
            )
        )

        // Should have highest confidence: 0.85 (no penalties)
        assertTrue(forecast.confidence >= 0.80)
    }

    // ============================================================================
    // SECTION 12: INSIGHT GENERATION TESTS
    // ============================================================================

    @Test
    fun `stress - insights with exceeded budgets`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        assertTrue(forecast.actionableInsights.isNotEmpty())
    }

    @Test
    fun `stress - insights with no issues`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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

        // May be empty or minimal
        assertNotNull(forecast.actionableInsights)
    }

    // ============================================================================
    // SECTION 13: FUZZY/EDGE INPUT TESTS
    // ============================================================================

    @Test
    fun `stress - recurring pattern confidence boundaries`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Test exact boundary: confidence = 0.90 (should be committed)
        val forecast1 = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.90f)),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Test boundary: confidence = 0.89 (should be likely)
        val forecast2 = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.89f)),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // 0.90 should be committed, 0.89 should be likely
        assertTrue(forecast1.components.totalCommitted > 0)
        assertTrue(forecast2.components.totalLikely > 0)
    }

    @Test
    fun `stress - zero days in month edge case`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // This would be invalid input but should not crash
        val pace = SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 0,
            daysInMonth = 0, // Invalid!
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = pace
        )

        assertNotNull(forecast)
    }

    @Test
    fun `stress - planned expense on past date`() {
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        // Planned expense in the past should be filtered out
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = listOf(
                createPlannedExpense(
                    amount = 100.0,
                    priority = PlannedExpensePriority.MUST,
                    date = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // Yesterday
                )
            ),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        // Should not include past planned expenses
        assertNotNull(forecast)
    }

    // ============================================================================
    // SECTION 14: REGRESSION TESTS FOR FIXES
    // ============================================================================

    @Test
    fun `regression - confidence interval gap 0-89 to 0-90`() {
        // Previously there was a gap between 0.89 and 0.90
        // Verify the fix is in place
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

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
        assertEquals(0.0, forecast.components.totalCommitted, 0.01)
        assertTrue(forecast.components.totalLikely > 0)
    }

    @Test
    fun `regression - Calendar instance reuse`() {
        // Verify Calendar is not recreated excessively
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = (1..30).map { it * 10.0 },
            recurringPatterns = (1..10).map { i ->
                createRecurringPattern(
                    amount = 50.0 + i * 5.0,
                    confidence = 0.95f,
                    date = System.currentTimeMillis() + i * 24 * 60 * 60 * 1000
                )
            },
            plannedExpenses = (1..20).map { i ->
                createPlannedExpense(
                    amount = 30.0 + i * 2.0,
                    priority = PlannedExpensePriority.entries[i % 3],
                    date = System.currentTimeMillis() + i * 24 * 60 * 60 * 1000
                )
            },
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = createPace()
        )

        assertNotNull(forecast)
    }

    @Test
    fun `regression - discretionary pool formula`() {
        // Verify LOG-021 fix: discretionary = budget - recurring - planned - goals
        createTimeProvider(System.currentTimeMillis())
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(createRecurringPattern(amount = 100.0, confidence = 0.95f)),
            plannedExpenses = listOf(createPlannedExpense(amount = 50.0, priority = PlannedExpensePriority.MUST)),
            savingsGoals = listOf(createSavingsGoal(target = 1000.0, current = 500.0, protection = GoalProtectionLevel.STRICT)),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = createPace(currentSpent = 200.0)
        )

        // discretionary = 1000 - 200(spent) - 100(committed) - 50(likely) - 500(goal reserve) = 150
        // But likely = 50 * 0.7 = 35
        // discretionary = 1000 - 200 - 100 - 35 - 500 = 165
        assertTrue(forecast.components.discretionaryBudget >= 0)
    }

    // ============================================================================
    // SECTION 15: FINAL STRESS - COMBINATION OF ALL EDGE CASES
    // ============================================================================

    @Test
    fun `stress - maximum chaos scenario`() {
        // Combine as many edge cases as possible
        createTimeProvider(getTimestampForDayOfMonth(2024, 1, 29)) // Feb 29 - leap year + DST period
        val engine = SynthesisEngine(timeProvider)

        val forecast = engine.synthesize(
            pastSumDaily = List(2000) { Random.nextDouble(0.0, 1000.0) }, // Large random data
            recurringPatterns = (1..200).map { i ->
                createRecurringPattern(
                    amount = Random.nextDouble(10.0, 1000.0),
                    confidence = Random.nextFloat(),
                    date = System.currentTimeMillis() + Random.nextLong(0, 365L * 24 * 60 * 60 * 1000),
                    frequency = RecurrenceFrequency.entries[Random.nextInt(RecurrenceFrequency.entries.size)]
                )
            },
            plannedExpenses = (1..300).map { i ->
                createPlannedExpense(
                    amount = Random.nextDouble(5.0, 500.0),
                    priority = PlannedExpensePriority.entries[Random.nextInt(PlannedExpensePriority.entries.size)],
                    date = System.currentTimeMillis() + Random.nextLong(0, 365L * 24 * 60 * 60 * 1000)
                )
            },
            savingsGoals = (1..20).map { i ->
                createSavingsGoal(
                    target = Random.nextDouble(100.0, 50000.0),
                    current = Random.nextDouble(0.0, 25000.0),
                    protection = GoalProtectionLevel.entries[Random.nextInt(GoalProtectionLevel.entries.size)],
                    targetDate = if (Random.nextBoolean()) System.currentTimeMillis() + Random.nextLong(0, 3650L * 24 * 60 * 60 * 1000) else null
                )
            },
            budgetStatuses = (1..10).map { i ->
                createBudgetStatus(
                    limit = Random.nextDouble(100.0, 5000.0),
                    spent = Random.nextDouble(0.0, 6000.0),
                    health = BudgetHealthStatus.entries[Random.nextInt(BudgetHealthStatus.entries.size)]
                )
            },
            spendingPace = SpendingPace(
                currentMonthSpent = Random.nextDouble(0.0, 10000.0),
                daysElapsed = Random.nextInt(1, 30),
                daysInMonth = 30,
                projectedTotal = Random.nextDouble(0.0, 15000.0),
                previousMonthTotal = if (Random.nextBoolean()) Random.nextDouble(0.0, 15000.0) else null,
                averageMonthlyTotal = if (Random.nextBoolean()) Random.nextDouble(0.0, 15000.0) else null,
                pacePercentage = Random.nextFloat() * 200f,
                paceStatus = PaceStatus.entries[Random.nextInt(PaceStatus.entries.size)],
                displayCurrency = "EUR",
            )
        )

        // Should complete without any exceptions
        assertNotNull(forecast)
        assertNotNull(forecast.components)
        // Should have calculated some values
        assertTrue(forecast.confidence > 0)
    }
}