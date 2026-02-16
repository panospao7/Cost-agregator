package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SynthesisEngineTest {

    private val engine = SynthesisEngine()

    @Test
    fun `patterns with confidence 0_89 to 0_90 are included in likely bills`() {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        
        // Ensure we are testing a date within this month, ideally tomorrow to be safe vs "startOfToday"
        // If today is the last day, we can't easily test "upcoming".
        // But the logic checks: nextExpectedDate >= startOfToday && nextExpectedDate <= endOfMonth
        
        val testAmount = 100.0
        
        // Case 1: Confidence 0.895 (The gap case)
        val gapPattern = RecurringPattern(
            id = 1,
            merchantName = "GapMerchant",
            averageAmount = testAmount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = now, // Due today/now
            confidence = 0.895f,
            previousDates = emptyList()
        )
        
        // Case 2: Confidence 0.70 (Lower bound)
        val lowerBound = RecurringPattern(
            id = 2,
            merchantName = "Lower",
            averageAmount = testAmount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = now,
            confidence = 0.70f,
            previousDates = emptyList()
        )
        
        // Case 3: Confidence 0.90 (Upper bound - should be COMMITTED, not Likely)
        val committed = RecurringPattern(
            id = 3,
            merchantName = "Committed",
            averageAmount = testAmount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = now,
            confidence = 0.90f,
            previousDates = emptyList()
        )

        val result = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(gapPattern, lowerBound, committed),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = SpendingPace(0.0, 1, 30, 0.0, null, null, 0.0f, PaceStatus.ON_PACE)
        )

        // totalLikely should include gapPattern (100) and lowerBound (100) = 200
        // committed (100) should be in totalCommitted
        
        assertEquals("Likely bills should include 0.895 confidence", 200.0, result.components.totalLikely, 0.01)
        assertEquals("Committed bills should include 0.90 confidence", 100.0, result.components.totalCommitted, 0.01)
    }

    @Test
    fun `no budget scenario returns non-critical risk level`() {
        // When checkBudgets is empty or budgetLimit is 0
        val result = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(), // No budgets
            spendingPace = SpendingPace(
                currentMonthSpent = 500.0,
                daysElapsed = 15,
                daysInMonth = 30,
                projectedTotal = 1000.0, // formerly currentDailyAverage
                averageMonthlyTotal = 1000.0,
                previousMonthTotal = 1000.0,
                pacePercentage = 0.5f,
                paceStatus = PaceStatus.OVER_PACE // Even if over pace
            )
        )

        // Budget limit is 0, so bufferRatio is 0.
        // Usually OverPace + Low Buffer -> CRITICAL.
        // But with limit <= 0, we expect Medium or Low, avoiding Critical artificiality.
        
        assertNotEquals("Risk Should NOT be CRITICAL for no budget", RiskLevel.CRITICAL, result.components.riskLevel)
        assertEquals("Risk should be MEDIUM for over pace without budget", RiskLevel.MEDIUM, result.components.riskLevel)
    }

    @Test
    fun `empty inputs return valid forecast`() {
        // Stress test with absolutely nothing
        val result = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = SpendingPace(0.0, 1, 30, 0.0, null, null, 0.0f, PaceStatus.NO_BASELINE)
        )

        assertNotNull(result)
        assertEquals(0.0, result.components.totalCommitted, 0.01)
        assertEquals(0.0, result.components.totalLikely, 0.01)
        assertEquals(RiskLevel.LOW, result.components.riskLevel)
    }

    @Test
    fun `discretionary budget calculation correctness`() {
        // Scenario:
        // Budget Limit: 1000
        // Spent: 200
        // Committed Upcoming: 100
        // Likely Upcoming: 100
        // Goal Reserve: 100
        // Expected Discretionary: 1000 - (200 + 100 + 100 + 100) = 500
        
        val now = System.currentTimeMillis()

        // Mock data
        val budgetStatus = BudgetStatus(
            budget = com.yourname.expensetracker.data.database.entity.Budget(
                id = 1, amount = 1000.0, categoryId = null, period = com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY,
                startDate = System.currentTimeMillis()
            ),
            category = null,
            spentAmount = 200.0,
            remainingAmount = 800.0,
            percentUsed = 0.2f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now,
            periodEnd = now + 2592000000L // ~30 days
        )
        
        val committedPattern = RecurringPattern(
            id = 1, merchantName = "C", averageAmount = 100.0, currency = "EUR", frequency = RecurrenceFrequency.MONTHLY, 
            periodVarianceDays = 0, amountVariancePercent = 0.0, confidence = 0.95f, nextExpectedDate = now, previousDates = emptyList()
        )
        val likelyPattern = RecurringPattern(
            id = 2, merchantName = "L", averageAmount = 100.0, currency = "EUR", frequency = RecurrenceFrequency.MONTHLY, 
            periodVarianceDays = 0, amountVariancePercent = 0.0, confidence = 0.80f, nextExpectedDate = now, previousDates = emptyList()
        )
        
        // Goal: Target 1000, Current 0. Due in 10 months. Monthly reserve ~100?
        // Let's create a goal due in exactly 1 month from now to enforce 100 reserve
        // If remaining is 100, and targetDate is 1 month (30 days) away.
        // daysRemainingInGoal = 30. targetMonthsRemaining = 1.0. remainingMonthly = 100.
        val goalTargetDate = now + (30L * 24 * 60 * 60 * 1000L) + 100000L // a bit more than 30 days
        val strictGoal = SavingsGoal(
            id = 1, name = "Goal", targetAmount = 100.0, currentAmount = 0.0, 
            targetDate = goalTargetDate, 
            protectionLevel = GoalProtectionLevel.STRICT
        )

        val result = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(committedPattern, likelyPattern),
            plannedExpenses = emptyList(),
            savingsGoals = listOf(strictGoal),
            budgetStatuses = listOf(budgetStatus),
            spendingPace = SpendingPace(200.0, 15, 30, 0.0, null, null, 0.0f, PaceStatus.ON_PACE)
        )

        // Verify Calculation
        // Limit: 1000
        // Deductions: 200 (Spent) + 100 (Committed) + 100 (Likely) + ~100 (Goal) = 500
        // Result ~500
        
        val disc = result.components.discretionaryBudget
        // Allow valid floating point error and slight date calculation variances
        assertTrue("Discretionary budget should be approx 500, was $disc", disc in 490.0..510.0)
    }
}
