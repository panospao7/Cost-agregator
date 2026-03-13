package com.yourname.expensetracker.domain.location

import org.junit.Assert.*
import org.junit.Test

/**
 * Stress tests for SpendingHeatmapEngine
 * 
 * Tests grid-based clustering, log-normalization, weight calculation,
 * and edge cases for the heatmap generation algorithm.
 */
class SpendingHeatmapEngineStressTest {

    private val heatmapEngine = SpendingHeatmapEngine()

    // ============================================================================
    // SECTION 1: BASIC CLUSTERING
    // ============================================================================

    @Test
    fun `stress - cluster expenses within same grid cell`() {
        val expenses = listOf(
            createExpense(1, 40.712800, -74.006000, 10.0),  // Same cell
            createExpense(2, 40.712805, -74.006005, 20.0),  // Same cell (~0.7m away)
            createExpense(3, 40.712801, -74.006002, 15.0)   // Same cell
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals("Should cluster into single point", 1, heatmap.size)
        assertEquals("Should sum amounts", 45.0, heatmap[0].totalSpend, 0.01)
        assertEquals("Should count 3 transactions", 3, heatmap[0].count)
    }

    @Test
    fun `stress - separate expenses into different grid cells`() {
        // ~150m apart = different grid cells
        val expenses = listOf(
            createExpense(1, 40.712800, -74.006000, 10.0),
            createExpense(2, 40.712800 + 0.0015, -74.006000, 20.0),  // ~150m north
            createExpense(3, 40.712800, -74.006000 + 0.0015, 15.0)   // ~150m east
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals("Should create 3 separate cells", 3, heatmap.size)
    }

    @Test
    fun `stress - handle expenses at grid boundaries`() {
        // Expenses just on either side of a grid boundary
        val gridSize = 0.0015
        val baseLat = 40.7128 - (40.7128 % gridSize)
        
        val expenses = listOf(
            createExpense(1, baseLat + gridSize - 0.0001, -74.0060, 10.0),  // Just under boundary
            createExpense(2, baseLat + gridSize + 0.0001, -74.0060, 20.0)   // Just over boundary
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals("Should separate at boundary", 2, heatmap.size)
    }

    @Test
    fun `stress - cluster many expenses in same location`() {
        val expenses = (1..100).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + (Math.random() - 0.5) * 0.0001,  // Within ~10m
                lon = -74.0060 + (Math.random() - 0.5) * 0.0001,
                amount = (i * 10).toDouble()
            )
        }

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals("Should cluster all into single cell", 1, heatmap.size)
        assertEquals("Should have 100 transactions", 100, heatmap[0].count)
    }

    // ============================================================================
    // SECTION 2: LOG NORMALIZATION
    // ============================================================================

    @Test
    fun `stress - log normalization compresses large values`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 10.0),
            createExpense(2, 40.7200, -74.0100, 1000.0)  // 100x larger
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        // After log normalization: ln(11) ≈ 2.4, ln(1001) ≈ 6.9
        // Weights should be compressed, not 100:1 ratio
        val weight1 = heatmap.find { it.totalSpend == 10.0 }?.weight ?: 0f
        val weight2 = heatmap.find { it.totalSpend == 1000.0 }?.weight ?: 0f
        assertTrue("Log should compress ratio", weight2 / weight1 < 10f)
    }

    @Test
    fun `stress - handle very small spending values`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 0.01),
            createExpense(2, 40.7128 + 0.0015, -74.0060, 0.05)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        assertTrue("Should have valid weights", heatmap.all { it.weight in 0f..1f })
    }

    @Test
    fun `stress - handle zero spending`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 0.0),
            createExpense(2, 40.7128 + 0.0015, -74.0060, 100.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        val zeroPoint = heatmap.find { it.totalSpend == 0.0 }
        assertNotNull(zeroPoint)
        assertTrue("Zero should still have weight", zeroPoint?.weight!! >= 0f)
    }

    @Test
    fun `stress - handle very large spending values`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 1000000.0),  // 1 million
            createExpense(2, 40.7200, -74.0100, 10.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        assertTrue("Large value should have weight <= 1", heatmap[0].weight <= 1f)
    }

    // ============================================================================
    // SECTION 3: WEIGHT CALCULATION
    // ============================================================================

    @Test
    fun `stress - weights normalized to 0-1 range`() {
        val expenses = (1..20).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + i * 0.0015,
                lon = -74.0060,
                amount = (i * 100).toDouble()
            )
        }

        val heatmap = heatmapEngine.compute(expenses)

        assertTrue("All weights should be in 0-1 range", 
            heatmap.all { it.weight in 0f..1f })
    }

    @Test
    fun `stress - highest spending gets weight of 1`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),
            createExpense(2, 40.7200, -74.0100, 500.0),
            createExpense(3, 40.7300, -74.0200, 1000.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        val maxWeight = heatmap.maxOf { it.weight }
        assertEquals("Max weight should be 1.0", 1.0f, maxWeight, 0.01f)
    }

    @Test
    fun `stress - equal spending gets equal weights`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),
            createExpense(2, 40.7200, -74.0100, 100.0),
            createExpense(3, 40.7300, -74.0200, 100.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        val weights = heatmap.map { it.weight }.distinct()
        assertEquals("Equal spending should have equal weights", 1, weights.size)
    }

    // ============================================================================
    // SECTION 4: AVERAGE POSITION CALCULATION
    // ============================================================================

    @Test
    fun `stress - average position in cluster`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 10.0),
            createExpense(2, 40.7130, -74.0062, 10.0),
            createExpense(3, 40.7132, -74.0064, 10.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        val expectedLat = (40.7128 + 40.7130 + 40.7132) / 3
        val expectedLon = (-74.0060 + -74.0062 + -74.0064) / 3
        
        assertEquals(expectedLat, heatmap[0].latitude, 0.0001)
        assertEquals(expectedLon, heatmap[0].longitude, 0.0001)
    }

    @Test
    fun `stress - single expense position is exact`() {
        val expenses = listOf(
            createExpense(1, 40.712800, -74.006000, 100.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(40.712800, heatmap[0].latitude, 0.000001)
        assertEquals(-74.006000, heatmap[0].longitude, 0.000001)
    }

    // ============================================================================
    // SECTION 5: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - empty expense list returns empty heatmap`() {
        val heatmap = heatmapEngine.compute(emptyList())

        assertTrue(heatmap.isEmpty())
    }

    @Test
    fun `stress - single expense`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 50.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(1, heatmap.size)
        assertEquals(50.0, heatmap[0].totalSpend, 0.01)
        assertEquals(1, heatmap[0].count)
        assertEquals(1.0f, heatmap[0].weight, 0.01f)
    }

    @Test
    fun `stress - all expenses at same location`() {
        val expenses = (1..50).map { i ->
            createExpense(i.toLong(), 40.712800, -74.006000, 10.0)
        }

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(1, heatmap.size)
        assertEquals(500.0, heatmap[0].totalSpend, 0.01)
        assertEquals(50, heatmap[0].count)
    }

    @Test
    fun `stress - expenses distributed globally`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),    // NYC
            createExpense(2, 51.5074, -0.1278, 200.0),     // London
            createExpense(3, -33.8688, 151.2093, 150.0),   // Sydney
            createExpense(4, 35.6762, 139.6503, 180.0),    // Tokyo
            createExpense(5, -23.5505, -46.6333, 120.0)    // São Paulo
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(5, heatmap.size)
        assertTrue("All weights valid", heatmap.all { it.weight in 0f..1f })
    }

    @Test
    fun `stress - negative coordinates`() {
        val expenses = listOf(
            createExpense(1, -33.8688, 151.2093, 100.0),   // Sydney (S hemisphere)
            createExpense(2, -33.8700, 151.2100, 50.0)     // Nearby
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertTrue(heatmap.isNotEmpty())
        assertTrue("Should handle negative lat", heatmap[0].latitude < 0)
    }

    @Test
    fun `stress - coordinates at extreme latitudes`() {
        val expenses = listOf(
            createExpense(1, 89.9, 0.0, 100.0),   // Near North Pole
            createExpense(2, -89.9, 0.0, 100.0)   // Near South Pole
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        assertTrue(heatmap.any { it.latitude > 89.0 })
        assertTrue(heatmap.any { it.latitude < -89.0 })
    }

    @Test
    fun `stress - handle antimeridian crossing`() {
        val expenses = listOf(
            createExpense(1, 0.0, 179.9, 100.0),   // Just west of antimeridian
            createExpense(2, 0.0, -179.9, 100.0)   // Just east of antimeridian
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(2, heatmap.size)
        assertTrue(heatmap.any { it.longitude > 179.0 })
        assertTrue(heatmap.any { it.longitude < -179.0 })
    }

    @Test
    fun `stress - very small grid cells at high latitudes`() {
        // At high latitudes, 0.0015 degrees longitude is smaller distance
        val expenses = listOf(
            createExpense(1, 89.0, 0.0, 100.0),
            createExpense(2, 89.0, 0.002, 100.0)  // Very close in longitude
        )

        val heatmap = heatmapEngine.compute(expenses)

        // May or may not be in same cell depending on grid calculation
        assertTrue(heatmap.size in 1..2)
    }

    // ============================================================================
    // SECTION 6: LARGE SCALE TESTS
    // ============================================================================

    @Test
    fun `stress - handle 1000 expenses quickly`() {
        val expenses = (1..1000).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + (i % 20) * 0.0015,  // Create multiple cells
                lon = -74.0060 + (i % 20) * 0.0015,
                amount = (i % 100).toDouble()
            )
        }

        val startTime = System.nanoTime()
        val heatmap = heatmapEngine.compute(expenses)
        val duration = System.nanoTime() - startTime

        assertTrue("Should complete in under 1 second", duration < 1_000_000_000)
        assertTrue("Should have multiple cells", heatmap.size >= 10)
    }

    @Test
    fun `stress - handle 10000 expenses efficiently`() {
        val expenses = (1..10000).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + (Math.random() - 0.5) * 0.1,
                lon = -74.0060 + (Math.random() - 0.5) * 0.1,
                amount = Math.random() * 1000
            )
        }

        val startTime = System.nanoTime()
        val heatmap = heatmapEngine.compute(expenses)
        val duration = System.nanoTime() - startTime

        assertTrue("Should handle 10000 expenses", duration < 5_000_000_000) // Under 5 seconds
        assertTrue("Should create heatmap", heatmap.isNotEmpty())
    }

    // ============================================================================
    // SECTION 7: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic results for same input`() {
        val expenses = (1..100).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + (i % 10) * 0.0015,
                lon = -74.0060 + (i % 10) * 0.0015,
                amount = (i * 10).toDouble()
            )
        }

        val heatmap1 = heatmapEngine.compute(expenses)
        val heatmap2 = heatmapEngine.compute(expenses)
        val heatmap3 = heatmapEngine.compute(expenses)

        assertEquals("Should be deterministic", heatmap1.size, heatmap2.size)
        assertEquals("Should be deterministic", heatmap2.size, heatmap3.size)
        
        val totalSpend1 = heatmap1.sumOf { it.totalSpend }
        val totalSpend2 = heatmap2.sumOf { it.totalSpend }
        assertEquals("Total spend should match", totalSpend1, totalSpend2, 0.01)
    }

    @Test
    fun `stress - total spend preserved across clusters`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),
            createExpense(2, 40.7200, -74.0100, 200.0),
            createExpense(3, 40.7300, -74.0200, 300.0)
        )

        val heatmap = heatmapEngine.compute(expenses)
        val totalHeatmapSpend = heatmap.sumOf { it.totalSpend }
        val originalTotal = expenses.sumOf { it.amount }

        assertEquals("Total spend should be preserved", originalTotal, totalHeatmapSpend, 0.01)
    }

    // ============================================================================
    // SECTION 8: SPECIAL CASES
    // ============================================================================

    @Test
    fun `stress - handle expenses with same timestamp`() {
        val timestamp = System.currentTimeMillis()
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0, timestamp),
            createExpense(2, 40.7128, -74.0060, 200.0, timestamp),
            createExpense(3, 40.7128, -74.0060, 300.0, timestamp)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(1, heatmap.size)
        assertEquals(600.0, heatmap[0].totalSpend, 0.01)
        assertEquals(3, heatmap[0].count)
    }

    @Test
    fun `stress - handle mixed very large and very small values`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 0.001),
            createExpense(2, 40.7128, -74.0060, 1000000.0),
            createExpense(3, 40.7128, -74.0060, 50.0)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(1, heatmap.size)
        assertEquals(1000050.001, heatmap[0].totalSpend, 0.01)
        assertTrue("Weight should be valid", heatmap[0].weight in 0f..1f)
    }

    @Test
    fun `stress - handle duplicate expense IDs`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),
            createExpense(1, 40.7128, -74.0060, 200.0),  // Same ID
            createExpense(1, 40.7128, -74.0060, 300.0)   // Same ID again
        )

        val heatmap = heatmapEngine.compute(expenses)

        // Should treat as separate expenses (IDs not used in clustering)
        assertEquals(1, heatmap.size)
        assertEquals(600.0, heatmap[0].totalSpend, 0.01)
        assertEquals(3, heatmap[0].count)
    }

    @Test
    fun `stress - single cell with max double value`() {
        val expenses = listOf(
            createExpense(1, 40.7128, -74.0060, Double.MAX_VALUE / 2),
            createExpense(2, 40.7128, -74.0060, Double.MAX_VALUE / 2)
        )

        val heatmap = heatmapEngine.compute(expenses)

        assertEquals(1, heatmap.size)
        assertTrue("Should handle large values", heatmap[0].totalSpend > 0)
        assertTrue("Weight should be valid", heatmap[0].weight in 0f..1f)
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE BENCHMARK
    // ============================================================================

    @Test
    fun `stress - large clustered dataset performance`() {
        // Create many expenses clustered in few locations
        val expenses = mutableListOf<LocatedExpense>()
        
        // 5 main locations with many expenses each
        for (location in 0..4) {
            for (expense in 1..100) {
                expenses.add(
                    createExpense(
                        id = (location * 100 + expense).toLong(),
                        lat = 40.7128 + location * 0.01,
                        lon = -74.0060 + location * 0.01,
                        amount = (expense * 10).toDouble()
                    )
                )
            }
        }

        val startTime = System.nanoTime()
        val heatmap = heatmapEngine.compute(expenses)
        val duration = System.nanoTime() - startTime

        assertEquals(5, heatmap.size)
        assertTrue("Should process quickly", duration < 500_000_000) // Under 500ms
    }

    // Helper function
    private fun createExpense(
        id: Long,
        lat: Double,
        lon: Double,
        amount: Double,
        date: Long = System.currentTimeMillis()
    ): LocatedExpense {
        return LocatedExpense(
            expenseId = id,
            latitude = lat,
            longitude = lon,
            amount = amount,
            merchant = "Test Merchant",
            date = date,
            locationSource = "test",
            placeId = null
        )
    }
}
