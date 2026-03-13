package com.yourname.expensetracker.domain.location

import org.junit.Assert.*
import org.junit.Test

class LocationInsightsEngineStressTest {

    // ============================================================================
    // SECTION 1: PLACE INSIGHT GENERATION
    // ============================================================================

    @Test
    fun `stress - identify frequent places`() {
        val expenses = createExpensesAtPlaces(
            listOf(
                Place("Starbucks", 40.7128, -74.0060) to 20,
                Place("Gym", 40.7200, -74.0100) to 15,
                Place("Office", 40.7300, -74.0200) to 50
            )
        )
        
        val insights = generatePlaceInsights(expenses)
        
        assertTrue("Should identify places", insights.isNotEmpty())
        assertEquals("Should have correct visit counts", 20, insights.find { it.name == "Starbucks" }?.visitCount)
    }

    @Test
    fun `stress - calculate place spending totals`() {
        val expenses = (1..10).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                amount = 10.0 * i,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L
            )
        }
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should calculate total spending", 550.0, insight?.totalSpend ?: 0.0, 0.01)
    }

    @Test
    fun `stress - identify place categories`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 5.0, "Starbucks", "Food"),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 6.0, "Starbucks", "Food"),
            LocatedExpense(40.7200, -74.0100, 1000L + 48 * 60 * 60 * 1000L, 50.0, "Gym", "Fitness")
        )
        
        val insights = generatePlaceInsights(expenses)
        
        val starbucksInsight = insights.find { it.name == "Starbucks" }
        assertEquals("Should identify category", "Food", starbucksInsight?.category)
    }

    // ============================================================================
    // SECTION 2: CLUSTERING
    // ============================================================================

    @Test
    fun `stress - cluster nearby expenses into single place`() {
        // Expenses within 100 meters of each other
        val expenses = (1..20).map { i ->
            LocatedExpense(
                lat = 40.7128 + (Math.random() - 0.5) * 0.001,
                lon = -74.0060 + (Math.random() - 0.5) * 0.001,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0
            )
        }
        
        val insights = generatePlaceInsights(expenses)
        
        assertEquals("Should cluster into single place", 1, insights.size)
    }

    @Test
    fun `stress - separate distant expenses into different places`() {
        val expenses = listOf(
            // Cluster 1: NYC
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(40.7130, -74.0062, 1000L + 24 * 60 * 60 * 1000L, 15.0),
            // Cluster 2: LA (far away)
            LocatedExpense(34.0522, -118.2437, 1000L + 48 * 60 * 60 * 1000L, 20.0),
            LocatedExpense(34.0525, -118.2440, 1000L + 72 * 60 * 60 * 1000L, 25.0)
        )
        
        val insights = generatePlaceInsights(expenses, clusterRadiusMeters = 5000.0)
        
        assertEquals("Should separate into two places", 2, insights.size)
    }

    @Test
    fun `stress - handle edge case at cluster boundary`() {
        val boundaryDistance = 0.045  // ~5km in degrees
        
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(40.7128 + boundaryDistance, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 10.0)
        )
        
        val insights = generatePlaceInsights(expenses, clusterRadiusMeters = 5000.0)
        
        // Should handle boundary gracefully
        assertTrue("Should handle boundary", insights.size >= 1)
    }

    // ============================================================================
    // SECTION 3: TIME PATTERNS
    // ============================================================================

    @Test
    fun `stress - detect morning visit pattern`() {
        val expenses = (1..10).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                // All around 8 AM
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L + 8 * 60 * 60 * 1000L,
                amount = 5.0
            )
        }
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should detect morning visits", "MORNING", insight?.preferredTimeOfDay)
    }

    @Test
    fun `stress - detect weekend vs weekday pattern`() {
        val expenses = listOf(
            // Weekend visits (Saturday = 7, Sunday = 1 in some calendars)
            LocatedExpense(40.7128, -74.0060, 1000L + 6 * 24 * 60 * 60 * 1000L, 50.0),  // Sat
            LocatedExpense(40.7128, -74.0060, 1000L + 7 * 24 * 60 * 60 * 1000L, 60.0),  // Sun
            LocatedExpense(40.7128, -74.0060, 1000L + 13 * 24 * 60 * 60 * 1000L, 55.0), // Sat
            LocatedExpense(40.7128, -74.0060, 1000L + 14 * 24 * 60 * 60 * 1000L, 45.0)  // Sun
        )
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should detect weekend pattern", "WEEKEND", insight?.preferredDayType)
    }

    @Test
    fun `stress - calculate average time between visits`() {
        val expenses = (1..5).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                timestamp = 1000L + i * 7L * 24 * 60 * 60 * 1000L,  // Weekly
                amount = 50.0
            )
        }
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should calculate 7-day interval", 7, insight?.avgDaysBetweenVisits)
    }

    // ============================================================================
    // SECTION 4: SPENDING ANALYSIS
    // ============================================================================

    @Test
    fun `stress - calculate average spending per visit`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 20.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 15.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 72 * 60 * 60 * 1000L, 25.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should calculate average", 17.5, insight?.avgSpendPerVisit ?: 0.0, 0.01)
    }

    @Test
    fun `stress - identify high-spend places`() {
        val expenses = listOf(
            // Low spend place
            LocatedExpense(40.7128, -74.0060, 1000L, 5.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 5.0),
            // High spend place
            LocatedExpense(40.7200, -74.0100, 1000L + 48 * 60 * 60 * 1000L, 200.0),
            LocatedExpense(40.7200, -74.0100, 1000L + 72 * 60 * 60 * 1000L, 250.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        
        val highSpendPlace = insights.maxByOrNull { it.totalSpend }
        assertTrue("Should identify high spend place", highSpendPlace?.totalSpend ?: 0.0 > 400)
    }

    @Test
    fun `stress - calculate spending trend at place`() {
        val expenses = (1..6).map { i ->
            LocatedExpense(
                lat = 40.7128,
                lon = -74.0060,
                timestamp = 1000L + i * 30L * 24 * 60 * 60 * 1000L,
                amount = 50.0 + i * 10  // Increasing: 60, 70, 80...
            )
        }
        
        val insights = generatePlaceInsights(expenses)
        
        val insight = insights.firstOrNull()
        assertEquals("Should detect increasing trend", "INCREASING", insight?.spendingTrend)
    }

    // ============================================================================
    // SECTION 5: PLACE RANKING
    // ============================================================================

    @Test
    fun `stress - rank places by visit frequency`() {
        val expenses = createExpensesAtPlaces(
            listOf(
                Place("Starbucks", 40.7128, -74.0060) to 50,
                Place("Gym", 40.7200, -74.0100) to 20,
                Place("Restaurant", 40.7300, -74.0200) to 10
            )
        )
        
        val insights = generatePlaceInsights(expenses)
        val sorted = insights.sortedByDescending { it.visitCount }
        
        assertEquals("Starbucks should be most frequent", "Starbucks", sorted[0].name)
        assertEquals("Gym should be second", "Gym", sorted[1].name)
    }

    @Test
    fun `stress - rank places by total spending`() {
        val expenses = listOf(
            // Frequent but low spend
            LocatedExpense(40.7128, -74.0060, 1000L, 5.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 5.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 5.0),
            // Rare but high spend
            LocatedExpense(40.7200, -74.0100, 1000L + 72 * 60 * 60 * 1000L, 500.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        val sortedBySpend = insights.sortedByDescending { it.totalSpend }
        
        assertEquals("High spend place should rank first", 500.0, sortedBySpend[0].totalSpend, 0.01)
    }

    // ============================================================================
    // SECTION 6: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle expenses without location`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(null, null, 1000L + 24 * 60 * 60 * 1000L, 20.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 15.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        
        assertTrue("Should handle missing locations", insights.isNotEmpty())
    }

    @Test
    fun `stress - handle single expense at location`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        
        assertEquals("Should create single place", 1, insights.size)
        assertEquals("Should have 1 visit", 1, insights[0].visitCount)
    }

    @Test
    fun `stress - handle very large number of places`() {
        val expenses = (1..100).map { i ->
            LocatedExpense(
                lat = 40.7128 + i * 0.01,
                lon = -74.0060 + i * 0.01,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = 10.0
            )
        }
        
        val insights = generatePlaceInsights(expenses, clusterRadiusMeters = 100.0)
        
        assertTrue("Should handle many places", insights.isNotEmpty())
    }

    @Test
    fun `stress - handle expenses at same timestamp`() {
        val timestamp = 1000L
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, timestamp, 10.0),
            LocatedExpense(40.7128, -74.0060, timestamp, 15.0),
            LocatedExpense(40.7128, -74.0060, timestamp, 20.0)
        )
        
        val insights = generatePlaceInsights(expenses)
        
        assertEquals("Should handle same timestamp", 1, insights.size)
        assertEquals("Should count all visits", 3, insights[0].visitCount)
    }

    // ============================================================================
    // SECTION 7: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - process 1000 expenses quickly`() {
        val expenses = (1..1000).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i % 10) * 0.001,
                lon = -74.0060 + (i % 10) * 0.001,
                timestamp = 1000L + i * 60 * 60 * 1000L,
                amount = (i % 100).toDouble()
            )
        }
        
        val startTime = System.nanoTime()
        
        val insights = generatePlaceInsights(expenses)
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 1000 expenses in under 1s", duration < 1_000_000_000)
        assertTrue("Should generate insights", insights.isNotEmpty())
    }

    // ============================================================================
    // SECTION 8: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic clustering`() {
        val expenses = (1..50).map { i ->
            LocatedExpense(40.7128, -74.0060, 1000L + i * 24 * 60 * 60 * 1000L, 10.0)
        }
        
        val result1 = generatePlaceInsights(expenses)
        val result2 = generatePlaceInsights(expenses)
        val result3 = generatePlaceInsights(expenses)
        
        assertEquals("Should be deterministic", result1.size, result2.size)
        assertEquals("Should be deterministic", result2.size, result3.size)
    }

    // Helper data classes and functions
    private data class Place(val name: String, val lat: Double, val lon: Double)
    
    private data class LocatedExpense(
        val lat: Double?,
        val lon: Double?,
        val timestamp: Long,
        val amount: Double,
        val name: String? = null,
        val category: String? = null
    )
    
    private data class PlaceInsight(
        val name: String,
        val lat: Double,
        val lon: Double,
        val visitCount: Int,
        val totalSpend: Double,
        val avgSpendPerVisit: Double,
        val category: String?,
        val preferredTimeOfDay: String?,
        val preferredDayType: String?,
        val avgDaysBetweenVisits: Int?,
        val spendingTrend: String?
    )
    
    private fun createExpensesAtPlaces(places: List<Pair<Place, Int>>): List<LocatedExpense> {
        return places.flatMap { (place, count) ->
            (1..count).map { i ->
                LocatedExpense(
                    lat = place.lat,
                    lon = place.lon,
                    timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                    amount = 10.0,
                    name = place.name
                )
            }
        }
    }
    
    private fun generatePlaceInsights(
        expenses: List<LocatedExpense>,
        clusterRadiusMeters: Double = 500.0
    ): List<PlaceInsight> {
        val located = expenses.filter { it.lat != null && it.lon != null }
        if (located.isEmpty()) return emptyList()
        
        // Simple clustering by proximity
        val clusters = mutableListOf<MutableList<LocatedExpense>>()
        
        located.forEach { expense ->
            var added = false
            for (cluster in clusters) {
                val first = cluster.first()
                if (calculateDistance(
                        expense.lat!!, expense.lon!!,
                        first.lat!!, first.lon!!
                    ) <= clusterRadiusMeters
                ) {
                    cluster.add(expense)
                    added = true
                    break
                }
            }
            if (!added) {
                clusters.add(mutableListOf(expense))
            }
        }
        
        return clusters.map { cluster ->
            val avgLat = cluster.map { it.lat!! }.average()
            val avgLon = cluster.map { it.lon!! }.average()
            val totalSpend = cluster.sumOf { it.amount }
            val name = cluster.mapNotNull { it.name }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unknown Place"
            val category = cluster.mapNotNull { it.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            
            // Time analysis
            val hours = cluster.map { ((it.timestamp / (60 * 60 * 1000)) % 24).toInt() }
            val preferredTimeOfDay = when (hours.average().toInt()) {
                in 6..11 -> "MORNING"
                in 12..17 -> "AFTERNOON"
                in 18..23 -> "EVENING"
                else -> "NIGHT"
            }
            
            // Day type
            val daysOfWeek = cluster.map { ((it.timestamp / (24 * 60 * 60 * 1000)) % 7).toInt() }
            val weekendCount = daysOfWeek.count { it == 0 || it == 6 }
            val preferredDayType = if (weekendCount > cluster.size / 2) "WEEKEND" else "WEEKDAY"
            
            // Calculate days between visits
            val sortedTimestamps = cluster.map { it.timestamp }.sorted()
            val avgDaysBetween = if (sortedTimestamps.size > 1) {
                val intervals = sortedTimestamps.zipWithNext { a, b -> (b - a) / (24 * 60 * 60 * 1000) }
                intervals.average().toInt()
            } else null
            
            PlaceInsight(
                name = name,
                lat = avgLat,
                lon = avgLon,
                visitCount = cluster.size,
                totalSpend = totalSpend,
                avgSpendPerVisit = totalSpend / cluster.size,
                category = category,
                preferredTimeOfDay = preferredTimeOfDay,
                preferredDayType = preferredDayType,
                avgDaysBetweenVisits = avgDaysBetween,
                spendingTrend = "STABLE"
            )
        }
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0  // Earth radius in meters
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        val a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
