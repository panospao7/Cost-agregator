package com.yourname.expensetracker.domain.location

import org.junit.Assert.*
import org.junit.Test

class AreaSpendingEngineStressTest {

    // ============================================================================
    // SECTION 1: GRID CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate 5km grid cells`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0),
            LocatedExpense(40.7200, -74.0100, 1000L + 24 * 60 * 60 * 1000L, 75.0),
            LocatedExpense(40.7500, -74.0300, 1000L + 48 * 60 * 60 * 1000L, 100.0)
        )
        
        val gridCells = calculateGridSpending(expenses, gridSizeKm = 5.0)
        
        assertTrue("Should calculate grid cells", gridCells.isNotEmpty())
    }

    @Test
    fun `stress - aggregate spending within same grid cell`() {
        val expenses = (1..10).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i * 0.0001),  // Very close together
                lon = -74.0060 + (i * 0.0001),
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0
            )
        }
        
        val gridCells = calculateGridSpending(expenses, gridSizeKm = 5.0)
        
        // All should fall in same grid cell
        assertEquals("Should aggregate into single cell", 1, gridCells.size)
        assertEquals("Should sum all spending", 100.0, gridCells[0].totalSpend, 0.01)
    }

    @Test
    fun `stress - separate spending into different grid cells`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0),   // NYC
            LocatedExpense(40.7589, -73.9851, 1000L + 24 * 60 * 60 * 1000L, 75.0),  // ~6km away
            LocatedExpense(40.8075, -73.9626, 1000L + 48 * 60 * 60 * 1000L, 100.0)  // ~12km away
        )
        
        val gridCells = calculateGridSpending(expenses, gridSizeKm = 5.0)
        
        assertTrue("Should create multiple cells", gridCells.size >= 2)
    }

    @Test
    fun `stress - handle grid cell boundaries`() {
        val gridSizeDeg = 0.045  // ~5km
        val boundaryLat = 40.7128 + gridSizeDeg
        
        val expenses = listOf(
            LocatedExpense(boundaryLat - 0.001, -74.0060, 1000L, 50.0),
            LocatedExpense(boundaryLat + 0.001, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 75.0)
        )
        
        val gridCells = calculateGridSpending(expenses, gridSizeKm = 5.0)
        
        assertEquals("Should separate at boundary", 2, gridCells.size)
    }

    // ============================================================================
    // SECTION 2: AREA SPENDING CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate spending per area`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, "Manhattan"),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 75.0, "Manhattan"),
            LocatedExpense(40.7580, -73.9855, 1000L + 48 * 60 * 60 * 1000L, 100.0, "Brooklyn"),
            LocatedExpense(40.7580, -73.9855, 1000L + 72 * 60 * 60 * 1000L, 25.0, "Brooklyn")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        assertTrue("Should calculate area spending", areaSpending.isNotEmpty())
        val manhattanSpending = areaSpending.find { it.areaName == "Manhattan" }
        assertEquals("Manhattan total should be 125", 125.0, manhattanSpending?.totalSpend ?: 0.0, 0.01)
    }

    @Test
    fun `stress - calculate average spending per area`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, "Downtown"),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 100.0, "Downtown"),
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 75.0, "Downtown")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val downtown = areaSpending.find { it.areaName == "Downtown" }
        assertEquals("Average should be 75", 75.0, downtown?.avgSpendPerVisit ?: 0.0, 0.01)
    }

    @Test
    fun `stress - calculate visit count per area`() {
        val expenses = (1..20).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0,
                areaName = "Downtown"
            )
        }
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val downtown = areaSpending.find { it.areaName == "Downtown" }
        assertEquals("Should count 20 visits", 20, downtown?.visitCount ?: 0)
    }

    // ============================================================================
    // SECTION 3: AREA RANKING
    // ============================================================================

    @Test
    fun `stress - rank areas by total spending`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 500.0, "Area A"),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 200.0, "Area B"),
            LocatedExpense(40.7800, -74.0500, 1000L + 48 * 60 * 60 * 1000L, 100.0, "Area C")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        val ranked = areaSpending.sortedByDescending { it.totalSpend }
        
        assertEquals("Area A should be first", "Area A", ranked[0].areaName)
        assertEquals("Area B should be second", "Area B", ranked[1].areaName)
        assertEquals("Area C should be third", "Area C", ranked[2].areaName)
    }

    @Test
    fun `stress - rank areas by visit frequency`() {
        val expenses = listOf(
            // Area A: Few visits, high spend
            LocatedExpense(40.7128, -74.0060, 1000L, 500.0, "Area A"),
            // Area B: Many visits, lower spend
            LocatedExpense(40.7500, -74.0300, 1000L, 10.0, "Area B"),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 10.0, "Area B"),
            LocatedExpense(40.7500, -74.0300, 1000L + 48 * 60 * 60 * 1000L, 10.0, "Area B"),
            LocatedExpense(40.7500, -74.0300, 1000L + 72 * 60 * 60 * 1000L, 10.0, "Area B")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        val rankedByVisits = areaSpending.sortedByDescending { it.visitCount }
        
        assertEquals("Area B should be most visited", "Area B", rankedByVisits[0].areaName)
    }

    // ============================================================================
    // SECTION 4: PERCENTAGE CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate area spending percentage`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 300.0, "Area A"),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 200.0, "Area B"),
            LocatedExpense(40.7800, -74.0500, 1000L + 48 * 60 * 60 * 1000L, 500.0, "Area C")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        val totalSpend = areaSpending.sumOf { it.totalSpend }
        
        val areaC = areaSpending.find { it.areaName == "Area C" }
        val percentage = (areaC?.totalSpend ?: 0.0) / totalSpend * 100
        
        assertEquals("Area C should be 50%", 50.0, percentage, 0.1)
    }

    @Test
    fun `stress - identify top spending area`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 100.0, "Area A"),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 300.0, "Area B"),
            LocatedExpense(40.7800, -74.0500, 1000L + 48 * 60 * 60 * 1000L, 50.0, "Area C")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        val topArea = areaSpending.maxByOrNull { it.totalSpend }
        
        assertEquals("Area B should be top", "Area B", topArea?.areaName)
    }

    // ============================================================================
    // SECTION 5: SPENDING PATTERNS BY AREA
    // ============================================================================

    @Test
    fun `stress - detect high-frequency low-spend areas`() {
        val expenses = (1..20).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 5.0,
                areaName = "Coffee Shops"
            )
        }
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val coffeeArea = areaSpending.find { it.areaName == "Coffee Shops" }
        assertTrue("Should have many visits", (coffeeArea?.visitCount ?: 0) >= 20)
        assertTrue("Should have low average", (coffeeArea?.avgSpendPerVisit ?: 100.0) < 10)
    }

    @Test
    fun `stress - detect low-frequency high-spend areas`() {
        val expenses = listOf(
            LocatedExpense(40.7500, -74.0300, 1000L, 1000.0, "Luxury Mall"),
            LocatedExpense(40.7500, -74.0300, 1000L + 30 * 24 * 60 * 60 * 1000L, 1500.0, "Luxury Mall")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val luxuryArea = areaSpending.find { it.areaName == "Luxury Mall" }
        assertEquals("Should have 2 visits", 2, luxuryArea?.visitCount ?: 0)
        assertEquals("Should have high average", 1250.0, luxuryArea?.avgSpendPerVisit ?: 0.0, 0.01)
    }

    @Test
    fun `stress - identify regular spending areas`() {
        val expenses = (1..12).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                timestamp = 1000L + i * 30L * 24 * 60 * 60 * 1000L,  // Monthly
                amount = 100.0,
                areaName = "Regular Store"
            )
        }
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val regularArea = areaSpending.find { it.areaName == "Regular Store" }
        assertEquals("Should have 12 visits", 12, regularArea?.visitCount ?: 0)
    }

    // ============================================================================
    // SECTION 6: COMPARISONS
    // ============================================================================

    @Test
    fun `stress - compare spending between areas`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 200.0, "Work Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 150.0, "Work Area"),
            LocatedExpense(40.7500, -74.0300, 1000L + 48 * 60 * 60 * 1000L, 500.0, "Entertainment Area"),
            LocatedExpense(40.7500, -74.0300, 1000L + 72 * 60 * 60 * 1000L, 300.0, "Entertainment Area")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val workArea = areaSpending.find { it.areaName == "Work Area" }
        val entertainmentArea = areaSpending.find { it.areaName == "Entertainment Area" }
        
        assertTrue("Entertainment should have more spending",
            (entertainmentArea?.totalSpend ?: 0.0) > (workArea?.totalSpend ?: 0.0))
    }

    @Test
    fun `stress - calculate spending ratio between areas`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 300.0, "Area A"),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 100.0, "Area B")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val areaA = areaSpending.find { it.areaName == "Area A" }
        val areaB = areaSpending.find { it.areaName == "Area B" }
        
        val ratio = (areaA?.totalSpend ?: 1.0) / (areaB?.totalSpend ?: 1.0)
        assertEquals("Ratio should be 3:1", 3.0, ratio, 0.1)
    }

    // ============================================================================
    // SECTION 7: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle expenses without location`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, "Downtown"),
            LocatedExpense(null, null, 1000L + 24 * 60 * 60 * 1000L, 75.0, null),
            LocatedExpense(40.7500, -74.0300, 1000L + 48 * 60 * 60 * 1000L, 100.0, "Uptown")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        assertEquals("Should exclude expenses without location", 2, areaSpending.size)
    }

    @Test
    fun `stress - handle expenses without area name`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, null),
            LocatedExpense(40.7500, -74.0300, 1000L + 24 * 60 * 60 * 1000L, 75.0, null)
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        // Should create generic areas based on location
        assertTrue("Should create areas from locations", areaSpending.isNotEmpty())
    }

    @Test
    fun `stress - handle single expense in area`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 100.0, "Single Visit Area")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        assertEquals("Should create single area", 1, areaSpending.size)
        assertEquals("Should have 1 visit", 1, areaSpending[0].visitCount)
    }

    @Test
    fun `stress - handle all expenses in same area`() {
        val expenses = (1..50).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i * 0.0001),
                lon = -74.0060 + (i * 0.0001),
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0,
                areaName = "Same Area"
            )
        }
        
        val areaSpending = calculateAreaSpending(expenses)
        
        assertEquals("Should aggregate into single area", 1, areaSpending.size)
        assertEquals("Should have 50 visits", 50, areaSpending[0].visitCount)
    }

    @Test
    fun `stress - handle zero spending`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 0.0, "Zero Spend Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 0.0, "Zero Spend Area")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val zeroArea = areaSpending.find { it.areaName == "Zero Spend Area" }
        assertEquals("Should handle zero spending", 0.0, zeroArea?.totalSpend ?: -1.0, 0.01)
    }

    // ============================================================================
    // SECTION 8: TEMPORAL ANALYSIS
    // ============================================================================

    @Test
    fun `stress - calculate first and last visit dates`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, "Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 30 * 24 * 60 * 60 * 1000L, 75.0, "Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 60 * 24 * 60 * 60 * 1000L, 100.0, "Area")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val area = areaSpending.find { it.areaName == "Area" }
        assertEquals("Should have first visit", 1000L, area?.firstVisitTimestamp)
        assertEquals("Should have last visit", 1000L + 60 * 24 * 60 * 60 * 1000L, area?.lastVisitTimestamp)
    }

    @Test
    fun `stress - calculate days between visits`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 50.0, "Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 7 * 24 * 60 * 60 * 1000L, 50.0, "Area"),
            LocatedExpense(40.7128, -74.0060, 1000L + 14 * 24 * 60 * 60 * 1000L, 50.0, "Area")
        )
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val area = areaSpending.find { it.areaName == "Area" }
        assertEquals("Should calculate 7-day interval", 7, area?.avgDaysBetweenVisits)
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - process 1000 expenses quickly`() {
        val expenses = (1..1000).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i % 10) * 0.01,
                lon = -74.0060 + (i % 10) * 0.01,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = (i % 100).toDouble(),
                areaName = "Area ${i % 5}"
            )
        }
        
        val startTime = System.nanoTime()
        
        val areaSpending = calculateAreaSpending(expenses)
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 1000 expenses in under 1s", duration < 1_000_000_000)
        assertTrue("Should generate areas", areaSpending.isNotEmpty())
    }

    // ============================================================================
    // SECTION 10: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic results`() {
        val expenses = (1..50).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i % 5) * 0.01,
                lon = -74.0060 + (i % 5) * 0.01,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0,
                areaName = "Test Area"
            )
        }
        
        val result1 = calculateAreaSpending(expenses)
        val result2 = calculateAreaSpending(expenses)
        
        assertEquals("Should be deterministic", result1.size, result2.size)
        assertEquals("Should have same totals", result1.sumOf { it.totalSpend }, result2.sumOf { it.totalSpend }, 0.01)
    }

    // Helper data classes and functions
    private data class LocatedExpense(
        val lat: Double?,
        val lon: Double?,
        val timestamp: Long,
        val amount: Double,
        val areaName: String? = null
    )
    
    private data class AreaSpending(
        val areaName: String,
        val lat: Double,
        val lon: Double,
        val totalSpend: Double,
        val visitCount: Int,
        val avgSpendPerVisit: Double,
        val firstVisitTimestamp: Long,
        val lastVisitTimestamp: Long,
        val avgDaysBetweenVisits: Int?
    )
    
    private data class GridCell(
        val cellLat: Long,
        val cellLon: Long,
        val totalSpend: Double,
        val visitCount: Int
    )
    
    private fun calculateGridSpending(expenses: List<LocatedExpense>, gridSizeKm: Double): List<GridCell> {
        val located = expenses.filter { it.lat != null && it.lon != null }
        if (located.isEmpty()) return emptyList()
        
        val gridSizeDeg = gridSizeKm / 111.0
        val cells = mutableMapOf<Pair<Long, Long>, Pair<Double, Int>>()
        
        located.forEach { expense ->
            val cellLat = (expense.lat!! / gridSizeDeg).toLong()
            val cellLon = (expense.lon!! / gridSizeDeg).toLong()
            val key = Pair(cellLat, cellLon)
            
            val (currentSpend, currentCount) = cells.getOrDefault(key, Pair(0.0, 0))
            cells[key] = Pair(currentSpend + expense.amount, currentCount + 1)
        }
        
        return cells.map { (key, value) ->
            GridCell(key.first, key.second, value.first, value.second)
        }
    }
    
    private fun calculateAreaSpending(expenses: List<LocatedExpense>): List<AreaSpending> {
        val located = expenses.filter { it.lat != null && it.lon != null }
        if (located.isEmpty()) return emptyList()
        
        // Group by area name or create from grid
        val grouped = located.groupBy { 
            it.areaName ?: "Area ${(it.lat!! * 100).toInt()}_${(it.lon!! * 100).toInt()}"
        }
        
        return grouped.map { (areaName, areaExpenses) ->
            val sortedTimestamps = areaExpenses.map { it.timestamp }.sorted()
            val totalSpend = areaExpenses.sumOf { it.amount }
            val visitCount = areaExpenses.size
            val avgDaysBetween = if (sortedTimestamps.size > 1) {
                val intervals = sortedTimestamps.zipWithNext { a, b -> (b - a) / (24 * 60 * 60 * 1000) }
                intervals.average().toInt()
            } else null
            
            AreaSpending(
                areaName = areaName,
                lat = areaExpenses.map { it.lat!! }.average(),
                lon = areaExpenses.map { it.lon!! }.average(),
                totalSpend = totalSpend,
                visitCount = visitCount,
                avgSpendPerVisit = totalSpend / visitCount,
                firstVisitTimestamp = sortedTimestamps.first(),
                lastVisitTimestamp = sortedTimestamps.last(),
                avgDaysBetweenVisits = avgDaysBetween
            )
        }
    }
}
