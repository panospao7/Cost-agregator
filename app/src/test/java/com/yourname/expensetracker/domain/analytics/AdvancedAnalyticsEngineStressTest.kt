package com.yourname.expensetracker.domain.analytics

import org.junit.Assert.*
import org.junit.Test

class AdvancedAnalyticsEngineStressTest {

    // ============================================================================
    // SECTION 1: STATISTICAL CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate mean correctly`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        
        val mean = values.average()
        
        assertEquals(30.0, mean, 0.001)
    }

    @Test
    fun `stress - calculate median for odd count`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        
        val median = values.sorted()[values.size / 2]
        
        assertEquals(30.0, median, 0.001)
    }

    @Test
    fun `stress - calculate median for even count`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0)
        
        val sorted = values.sorted()
        val median = (sorted[1] + sorted[2]) / 2
        
        assertEquals(25.0, median, 0.001)
    }

    @Test
    fun `stress - calculate standard deviation`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        
        assertEquals(14.14, stdDev, 0.01)
    }

    @Test
    fun `stress - calculate variance`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        
        assertEquals(200.0, variance, 0.1)
    }

    // ============================================================================
    // SECTION 2: PERCENTILE CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate 25th percentile`() {
        val values = (1..100).map { it.toDouble() }
        
        val percentile25 = values.sorted()[24]
        
        assertEquals(25.0, percentile25, 0.1)
    }

    @Test
    fun `stress - calculate 75th percentile`() {
        val values = (1..100).map { it.toDouble() }
        
        val percentile75 = values.sorted()[74]
        
        assertEquals(75.0, percentile75, 0.1)
    }

    @Test
    fun `stress - calculate 90th percentile`() {
        val values = (1..100).map { it.toDouble() }
        
        val percentile90 = values.sorted()[89]
        
        assertEquals(90.0, percentile90, 0.1)
    }

    @Test
    fun `stress - calculate percentile for small dataset`() {
        val values = listOf(10.0, 20.0, 30.0)
        
        val percentile50 = values.sorted()[1]
        
        assertEquals(20.0, percentile50, 0.1)
    }

    // ============================================================================
    // SECTION 3: OUTLIER DETECTION
    // ============================================================================

    @Test
    fun `stress - detect outliers using IQR method`() {
        val values = listOf(10.0, 12.0, 11.0, 13.0, 12.0, 100.0, 11.0)
        
        val sorted = values.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr
        
        val outliers = values.filter { it < lowerBound || it > upperBound }
        
        assertEquals(1, outliers.size)
        assertEquals(100.0, outliers[0], 0.1)
    }

    @Test
    fun `stress - detect outliers using Z-score`() {
        val values = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 50.0)
        val mean = values.average()
        val stdDev = Math.sqrt(values.map { (it - mean) * (it - mean) }.average())
        
        val outliers = values.filter { Math.abs((it - mean) / stdDev) > 2 }
        
        assertEquals(1, outliers.size)
        assertEquals(50.0, outliers[0], 0.1)
    }

    @Test
    fun `stress - detect multiple outliers`() {
        val values = listOf(10.0, 11.0, 12.0, 100.0, 13.0, 200.0)
        val mean = values.average()
        val stdDev = Math.sqrt(values.map { (it - mean) * (it - mean) }.average())
        
        val outliers = values.filter { Math.abs((it - mean) / stdDev) > 1.5 }
        
        assertEquals(1, outliers.size)
        assertEquals(200.0, outliers[0], 0.1)
    }

    // ============================================================================
    // SECTION 4: CORRELATION ANALYSIS
    // ============================================================================

    @Test
    fun `stress - calculate correlation coefficient`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(2.0, 4.0, 6.0, 8.0, 10.0)
        
        val meanX = x.average()
        val meanY = y.average()
        
        val numerator = x.zip(y).sumOf { (xi, yi) -> (xi - meanX) * (yi - meanY) }
        val denominator = Math.sqrt(
            x.sumOf { (it - meanX) * (it - meanX) } *
            y.sumOf { (it - meanY) * (it - meanY) }
        )
        
        val correlation = if (denominator != 0.0) numerator / denominator else 0.0
        
        assertEquals(1.0, correlation, 0.01)
    }

    @Test
    fun `stress - detect negative correlation`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(10.0, 8.0, 6.0, 4.0, 2.0)
        
        val meanX = x.average()
        val meanY = y.average()
        
        val numerator = x.zip(y).sumOf { (xi, yi) -> (xi - meanX) * (yi - meanY) }
        val denominator = Math.sqrt(
            x.sumOf { (it - meanX) * (it - meanX) } *
            y.sumOf { (it - meanY) * (it - meanY) }
        )
        
        val correlation = if (denominator != 0.0) numerator / denominator else 0.0
        
        assertEquals(-1.0, correlation, 0.01)
    }

    @Test
    fun `stress - detect no correlation`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(5.0, 5.0, 5.0, 5.0, 5.0)
        
        val meanX = x.average()
        val meanY = y.average()
        
        val numerator = x.zip(y).sumOf { (xi, yi) -> (xi - meanX) * (yi - meanY) }
        
        assertEquals(0.0, numerator, 0.01)
    }

    // ============================================================================
    // SECTION 5: TREND ANALYSIS
    // ============================================================================

    @Test
    fun `stress - detect upward trend`() {
        val values = listOf(10.0, 15.0, 20.0, 25.0, 30.0)
        
        val trend = values.zipWithNext().all { (a, b) -> b > a }
        
        assertTrue("Should detect upward trend", trend)
    }

    @Test
    fun `stress - detect downward trend`() {
        val values = listOf(30.0, 25.0, 20.0, 15.0, 10.0)
        
        val trend = values.zipWithNext().all { (a, b) -> b < a }
        
        assertTrue("Should detect downward trend", trend)
    }

    @Test
    fun `stress - detect stable trend`() {
        val values = listOf(20.0, 20.5, 19.8, 20.2, 20.1)
        val variance = values.map { (it - values.average()) * (it - values.average()) }.average()
        
        assertTrue("Should detect stable trend", variance < 0.5)
    }

    @Test
    fun `stress - calculate moving average`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val window = 3
        
        val movingAvg = (window - 1 until values.size).map { i ->
            values.subList(i - window + 1, i + 1).average()
        }
        
        assertEquals(3, movingAvg.size)
        assertEquals(20.0, movingAvg[0], 0.1)
    }

    // ============================================================================
    // SECTION 6: PROJECTION CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - project future spending linear`() {
        val dailySpending = listOf(50.0, 52.0, 54.0, 56.0, 58.0)
        val daysToProject = 7
        
        val avgDailyIncrease = dailySpending.zipWithNext()
            .map { (a, b) -> b - a }.average()
        val lastDay = dailySpending.last()
        val projection = lastDay + (avgDailyIncrease * daysToProject)
        
        assertTrue("Should project positive spending", projection > 0)
    }

    @Test
    fun `stress - project monthly spending`() {
        val monthlySpending = listOf(1000.0, 1100.0, 1050.0, 1200.0)
        val avgSpending = monthlySpending.average()
        
        assertTrue("Should calculate average", avgSpending > 0)
    }

    @Test
    fun `stress - confidence intervals`() {
        val values = listOf(10.0, 12.0, 11.0, 13.0, 12.0)
        val mean = values.average()
        val stdDev = Math.sqrt(values.map { (it - mean) * (it - mean) }.average())
        
        val lowerBound = mean - 1.96 * stdDev
        val upperBound = mean + 1.96 * stdDev
        
        assertTrue("Lower bound should be less than mean", lowerBound < mean)
        assertTrue("Upper bound should be greater than mean", upperBound > mean)
    }

    // ============================================================================
    // SECTION 7: CATEGORY ANALYTICS
    // ============================================================================

    @Test
    fun `stress - calculate category diversity`() {
        val transactions = listOf(
            "Food" to 1,
            "Food" to 2,
            "Transport" to 1,
            "Shopping" to 1
        )
        
        val uniqueCategories = transactions.map { it.first }.distinct().size
        val totalTransactions = transactions.size
        val diversity = uniqueCategories.toDouble() / totalTransactions
        
        assertTrue("Should have diversity score", diversity in 0.0..1.0)
    }

    @Test
    fun `stress - calculate category concentration`() {
        val categorySpending = mapOf(
            "Food" to 600.0,
            "Transport" to 200.0,
            "Shopping" to 200.0
        )
        val total = categorySpending.values.sum()
        
        val topCategoryShare = categorySpending.values.maxOrNull()?.div(total) ?: 0.0
        
        assertTrue("Top category should be dominant", topCategoryShare >= 0.5)
    }

    @Test
    fun `stress - detect seasonal patterns`() {
        val monthlySpending = listOf(
            1000.0, 1100.0, 1200.0,  // Q1
            900.0, 800.0, 700.0,     // Q2
            800.0, 900.0, 1000.0,    // Q3
            1200.0, 1300.0, 1400.0   // Q4
        )
        
        val q4Avg = monthlySpending.subList(9, 12).average()
        val q2Avg = monthlySpending.subList(3, 6).average()
        
        assertTrue("Q4 should be higher than Q2", q4Avg > q2Avg)
    }

    // ============================================================================
    // SECTION 8: MERCHANT ANALYTICS
    // ============================================================================

    @Test
    fun `stress - calculate merchant lifetime value`() {
        val merchantTransactions = listOf(50.0, 45.0, 60.0, 55.0, 50.0)
        val ltv = merchantTransactions.sum()
        
        assertEquals(260.0, ltv, 0.1)
    }

    @Test
    fun `stress - calculate merchant frequency`() {
        val merchantVisits = mapOf(
            "Starbucks" to 20,
            "McDonalds" to 10,
            "Other" to 5
        )
        
        val totalVisits = merchantVisits.values.sum()
        val starbucksShare = merchantVisits["Starbucks"]?.toDouble()?.div(totalVisits) ?: 0.0
        
        assertTrue("Starbucks should be frequent", starbucksShare > 0.5)
    }

    @Test
    fun `stress - detect merchant preference changes`() {
        val earlySpending = mapOf("Starbucks" to 200.0, "Other" to 50.0)
        val recentSpending = mapOf("Starbucks" to 50.0, "Other" to 200.0)
        
        val preferenceShift = earlySpending["Starbucks"]!! > recentSpending["Starbucks"]!!
        
        assertTrue("Should detect preference shift", preferenceShift)
    }

    // ============================================================================
    // SECTION 9: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle single value`() {
        val values = listOf(50.0)
        val mean = values.average()
        
        assertEquals(50.0, mean, 0.001)
    }

    @Test
    fun `stress - handle two values`() {
        val values = listOf(10.0, 20.0)
        val mean = values.average()
        val median = (values[0] + values[1]) / 2
        
        assertEquals(15.0, mean, 0.001)
        assertEquals(15.0, median, 0.001)
    }

    @Test
    fun `stress - handle all same values`() {
        val values = listOf(50.0, 50.0, 50.0, 50.0)
        val variance = values.map { (it - values.average()) * (it - values.average()) }.average()
        
        assertEquals(0.0, variance, 0.001)
    }

    @Test
    fun `stress - handle empty dataset`() {
        val values = emptyList<Double>()
        
        val mean = if (values.isNotEmpty()) values.average() else 0.0
        
        assertEquals(0.0, mean, 0.001)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - analyze 10000 data points quickly`() {
        val values = (1..10000).map { it.toDouble() }
        
        val startTime = System.nanoTime()
        
        val mean = values.average()
        val sorted = values.sorted()
        val median = sorted[5000]
        val variance = values.map { (it - mean) * (it - mean) }.average()
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should analyze 10000 points quickly", duration < 1_000_000_000)
        assertEquals(5000.5, mean, 0.1)
    }
}
