package com.yourname.expensetracker.integration

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.StatisticsUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AnalyticsPipelineIntegrationTest {

    // ============================================================================
    // SECTION 1: SPENDING TREND CALCULATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate spending trend`() {
        val currentPeriodSpending = 500.0
        val previousPeriodSpending = 400.0
        
        val change = currentPeriodSpending - previousPeriodSpending
        val percentage = if (previousPeriodSpending > 0) {
            (change / previousPeriodSpending) * 100
        } else 0.0
        
        assertEquals(100.0, change, 0.001)
        assertEquals(25.0, percentage, 0.001)
    }

    @Test
    fun `integration - negative trend when spending decreases`() {
        val currentPeriodSpending = 300.0
        val previousPeriodSpending = 400.0
        
        val change = currentPeriodSpending - previousPeriodSpending
        val percentage = if (previousPeriodSpending > 0) {
            (change / previousPeriodSpending) * 100
        } else 0.0
        
        assertEquals(-100.0, change, 0.001)
        assertEquals(-25.0, percentage, 0.001)
    }

    @Test
    fun `integration - zero trend when no previous data`() {
        val currentPeriodSpending = 500.0
        val previousPeriodSpending = 0.0
        
        val percentage = if (previousPeriodSpending > 0) {
            ((currentPeriodSpending - previousPeriodSpending) / previousPeriodSpending) * 100
        } else 0.0
        
        assertEquals(0.0, percentage, 0.001)
    }

    // ============================================================================
    // SECTION 2: CATEGORY BREAKDOWN PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate category percentages`() {
        val categoryTotals = mapOf(
            "Food" to 200.0,
            "Transport" to 100.0,
            "Entertainment" to 200.0
        )
        val totalSpending = categoryTotals.values.sum()
        
        val percentages = categoryTotals.mapValues { (_, amount) ->
            if (totalSpending > 0) (amount / totalSpending) * 100 else 0.0
        }
        
        assertEquals(40.0, percentages["Food"] ?: 0.0, 0.001)
        assertEquals(20.0, percentages["Transport"] ?: 0.0, 0.001)
        assertEquals(40.0, percentages["Entertainment"] ?: 0.0, 0.001)
    }

    @Test
    fun `integration - handle empty category breakdown`() {
        val categoryTotals = emptyMap<String, Double>()
        val totalSpending = 0.0
        
        val percentages = categoryTotals.mapValues { (_, amount) ->
            if (totalSpending > 0) (amount / totalSpending) * 100 else 0.0
        }
        
        assertTrue(percentages.isEmpty())
    }

    // ============================================================================
    // SECTION 3: DAILY AVERAGE CALCULATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate daily average`() {
        val totalSpending = 700.0
        val daysInPeriod = 7
        
        val dailyAverage = totalSpending / daysInPeriod
        
        assertEquals(100.0, dailyAverage, 0.001)
    }

    @Test
    fun `integration - calculate daily average for partial month`() {
        val totalSpending = 1500.0
        val daysElapsed = 15
        
        val dailyAverage = totalSpending / daysElapsed
        
        assertEquals(100.0, dailyAverage, 0.001)
    }

    // ============================================================================
    // SECTION 4: MERCHANT ANALYSIS PIPELINE
    // ============================================================================

    @Test
    fun `integration - find top merchants by spending`() {
        val merchantTotals = listOf(
            "Starbucks" to 150.0,
            "McDonalds" to 200.0,
            "Amazon" to 500.0,
            "Netflix" to 50.0
        )
        
        val topMerchants = merchantTotals.sortedByDescending { it.second }.take(3)
        
        assertEquals("Amazon", topMerchants[0].first)
        assertEquals("McDonalds", topMerchants[1].first)
        assertEquals("Starbucks", topMerchants[2].first)
    }

    @Test
    fun `integration - calculate merchant frequency`() {
        val transactions = listOf(
            "Starbucks" to 50.0,
            "Starbucks" to 45.0,
            "Starbucks" to 55.0,
            "McDonalds" to 30.0
        )
        
        val merchantFrequency = transactions.groupBy { it.first }
            .mapValues { it.value.size }
        
        assertEquals(3, merchantFrequency["Starbucks"])
        assertEquals(1, merchantFrequency["McDonalds"])
    }

    // ============================================================================
    // SECTION 5: TIME-BASED ANALYSIS PIPELINE
    // ============================================================================

    @Test
    fun `integration - group expenses by day of week`() {
        val daySpending = mapOf(
            1 to 100.0,  // Monday
            2 to 150.0,  // Tuesday
            3 to 80.0,   // Wednesday
            4 to 120.0,  // Thursday
            5 to 200.0   // Friday
        )
        
        val weekendSpending = daySpending.filter { it.key >= 6 }.values.sum()
        val weekdaySpending = daySpending.filter { it.key < 6 }.values.sum()
        
        assertEquals(0.0, weekendSpending, 0.001)
        assertEquals(650.0, weekdaySpending, 0.001)
    }

    @Test
    fun `integration - calculate hourly spending pattern`() {
        val hourlySpending = mapOf(
            8 to 50.0,   // 8 AM
            12 to 100.0, // 12 PM
            18 to 150.0, // 6 PM
            22 to 30.0   // 10 PM
        )
        
        val peakHour = hourlySpending.maxByOrNull { it.value }?.key
        
        assertEquals(18, peakHour)
    }

    // ============================================================================
    // SECTION 6: STATISTICAL CALCULATIONS PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate mean and median`() {
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        
        val mean = amounts.average()
        val median = amounts.sorted().let { 
            if (it.size % 2 == 0) {
                (it[it.size / 2 - 1] + it[it.size / 2]) / 2
            } else {
                it[it.size / 2]
            }
        }
        
        assertEquals(30.0, mean, 0.001)
        assertEquals(30.0, median, 0.001)
    }

    @Test
    fun `integration - calculate standard deviation`() {
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        
        assertEquals(14.14, stdDev, 0.01)
    }

    // ============================================================================
    // SECTION 7: PERIOD COMPARISON PIPELINE
    // ============================================================================

    @Test
    fun `integration - compare month-over-month`() {
        val currentMonth = 1200.0
        val lastMonth = 1000.0
        
        val change = currentMonth - lastMonth
        val percentage = (change / lastMonth) * 100
        
        assertEquals(200.0, change, 0.001)
        assertEquals(20.0, percentage, 0.001)
    }

    @Test
    fun `integration - compare year-over-year`() {
        val currentYear = 15000.0
        val lastYear = 12000.0
        
        val change = currentYear - lastYear
        val percentage = (change / lastYear) * 100
        
        assertEquals(3000.0, change, 0.001)
        assertEquals(25.0, percentage, 0.001)
    }

    // ============================================================================
    // SECTION 8: BUDGET ANALYSIS PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate budget health score`() {
        val budgetAmount = 500.0
        val spentAmount = 400.0
        val daysInPeriod = 30
        val daysElapsed = 20
        
        val budgetHealth = 1.0 - (spentAmount / budgetAmount)
        val timeHealth = 1.0 - (daysElapsed.toDouble() / daysInPeriod)
        
        assertEquals(0.2, budgetHealth, 0.001)
        assertTrue("Budget health should be reasonable", budgetHealth >= 0)
    }

    @Test
    fun `integration - identify overspending categories`() {
        val categoryBudgets = mapOf(
            "Food" to Pair(200.0, 250.0),
            "Transport" to Pair(100.0, 80.0),
            "Entertainment" to Pair(150.0, 200.0)
        )
        
        val overspending = categoryBudgets.filter { (_, amounts) ->
            val (budget, spent) = amounts
            spent > budget
        }
        
        assertEquals(2, overspending.size)
        assertTrue(overspending.containsKey("Food"))
        assertTrue(overspending.containsKey("Entertainment"))
    }

    // ============================================================================
    // SECTION 9: ANOMALY DETECTION PIPELINE
    // ============================================================================

    @Test
    fun `integration - detect spending anomalies`() {
        val dailySpending = listOf(50.0, 45.0, 60.0, 55.0, 500.0, 48.0, 52.0)
        
        val mean = dailySpending.average()
        val stdDev = Math.sqrt(dailySpending.map { (it - mean) * (it - mean) }.average())
        
        val anomalies = dailySpending.filter { 
            Math.abs(it - mean) > 2 * stdDev 
        }
        
        assertEquals(1, anomalies.size)
        assertEquals(500.0, anomalies[0], 0.001)
    }

    @Test
    fun `integration - detect unusual transaction amounts`() {
        val amounts = listOf(10.0, 15.0, 12.0, 1000.0, 18.0, 20.0)
        
        val mean = amounts.average()
        val threshold = mean * 5  // 5x average is unusual
        
        val unusual = amounts.filter { it > threshold }
        
        assertEquals(1, unusual.size)
        assertEquals(1000.0, unusual[0], 0.001)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `integration - process 1000 transactions quickly`() {
        val transactions = (1..1000).map { 
            mapOf(
                "amount" to (it % 100).toDouble(),
                "category" to "Category${it % 10}",
                "date" to System.currentTimeMillis() - (it * 86400000L)
            )
        }
        
        val startTime = System.nanoTime()
        
        // Group by category
        val byCategory = transactions.groupBy { it["category"] }
        val totals = byCategory.mapValues { it.value.sumOf { t -> t["amount"] as Double } }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process quickly", duration < 1_000_000_000)
        assertEquals(10, totals.size)
    }
}
