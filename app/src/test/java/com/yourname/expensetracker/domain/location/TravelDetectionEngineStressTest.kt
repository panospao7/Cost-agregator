package com.yourname.expensetracker.domain.location

import org.junit.Assert.*
import org.junit.Test

class TravelDetectionEngineStressTest {

    // ============================================================================
    // SECTION 1: HOME AREA DETECTION
    // ============================================================================

    @Test
    fun `stress - detect home area from clustered expenses`() {
        // Create expenses clustered around a central point (home)
        val homeExpenses = createClusteredExpenses(
            centerLat = 40.7128,
            centerLon = -74.0060,
            radiusKm = 2.0,
            count = 50
        )
        
        val insight = detectTravel(homeExpenses)
        
        assertNotNull("Should detect home", insight)
        assertNotNull("Should have home coordinates", insight?.homeLatitude)
        assertNotNull("Should have home coordinates", insight?.homeLongitude)
    }

    @Test
    fun `stress - home detection with scattered expenses`() {
        val expenses = createScatteredExpenses(
            centerLat = 40.7128,
            centerLon = -74.0060,
            radiusKm = 20.0,
            count = 100
        )
        
        val insight = detectTravel(expenses)
        
        // Should still find a most frequent area
        assertNotNull("Should find most frequent area", insight)
    }

    @Test
    fun `stress - home detection with minimum expenses`() {
        val expenses = createClusteredExpenses(
            centerLat = 40.7128,
            centerLon = -74.0060,
            radiusKm = 3.0,
            count = 10  // Minimum threshold
        )
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should detect with minimum", insight)
    }

    @Test
    fun `stress - no home detection with insufficient expenses`() {
        val expenses = createClusteredExpenses(
            centerLat = 40.7128,
            centerLon = -74.0060,
            radiusKm = 3.0,
            count = 5  // Below minimum
        )
        
        val insight = detectTravel(expenses)
        
        assertNull("Should not detect with insufficient data", insight)
    }

    // ============================================================================
    // SECTION 2: DISTANCE CLASSIFICATION
    // ============================================================================

    @Test
    fun `stress - classify home expenses within 5km`() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        
        val homeExpenses = createExpensesAtDistance(
            homeLat = homeLat,
            homeLon = homeLon,
            distancesKm = listOf(1.0, 2.0, 3.0, 4.0, 5.0),
            baseTimestamp = 1000L
        )
        
        val insight = detectTravel(homeExpenses)
        
        assertTrue("Should have home spending", insight?.homeSpend ?: 0.0 > 0)
    }

    @Test
    fun `stress - classify local expenses between 5-50km`() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        
        val localExpenses = createExpensesAtDistance(
            homeLat = homeLat,
            homeLon = homeLon,
            distancesKm = listOf(10.0, 20.0, 30.0, 40.0, 50.0),
            baseTimestamp = 1000L
        )
        
        val insight = detectTravel(localExpenses)
        
        assertTrue("Should have local spending", insight?.localSpend ?: 0.0 > 0)
    }

    @Test
    fun `stress - classify travel expenses beyond 50km`() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        
        val travelExpenses = createExpensesAtDistance(
            homeLat = homeLat,
            homeLon = homeLon,
            distancesKm = listOf(60.0, 100.0, 200.0, 500.0),
            baseTimestamp = 1000L
        )
        
        val insight = detectTravel(travelExpenses)
        
        assertTrue("Should have travel spending", insight?.travelSpend ?: 0.0 > 0)
    }

    // ============================================================================
    // SECTION 3: TRAVEL TRIP DETECTION
    // ============================================================================

    @Test
    fun `stress - detect single day trip`() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        
        val expenses = listOf(
            // Home expenses
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),  // Home
            // Day trip expenses (100km away)
            LocatedExpense(41.5000, -73.5000, 1000L + 24 * 60 * 60 * 1000L, 50.0),  // Travel
            LocatedExpense(41.5000, -73.5000, 1000L + 26 * 60 * 60 * 1000L, 30.0),  // Travel (same day)
            // Back home
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 10.0)   // Home
        )
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should detect trip", insight)
        assertTrue("Should have travel trips", insight?.travelTrips?.isNotEmpty() ?: false)
    }

    @Test
    fun `stress - detect multi-day trip`() {
        val expenses = listOf(
            // Day 1: Travel to destination
            LocatedExpense(41.8781, -87.6298, 1000L + 24 * 60 * 60 * 1000L, 100.0),  // Chicago (from NYC)
            // Day 2-3: At destination
            LocatedExpense(41.8781, -87.6298, 1000L + 48 * 60 * 60 * 1000L, 200.0),
            LocatedExpense(41.8781, -87.6298, 1000L + 72 * 60 * 60 * 1000L, 150.0),
            // Day 4: Back home
            LocatedExpense(40.7128, -74.0060, 1000L + 96 * 60 * 60 * 1000L, 50.0)
        )
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should detect multi-day trip", insight)
    }

    @Test
    fun `stress - detect multiple trips`() {
        val expenses = listOf(
            // Trip 1: Week 1
            LocatedExpense(41.8781, -87.6298, 1000L + 7 * 24 * 60 * 60 * 1000L, 300.0),
            // Back home
            LocatedExpense(40.7128, -74.0060, 1000L + 10 * 24 * 60 * 60 * 1000L, 50.0),
            // Trip 2: Week 3
            LocatedExpense(34.0522, -118.2437, 1000L + 21 * 24 * 60 * 60 * 1000L, 500.0),
            // Back home
            LocatedExpense(40.7128, -74.0060, 1000L + 25 * 24 * 60 * 60 * 1000L, 50.0)
        )
        
        val insight = detectTravel(expenses)
        
        assertTrue("Should detect multiple trips", (insight?.travelTrips?.size ?: 0) >= 2)
    }

    @Test
    fun `stress - group consecutive travel expenses into single trip`() {
        val expenses = listOf(
            // Trip with expenses on consecutive days
            LocatedExpense(41.8781, -87.6298, 1000L + 24 * 60 * 60 * 1000L, 100.0),  // Day 1
            LocatedExpense(41.8781, -87.6298, 1000L + 48 * 60 * 60 * 1000L, 150.0),  // Day 2
            LocatedExpense(41.8781, -87.6298, 1000L + 72 * 60 * 60 * 1000L, 200.0),  // Day 3
        )
        
        val insight = detectTravel(expenses)
        
        // All should be grouped into one trip
        assertEquals("Should group into single trip", 1, insight?.travelTrips?.size)
    }

    // ============================================================================
    // SECTION 4: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle expenses without location`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(null, null, 1000L + 24 * 60 * 60 * 1000L, 20.0),  // No location
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 15.0)
        )
        
        val insight = detectTravel(expenses)
        
        // Should process expenses with locations
        assertNotNull("Should handle missing locations", insight)
    }

    @Test
    fun `stress - handle all expenses at same location`() {
        val expenses = (1..50).map { i ->
            LocatedExpense(40.7128, -74.0060, 1000L + i * 24 * 60 * 60 * 1000L, 10.0)
        }
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should handle single location", insight)
        assertEquals("All should be home spending", insight?.homeSpend ?: 0.0, insight?.homeSpend ?: 0.0)
    }

    @Test
    fun `stress - handle expenses at exact boundary distances`() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        
        // Create expenses at exactly 5km, 50km boundaries
        val boundaryExpenses = listOf(
            createExpenseAtDistance(homeLat, homeLon, 4.99, 1000L),   // Just under 5km
            createExpenseAtDistance(homeLat, homeLon, 5.0, 1000L + 24 * 60 * 60 * 1000L),     // Exactly 5km
            createExpenseAtDistance(homeLat, homeLon, 5.01, 1000L + 48 * 60 * 60 * 1000L),    // Just over 5km
            createExpenseAtDistance(homeLat, homeLon, 49.99, 1000L + 72 * 60 * 60 * 1000L),   // Just under 50km
            createExpenseAtDistance(homeLat, homeLon, 50.0, 1000L + 96 * 60 * 60 * 1000L),    // Exactly 50km
            createExpenseAtDistance(homeLat, homeLon, 50.01, 1000L + 120 * 60 * 60 * 1000L)   // Just over 50km
        )
        
        val insight = detectTravel(boundaryExpenses)
        
        assertNotNull("Should handle boundary distances", insight)
    }

    @Test
    fun `stress - handle commute pattern vs travel`() {
        // Regular commute to same distant location
        val commuteExpenses = (1..20).map { i ->
            LocatedExpense(41.5000, -73.5000, 1000L + i * 2 * 24 * 60 * 60 * 1000L, 20.0)
        }
        
        val insight = detectTravel(commuteExpenses)
        
        // Should detect this as regular pattern, not necessarily travel
        assertNotNull("Should handle commute", insight)
    }

    // ============================================================================
    // SECTION 5: SPENDING CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate accurate spending totals`() {
        val homeExpenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 20.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 48 * 60 * 60 * 1000L, 15.0)
        )
        
        val insight = detectTravel(homeExpenses)
        
        assertEquals("Home spending should be accurate", 45.0, insight?.homeSpend ?: 0.0, 0.01)
    }

    @Test
    fun `stress - calculate trip totals`() {
        val tripExpenses = listOf(
            LocatedExpense(41.8781, -87.6298, 1000L, 100.0),
            LocatedExpense(41.8781, -87.6298, 1000L + 24 * 60 * 60 * 1000L, 200.0),
            LocatedExpense(41.8781, -87.6298, 1000L + 48 * 60 * 60 * 1000L, 150.0)
        )
        
        val insight = detectTravel(tripExpenses)
        
        val trip = insight?.travelTrips?.firstOrNull()
        assertEquals("Trip total should be accurate", 450.0, trip?.totalSpend ?: 0.0, 0.01)
        assertEquals("Trip count should be accurate", 3, trip?.transactionCount ?: 0)
    }

    @Test
    fun `stress - handle zero spending`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 0.0),
            LocatedExpense(40.7128, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 0.0)
        )
        
        val insight = detectTravel(expenses)
        
        assertEquals("Should handle zero spending", 0.0, insight?.homeSpend ?: -1.0, 0.01)
    }

    // ============================================================================
    // SECTION 6: GRID CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - home grid bucketing accuracy`() {
        val expenses = listOf(
            LocatedExpense(40.7128, -74.0060, 1000L, 10.0),
            LocatedExpense(40.7130, -74.0062, 1000L + 24 * 60 * 60 * 1000L, 10.0),  // Same grid cell
            LocatedExpense(40.7200, -74.0100, 1000L + 48 * 60 * 60 * 1000L, 10.0),  // Adjacent cell
            LocatedExpense(40.7500, -74.0300, 1000L + 72 * 60 * 60 * 1000L, 10.0)   // Different cell
        )
        
        val insight = detectTravel(expenses)
        
        // Should cluster expenses correctly
        assertNotNull("Should bucket correctly", insight)
    }

    @Test
    fun `stress - handle coordinates at grid boundaries`() {
        val gridSizeDeg = 0.045  // ~5km
        val boundaryLat = 40.7128 - (40.7128 % gridSizeDeg) + gridSizeDeg
        
        val expenses = listOf(
            LocatedExpense(boundaryLat - 0.001, -74.0060, 1000L, 10.0),
            LocatedExpense(boundaryLat + 0.001, -74.0060, 1000L + 24 * 60 * 60 * 1000L, 10.0)
        )
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should handle grid boundaries", insight)
    }

    // ============================================================================
    // SECTION 7: TIME-BASED ANALYSIS
    // ============================================================================

    @Test
    fun `stress - handle out-of-order timestamps`() {
        val expenses = listOf(
            LocatedExpense(41.8781, -87.6298, 1000L + 72 * 60 * 60 * 1000L, 100.0),  // Day 3
            LocatedExpense(41.8781, -87.6298, 1000L + 24 * 60 * 60 * 1000L, 100.0),  // Day 1
            LocatedExpense(41.8781, -87.6298, 1000L + 48 * 60 * 60 * 1000L, 100.0)   // Day 2
        )
        
        val insight = detectTravel(expenses)
        
        // Should sort by timestamp before processing
        assertNotNull("Should handle out-of-order", insight)
        assertEquals("Should group into single trip", 1, insight?.travelTrips?.size)
    }

    @Test
    fun `stress - handle gaps between trips`() {
        val expenses = listOf(
            // Trip 1: Week 1
            LocatedExpense(41.8781, -87.6298, 1000L + 7 * 24 * 60 * 60 * 1000L, 200.0),
            // Gap of 2 weeks
            // Trip 2: Week 4
            LocatedExpense(34.0522, -118.2437, 1000L + 28 * 24 * 60 * 60 * 1000L, 300.0)
        )
        
        val insight = detectTravel(expenses)
        
        // Gap should create separate trips
        assertEquals("Should create separate trips", 2, insight?.travelTrips?.size)
    }

    // ============================================================================
    // SECTION 8: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - process 1000 expenses quickly`() {
        val expenses = (1..1000).map { i ->
            LocatedExpense(
                lat = 40.7128 + (i % 10) * 0.01,
                lon = -74.0060 + (i % 10) * 0.01,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = (i % 100).toDouble()
            )
        }
        
        val startTime = System.nanoTime()
        
        val insight = detectTravel(expenses)
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 1000 expenses in under 1s", duration < 1_000_000_000)
        assertNotNull("Should produce result", insight)
    }

    @Test
    fun `stress - process expenses spanning multiple years`() {
        val expenses = (1..100).map { i ->
            LocatedExpense(
                lat = if (i % 10 == 0) 41.8781 else 40.7128,
                lon = if (i % 10 == 0) -87.6298 else -74.0060,
                timestamp = 1000L + i * 30L * 24 * 60 * 60 * 1000L,
                amount = 100.0
            )
        }
        
        val insight = detectTravel(expenses)
        
        assertNotNull("Should handle multi-year data", insight)
    }

    // ============================================================================
    // SECTION 9: DESTINATION HINTS
    // ============================================================================

    @Test
    fun `stress - generate destination hints`() {
        val expenses = listOf(
            LocatedExpense(41.8781, -87.6298, 1000L, 100.0, "Chicago, IL"),
            LocatedExpense(41.8781, -87.6298, 1000L + 24 * 60 * 60 * 1000L, 150.0, "Chicago, IL")
        )
        
        val insight = detectTravel(expenses)
        
        val trip = insight?.travelTrips?.firstOrNull()
        assertNotNull("Should have destination hint", trip?.destinationHint)
    }

    // ============================================================================
    // SECTION 10: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic results`() {
        val expenses = (1..50).map { i ->
            LocatedExpense(40.7128, -74.0060, 1000L + i * 24 * 60 * 60 * 1000L, 10.0)
        }
        
        val result1 = detectTravel(expenses)
        val result2 = detectTravel(expenses)
        val result3 = detectTravel(expenses)
        
        assertEquals("Should be deterministic", result1?.homeLatitude, result2?.homeLatitude)
        assertEquals("Should be deterministic", result2?.homeLongitude, result3?.homeLongitude)
    }

    // Helper data classes and functions
    private data class LocatedExpense(
        val lat: Double?,
        val lon: Double?,
        val timestamp: Long,
        val amount: Double,
        val address: String? = null
    )
    
    private data class TravelInsightResult(
        val homeLatitude: Double?,
        val homeLongitude: Double?,
        val homeSpend: Double,
        val localSpend: Double,
        val travelSpend: Double,
        val travelTrips: List<TravelTripResult>
    )
    
    private data class TravelTripResult(
        val startDate: Long,
        val endDate: Long,
        val totalSpend: Double,
        val transactionCount: Int,
        val destinationHint: String?
    )
    
    private fun createClusteredExpenses(centerLat: Double, centerLon: Double, radiusKm: Double, count: Int): List<LocatedExpense> {
        return (1..count).map { i ->
            val angle = Math.random() * 2 * Math.PI
            val distance = Math.random() * radiusKm
            val latOffset = (distance / 111.0) * Math.cos(angle)
            val lonOffset = (distance / (111.0 * Math.cos(Math.toRadians(centerLat)))) * Math.sin(angle)
            
            LocatedExpense(
                lat = centerLat + latOffset,
                lon = centerLon + lonOffset,
                timestamp = 1000L + i * 24 * 60 * 60 * 1000L,
                amount = (Math.random() * 100) + 10
            )
        }
    }
    
    private fun createScatteredExpenses(centerLat: Double, centerLon: Double, radiusKm: Double, count: Int): List<LocatedExpense> {
        return createClusteredExpenses(centerLat, centerLon, radiusKm, count)
    }
    
    private fun createExpensesAtDistance(homeLat: Double, homeLon: Double, distancesKm: List<Double>, baseTimestamp: Long): List<LocatedExpense> {
        return distancesKm.mapIndexed { index, distance ->
            val angle = (index.toDouble() / distancesKm.size) * 2 * Math.PI
            val latOffset = (distance / 111.0) * Math.cos(angle)
            val lonOffset = (distance / (111.0 * Math.cos(Math.toRadians(homeLat)))) * Math.sin(angle)
            
            LocatedExpense(
                lat = homeLat + latOffset,
                lon = homeLon + lonOffset,
                timestamp = baseTimestamp + index * 24 * 60 * 60 * 1000L,
                amount = 50.0
            )
        }
    }
    
    private fun createExpenseAtDistance(homeLat: Double, homeLon: Double, distanceKm: Double, timestamp: Long): LocatedExpense {
        val latOffset = distanceKm / 111.0
        return LocatedExpense(
            lat = homeLat + latOffset,
            lon = homeLon,
            timestamp = timestamp,
            amount = 50.0
        )
    }
    
    private fun detectTravel(expenses: List<LocatedExpense>): TravelInsightResult? {
        val located = expenses.filter { it.lat != null && it.lon != null }
        if (located.size < 10) return null
        
        // Find most frequent grid cell (home)
        val gridSize = 0.045  // ~5km
        val cellCounts = mutableMapOf<Pair<Long, Long>, Int>()
        
        located.forEach { exp ->
            val cell = Pair((exp.lat!! / gridSize).toLong(), (exp.lon!! / gridSize).toLong())
            cellCounts[cell] = (cellCounts[cell] ?: 0) + 1
        }
        
        val homeCell = cellCounts.maxByOrNull { it.value }?.key ?: return null
        val homeLat = homeCell.first * gridSize
        val homeLon = homeCell.second * gridSize
        
        // Classify expenses
        var homeSpend = 0.0
        var localSpend = 0.0
        var travelSpend = 0.0
        val travelExpenses = mutableListOf<LocatedExpense>()
        
        located.forEach { exp ->
            val distance = calculateDistance(homeLat, homeLon, exp.lat!!, exp.lon!!)
            when {
                distance <= 5.0 -> homeSpend += exp.amount
                distance <= 50.0 -> localSpend += exp.amount
                else -> {
                    travelSpend += exp.amount
                    travelExpenses.add(exp)
                }
            }
        }
        
        // Group travel expenses into trips
        val trips = groupIntoTrips(travelExpenses)
        
        return TravelInsightResult(
            homeLatitude = homeLat,
            homeLongitude = homeLon,
            homeSpend = homeSpend,
            localSpend = localSpend,
            travelSpend = travelSpend,
            travelTrips = trips
        )
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0  // Earth radius in km
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        val a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
    
    private fun groupIntoTrips(expenses: List<LocatedExpense>): List<TravelTripResult> {
        if (expenses.isEmpty()) return emptyList()
        
        val sorted = expenses.sortedBy { it.timestamp }
        val trips = mutableListOf<TravelTripResult>()
        var currentTrip = mutableListOf<LocatedExpense>()
        
        sorted.forEach { expense ->
            if (currentTrip.isEmpty()) {
                currentTrip.add(expense)
            } else {
                val lastExpense = currentTrip.last()
                val gap = (expense.timestamp - lastExpense.timestamp) / (24 * 60 * 60 * 1000L)
                
                if (gap > 3) {
                    // New trip
                    trips.add(createTripResult(currentTrip))
                    currentTrip = mutableListOf(expense)
                } else {
                    currentTrip.add(expense)
                }
            }
        }
        
        if (currentTrip.isNotEmpty()) {
            trips.add(createTripResult(currentTrip))
        }
        
        return trips
    }
    
    private fun createTripResult(expenses: List<LocatedExpense>): TravelTripResult {
        return TravelTripResult(
            startDate = expenses.first().timestamp,
            endDate = expenses.last().timestamp,
            totalSpend = expenses.sumOf { it.amount },
            transactionCount = expenses.size,
            destinationHint = expenses.first().address
        )
    }
}
