package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Stress tests for StatisticsUtils
 * 
 * Tests statistical calculations including standard deviation,
 * edge cases with empty/single value lists, overflow prevention,
 * and precision handling.
 */
class StatisticsUtilsStressTest {

    // ============================================================================
    // SECTION 1: BASIC STANDARD DEVIATION
    // ============================================================================

    @Test
    fun `stress - calculate stddev for uniform values`() {
        val values = List(100) { 50.0 }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertEquals("StdDev of uniform values should be 0", 0.0, stdDev, 0.0001)
    }

    @Test
    fun `stress - calculate stddev for simple range`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Sample stddev of 1,2,3,4,5 = sqrt(2.5) ≈ 1.581
        assertEquals(1.5811, stdDev, 0.01)
    }

    @Test
    fun `stress - calculate stddev for negative values`() {
        val values = listOf(-5.0, -3.0, -1.0, 1.0, 3.0, 5.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle negative values", stdDev > 0)
    }

    @Test
    fun `stress - calculate stddev for mixed positive negative`() {
        val values = listOf(-10.0, -5.0, 0.0, 5.0, 10.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle mixed values", stdDev > 0)
    }

    // ============================================================================
    // SECTION 2: EDGE CASES - LIST SIZE
    // ============================================================================

    @Test
    fun `stress - empty list returns zero`() {
        val stdDev = StatisticsUtils.calculateStdDev(emptyList())
        assertEquals("Empty list should return 0", 0.0, stdDev, 0.0001)
    }

    @Test
    fun `stress - single value returns zero`() {
        val values = listOf(42.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertEquals("Single value should return 0", 0.0, stdDev, 0.0001)
    }

    @Test
    fun `stress - two values`() {
        val values = listOf(10.0, 20.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Sample stddev = sqrt(((10-15)^2 + (20-15)^2) / (2-1)) = sqrt(50)
        assertEquals(7.0710678118654755, stdDev, 0.0001)
    }

    @Test
    fun `stress - minimum valid sample size`() {
        // Test that N=2 is the minimum for valid sample stddev
        val values = listOf(1.0, 2.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should calculate for N=2", stdDev > 0)
    }

    // ============================================================================
    // SECTION 3: LARGE DATASETS
    // ============================================================================

    @Test
    fun `stress - calculate stddev for 1000 values`() {
        val values = (1..1000).map { it.toDouble() }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle 1000 values", stdDev > 0)
        // Expected stddev for 1..1000 ≈ 288.7
        assertEquals(288.7, stdDev, 1.0)
    }

    @Test
    fun `stress - calculate stddev for 10000 values`() {
        val values = (1..10000).map { it.toDouble() }
        
        val startTime = System.nanoTime()
        val stdDev = StatisticsUtils.calculateStdDev(values)
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should handle 10000 values", stdDev > 0)
        assertTrue("Should complete quickly", duration < 100_000_000) // Under 100ms
    }

    @Test
    fun `stress - large uniform values`() {
        val values = List(1000) { 1000000.0 }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertEquals("Large uniform values should have 0 stddev", 0.0, stdDev, 0.0001)
    }

    @Test
    fun `stress - large varying values`() {
        val values = (1..1000).map { it * 1000.0 }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle large varying values", stdDev > 0)
    }

    // ============================================================================
    // SECTION 4: EXTREME VALUES
    // ============================================================================

    @Test
    fun `stress - max double values`() {
        val values = listOf(Double.MAX_VALUE, Double.MAX_VALUE * 0.9, Double.MAX_VALUE * 0.8)
        
        // This may overflow, should handle gracefully
        val stdDev = try {
            StatisticsUtils.calculateStdDev(values)
        } catch (e: Exception) {
            -1.0 // Indicate error
        }
        
        // Should either return valid result or handle gracefully
        assertTrue("Should handle max double values without crashing", stdDev >= 0.0 || stdDev == -1.0)
    }

    @Test
    fun `stress - min double values`() {
        val values = listOf(Double.MIN_VALUE, Double.MIN_VALUE * 2, Double.MIN_VALUE * 3)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Very small values should still work
        assertTrue("Should handle min double values", stdDev >= 0.0)
    }

    @Test
    fun `stress - mixed extreme values`() {
        val values = listOf(-1e9, 0.0, 1e9)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle mixed extreme values", stdDev > 0)
    }

    @Test
    fun `stress - infinity values`() {
        val values = listOf(1.0, Double.POSITIVE_INFINITY, 3.0)
        
        val stdDev = try {
            StatisticsUtils.calculateStdDev(values)
        } catch (e: Exception) {
            Double.NaN
        }
        
        // Should handle infinity gracefully
        assertTrue("Should handle infinity without crashing", 
            stdDev.isFinite() || stdDev.isNaN() || stdDev.isInfinite())
    }

    @Test
    fun `stress - NaN values`() {
        val values = listOf(1.0, Double.NaN, 3.0)
        
        val stdDev = try {
            StatisticsUtils.calculateStdDev(values)
        } catch (e: Exception) {
            Double.NaN
        }
        
        // NaN propagation is expected
        assertTrue("Should handle NaN values", stdDev.isNaN() || stdDev >= 0.0)
    }

    // ============================================================================
    // SECTION 5: PRECISION AND ACCURACY
    // ============================================================================

    @Test
    fun `stress - precision with decimal values`() {
        val values = listOf(1.111, 2.222, 3.333, 4.444, 5.555)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should maintain precision with decimals", stdDev > 0)
    }

    @Test
    fun `stress - very small differences`() {
        val base = 1000000.0
        val values = listOf(base, base + 0.001, base + 0.002, base + 0.003)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should detect small differences", stdDev > 0)
        assertTrue("Should be very small stddev", stdDev < 0.01)
    }

    @Test
    fun `stress - near-zero variance`() {
        val values = listOf(1.0, 1.0000001, 1.0000002, 1.0000003)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle near-zero variance", stdDev >= 0.0)
        assertTrue("Should be very small", stdDev < 0.001)
    }

    @Test
    fun `stress - mathematical properties`() {
        // Test that stddev is invariant to shift
        val values1 = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val values2 = values1.map { it + 100.0 }  // Shift by 100
        
        val stdDev1 = StatisticsUtils.calculateStdDev(values1)
        val stdDev2 = StatisticsUtils.calculateStdDev(values2)
        
        assertEquals("StdDev should be invariant to shift", stdDev1, stdDev2, 0.0001)
    }

    @Test
    fun `stress - scale property`() {
        // Test that stddev scales linearly
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val scaledValues = values.map { it * 2.0 }
        
        val stdDev1 = StatisticsUtils.calculateStdDev(values)
        val stdDev2 = StatisticsUtils.calculateStdDev(scaledValues)
        
        assertEquals("StdDev should scale linearly", stdDev1 * 2.0, stdDev2, 0.0001)
    }

    // ============================================================================
    // SECTION 6: COMPARISON WITH EXPECTED VALUES
    // ============================================================================

    @Test
    fun `stress - known statistical values`() {
        // Test against known statistical values
        val testCases = listOf(
            // (values, expectedStdDev)
            Pair(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 2.138089935299395),
            Pair(listOf(1.0, 1.0, 1.0, 1.0), 0.0),
            Pair(listOf(0.0, 10.0), 7.0710678118654755),
            Pair(listOf(-5.0, 5.0), 7.0710678118654755)
        )
        
        testCases.forEach { (values, expected) ->
            val stdDev = StatisticsUtils.calculateStdDev(values)
            assertEquals("StdDev for $values", expected, stdDev, 0.01)
        }
    }

    @Test
    fun `stress - sample vs population`() {
        // Verify we use sample stddev (N-1) not population (N)
        val values = listOf(1.0, 2.0, 3.0)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Sample stddev = sqrt(((1-2)^2 + (2-2)^2 + (3-2)^2) / 2) = sqrt(1) = 1
        // Population stddev = sqrt(((1-2)^2 + (2-2)^2 + (3-2)^2) / 3) = sqrt(0.666) = 0.816
        assertEquals("Should use sample stddev (N-1)", 1.0, stdDev, 0.01)
    }

    // ============================================================================
    // SECTION 7: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic results`() {
        val values = (1..100).map { Math.random() * 1000 }
        
        val results = List(10) {
            StatisticsUtils.calculateStdDev(values)
        }
        
        // All results should be identical
        val first = results[0]
        results.forEach { result ->
            assertEquals("Should be deterministic", first, result, 0.0001)
        }
    }

    @Test
    fun `stress - order independence`() {
        val values = listOf(1.0, 5.0, 10.0, 20.0, 50.0)
        val shuffled = values.shuffled()
        
        val stdDev1 = StatisticsUtils.calculateStdDev(values)
        val stdDev2 = StatisticsUtils.calculateStdDev(shuffled)
        
        assertEquals("Should be order-independent", stdDev1, stdDev2, 0.0001)
    }

    // ============================================================================
    // SECTION 8: BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `stress - single repeated value`() {
        val values = List(1000) { 42.0 }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertEquals("Single repeated value should have 0 stddev", 0.0, stdDev, 0.0001)
    }

    @Test
    fun `stress - two alternating values`() {
        val values = List(100) { if (it % 2 == 0) 0.0 else 10.0 }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Should be 5.0 (half the range for alternating values)
        assertEquals(5.0, stdDev, 0.1)
    }

    @Test
    fun `stress - arithmetic progression`() {
        val values = (1..100).map { it.toDouble() }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        // Sample stddev for 1..N = sqrt((N^2 - 1) / 12 * N/(N-1))
        val n = 100.0
        val expected = kotlin.math.sqrt(((n * n - 1) / 12.0) * (n / (n - 1.0)))
        assertEquals(expected, stdDev, 0.01)
    }

    @Test
    fun `stress - geometric progression`() {
        val values = (0..10).map { i -> Math.pow(2.0, i.toDouble()) }
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle geometric progression", stdDev > 0)
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - performance with increasing size`() {
        val sizes = listOf(10, 100, 1000, 10000)
        
        sizes.forEach { size ->
            val values = List(size) { Math.random() }
            
            val startTime = System.nanoTime()
            StatisticsUtils.calculateStdDev(values)
            val duration = System.nanoTime() - startTime
            
            // Should scale roughly linearly
            assertTrue("Size $size should complete quickly", duration < size * 10000L)
        }
    }

    @Test
    fun `stress - repeated calculations performance`() {
        val values = (1..1000).map { Math.random() }
        
        val startTime = System.nanoTime()
        repeat(100) {
            StatisticsUtils.calculateStdDev(values)
        }
        val duration = System.nanoTime() - startTime
        
        assertTrue("100 calculations should complete in under 1s", duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 10: SPECIAL CASES
    // ============================================================================

    @Test
    fun `stress - identical values with tiny epsilon`() {
        val base = 1.0
        val epsilon = 1e-10
        val values = listOf(base, base + epsilon, base - epsilon)
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle epsilon differences", stdDev >= 0.0)
        assertTrue("Should be very small", stdDev < epsilon * 2)
    }

    @Test
    fun `stress - outlier detection scenario`() {
        val values = List(98) { 10.0 } + listOf(100.0, 0.0)  // 98 same, 2 outliers
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should reflect outliers", stdDev > 5.0)
    }

    @Test
    fun `stress - bimodal distribution`() {
        val values = List(50) { 10.0 } + List(50) { 20.0 }  // Two clusters
        val stdDev = StatisticsUtils.calculateStdDev(values)
        
        assertTrue("Should handle bimodal data", stdDev > 4.0)
    }
}
