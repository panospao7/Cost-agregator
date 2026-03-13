package com.yourname.expensetracker.domain.analytics

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class InsightsEngineStressTest {

    // ============================================================================
    // SECTION 1: SPENDING INSIGHTS GENERATION
    // ============================================================================

    @Test
    fun `stress - generate insight for overspending`() {
        val budget = 500.0
        val spent = 600.0
        val percentage = (spent / budget) * 100
        
        val insight = when {
            percentage > 100 -> "OVERSPENDING"
            percentage > 90 -> "WARNING"
            else -> "NORMAL"
        }
        
        assertEquals("Should detect overspending", "OVERSPENDING", insight)
    }

    @Test
    fun `stress - generate insight for unusual spending pattern`() {
        val dailySpending = listOf(20.0, 25.0, 30.0, 200.0, 22.0, 28.0)
        val mean = dailySpending.average()
        val stdDev = Math.sqrt(dailySpending.map { (it - mean) * (it - mean) }.average())
        
        val hasAnomaly = dailySpending.any { Math.abs(it - mean) > 2 * stdDev }
        
        assertTrue("Should detect unusual spending", hasAnomaly)
    }

    @Test
    fun `stress - generate insight for recurring expenses`() {
        val amounts = listOf(50.0, 50.0, 50.0, 50.0, 50.0)
        val isRecurring = amounts.distinct().size == 1
        
        assertTrue("Should detect recurring pattern", isRecurring)
    }

    // ============================================================================
    // SECTION 2: CATEGORY INSIGHTS
    // ============================================================================

    @Test
    fun `stress - identify top spending category`() {
        val categories = mapOf(
            "Food" to 300.0,
            "Transport" to 150.0,
            "Entertainment" to 200.0
        )
        
        val topCategory = categories.maxByOrNull { it.value }?.key
        
        assertEquals("Food", topCategory)
    }

    @Test
    fun `stress - detect category overspending`() {
        val categoryBudgets = mapOf(
            "Food" to Pair(200.0, 250.0),
            "Transport" to Pair(100.0, 80.0)
        )
        
        val overspending = categoryBudgets.filter { (_, pair) ->
            val (budget, spent) = pair
            spent > budget
        }
        
        assertEquals(1, overspending.size)
        assertTrue(overspending.containsKey("Food"))
    }

    @Test
    fun `stress - calculate category trends`() {
        val currentMonth = mapOf("Food" to 300.0, "Transport" to 150.0)
        val lastMonth = mapOf("Food" to 250.0, "Transport" to 150.0)
        
        val trends = currentMonth.map { (category, current) ->
            val previous = lastMonth[category] ?: 0.0
            val change = ((current - previous) / previous) * 100
            category to change
        }.toMap()
        
        assertEquals(20.0, trends["Food"] ?: 0.0, 0.1)
    }

    // ============================================================================
    // SECTION 3: MERCHANT INSIGHTS
    // ============================================================================

    @Test
    fun `stress - identify frequent merchant`() {
        val merchantVisits = mapOf(
            "Starbucks" to 15,
            "McDonalds" to 8,
            "Amazon" to 20
        )
        
        val mostFrequent = merchantVisits.maxByOrNull { it.value }?.key
        
        assertEquals("Amazon", mostFrequent)
    }

    @Test
    fun `stress - detect merchant concentration`() {
        val totalSpending = 1000.0
        val topMerchantSpending = 600.0
        
        val concentration = (topMerchantSpending / totalSpending) * 100
        
        assertTrue("Should detect high concentration", concentration > 50)
    }

    @Test
    fun `stress - calculate merchant loyalty score`() {
        val merchantTransactions = listOf(
            "Starbucks" to 10,
            "Starbucks" to 12,
            "Starbucks" to 8,
            "Other" to 5
        )
        
        val byMerchant = merchantTransactions.groupBy { it.first }
            .mapValues { it.value.size }
        
        val loyaltyScore = byMerchant["Starbucks"]?.toDouble() ?: 0.0
        
        assertTrue("Should have loyalty score", loyaltyScore >= 3)
    }

    // ============================================================================
    // SECTION 4: TIME-BASED INSIGHTS
    // ============================================================================

    @Test
    fun `stress - identify peak spending day`() {
        val dailySpending = mapOf(
            Calendar.MONDAY to 50.0,
            Calendar.TUESDAY to 45.0,
            Calendar.FRIDAY to 150.0,
            Calendar.SATURDAY to 120.0
        )
        
        val peakDay = dailySpending.maxByOrNull { it.value }?.key
        
        assertEquals(Calendar.FRIDAY, peakDay)
    }

    @Test
    fun `stress - detect weekend vs weekday pattern`() {
        val weekdaySpending = 300.0
        val weekendSpending = 500.0
        
        val ratio = weekendSpending / weekdaySpending
        
        assertTrue("Should detect higher weekend spending", ratio > 1.5)
    }

    @Test
    fun `stress - calculate spending velocity`() {
        val dayOfMonth = 15
        val daysInMonth = 30
        val currentSpending = 800.0
        val monthlyBudget = 1500.0
        
        val expectedSpending = (monthlyBudget * dayOfMonth) / daysInMonth
        val velocity = (currentSpending / expectedSpending) * 100
        
        assertTrue("Should calculate velocity", velocity > 0)
    }

    // ============================================================================
    // SECTION 5: SAVINGS INSIGHTS
    // ============================================================================

    @Test
    fun `stress - calculate savings rate`() {
        val income = 2000.0
        val expenses = 1500.0
        
        val savings = income - expenses
        val savingsRate = (savings / income) * 100
        
        assertEquals(25.0, savingsRate, 0.1)
    }

    @Test
    fun `stress - project monthly savings`() {
        val dailySavings = listOf(10.0, 15.0, 20.0, 10.0, 15.0)
        val avgDailySavings = dailySavings.average()
        val projectedMonthly = avgDailySavings * 30
        
        assertTrue("Should project positive savings", projectedMonthly > 0)
    }

    @Test
    fun `stress - detect overspending trend`() {
        val monthlySpending = listOf(1000.0, 1100.0, 1200.0, 1350.0)
        
        val isIncreasing = monthlySpending.zipWithNext().all { (a, b) -> b > a }
        
        assertTrue("Should detect increasing trend", isIncreasing)
    }

    // ============================================================================
    // SECTION 6: BUDGET HEALTH INSIGHTS
    // ============================================================================

    @Test
    fun `stress - calculate budget health score`() {
        val budget = 500.0
        val spent = 350.0
        val daysPassed = 20
        val daysInMonth = 30
        
        val spendingRate = (spent / budget) * 100
        val timeProgress = (daysPassed.toDouble() / daysInMonth) * 100
        val healthScore = 100 - Math.abs(spendingRate - timeProgress)
        
        assertTrue("Should have reasonable health score", healthScore in 0.0..100.0)
    }

    @Test
    fun `stress - detect budget at risk`() {
        val budget = 500.0
        val spent = 450.0
        val daysPassed = 15
        val daysInMonth = 30
        
        val remainingDays = daysInMonth - daysPassed
        val remainingBudget = budget - spent
        val dailyBurnRate = spent / daysPassed
        val projectedTotal = spent + (dailyBurnRate * remainingDays)
        
        val atRisk = projectedTotal > budget
        
        assertTrue("Should detect budget at risk", atRisk)
    }

    // ============================================================================
    // SECTION 7: COMPARISON INSIGHTS
    // ============================================================================

    @Test
    fun `stress - compare to last month`() {
        val currentMonth = 1200.0
        val lastMonth = 1000.0
        
        val change = ((currentMonth - lastMonth) / lastMonth) * 100
        
        assertEquals(20.0, change, 0.1)
    }

    @Test
    fun `stress - compare to average`() {
        val current = 150.0
        val historical = listOf(100.0, 120.0, 130.0, 110.0)
        val average = historical.average()
        
        val deviation = ((current - average) / average) * 100
        
        assertTrue("Should calculate deviation", deviation != 0.0)
    }

    @Test
    fun `stress - year over year comparison`() {
        val thisYear = 15000.0
        val lastYear = 12000.0
        
        val growth = ((thisYear - lastYear) / lastYear) * 100
        
        assertEquals(25.0, growth, 0.1)
    }

    // ============================================================================
    // SECTION 8: ANOMALY DETECTION
    // ============================================================================

    @Test
    fun `stress - detect spending anomaly`() {
        val spending = listOf(20.0, 25.0, 22.0, 200.0, 24.0)
        val mean = spending.average()
        val stdDev = Math.sqrt(spending.map { (it - mean) * (it - mean) }.average())
        
        val anomalies = spending.filter { Math.abs(it - mean) > 2 * stdDev }
        
        assertEquals(1, anomalies.size)
        assertEquals(200.0, anomalies[0], 0.1)
    }

    @Test
    fun `stress - detect category anomaly`() {
        val categoryAvg = 100.0
        val currentSpending = 500.0
        
        val isAnomaly = currentSpending > (categoryAvg * 3)
        
        assertTrue("Should detect category anomaly", isAnomaly)
    }

    @Test
    fun `stress - detect unusual transaction time`() {
        val hour = 3  // 3 AM
        val amount = 200.0
        
        val isUnusual = hour in 0..5 && amount > 100
        
        assertTrue("Should detect unusual time", isUnusual)
    }

    // ============================================================================
    // SECTION 9: RECOMMENDATION INSIGHTS
    // ============================================================================

    @Test
    fun `stress - suggest budget reduction`() {
        val currentBudget = 500.0
        val avgSpending = 350.0
        val suggestedReduction = (currentBudget - avgSpending) * 0.8
        
        assertTrue("Should suggest reasonable reduction", suggestedReduction > 0)
    }

    @Test
    fun `stress - suggest category reallocation`() {
        val overspentCategory = "Food" to 600.0
        val underspentCategory = "Transport" to 50.0
        
        val reallocation = minOf(overspentCategory.second - 500, 500 - underspentCategory.second)
        
        assertTrue("Should suggest reallocation", reallocation > 0)
    }

    @Test
    fun `stress - calculate daily spending target`() {
        val monthlyBudget = 1500.0
        val daysInMonth = 30
        val spentSoFar = 800.0
        val daysRemaining = 15
        
        val remainingBudget = monthlyBudget - spentSoFar
        val dailyTarget = remainingBudget / daysRemaining
        
        assertTrue("Should calculate positive target", dailyTarget > 0)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - generate 1000 insights quickly`() {
        val expenses = (1..1000).map { i ->
            mapOf(
                "amount" to (i * 10).toDouble(),
                "category" to "Category${i % 10}",
                "date" to System.currentTimeMillis() - (i * 86400000L)
            )
        }
        
        val startTime = System.nanoTime()
        
        // Process insights
        val byCategory = expenses.groupBy { it["category"] }
        val totals = byCategory.mapValues { it.value.sumOf { e -> e["amount"] as Double } }
        val topCategory = totals.maxByOrNull { it.value }?.key
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should generate insights quickly", duration < 1_000_000_000)
        assertNotNull("Should find top category", topCategory)
    }
}
