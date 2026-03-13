package com.yourname.expensetracker.data.location

import com.yourname.expensetracker.domain.location.GeocodingResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Stress tests for CompositeGeocodingService
 * 
 * Tests multi-provider geocoding, result merging, ranking, deduplication,
 * and parallel execution patterns.
 */
class CompositeGeocodingServiceStressTest {

    private lateinit var photonService: PhotonGeocodingService
    private lateinit var geoapifyService: GeoapifyGeocodingService
    private lateinit var googlePlacesService: GooglePlacesGeocodingService
    private lateinit var nominatimService: NominatimGeocodingService
    private lateinit var compositeService: CompositeGeocodingService

    @Before
    fun setup() {
        photonService = mockk(relaxed = true)
        geoapifyService = mockk(relaxed = true)
        googlePlacesService = mockk(relaxed = true)
        nominatimService = mockk(relaxed = true)
        
        compositeService = CompositeGeocodingService(
            photon = photonService,
            geoapify = geoapifyService,
            googlePlaces = googlePlacesService,
            nominatim = nominatimService
        )
    }

    // ============================================================================
    // SECTION 1: SINGLE SEARCH DELEGATION
    // ============================================================================

    @Test
    fun `stress - search delegates to nominatim`() = runBlocking {
        val expectedResult = createGeocodingResult("N123", 40.7128, -74.0060, "Test Address")
        coEvery { 
            nominatimService.search(any(), any(), any(), any(), any()) 
        } returns expectedResult

        val result = compositeService.search("Test Merchant", 40.7, -74.0)

        assertNotNull(result)
        assertEquals(expectedResult.osmId, result?.osmId)
        coVerify { nominatimService.search("Test Merchant", 40.7, -74.0, null, false) }
    }

    @Test
    fun `stress - reverse geocode delegates to nominatim`() = runBlocking {
        val expectedResult = createGeocodingResult("N456", 40.7128, -74.0060, "NYC")
        coEvery { 
            nominatimService.reverseGeocode(40.7128, -74.0060) 
        } returns expectedResult

        val result = compositeService.reverseGeocode(40.7128, -74.0060)

        assertNotNull(result)
        assertEquals(expectedResult.osmId, result?.osmId)
        coVerify { nominatimService.reverseGeocode(40.7128, -74.0060) }
    }

    @Test
    fun `stress - search handles null result from nominatim`() = runBlocking {
        coEvery { 
            nominatimService.search(any(), any(), any(), any(), any()) 
        } returns null

        val result = compositeService.search("Unknown Merchant", null, null)

        assertNull(result)
    }

    // ============================================================================
    // SECTION 2: MULTI-SEARCH WITH SIMPLE QUERIES
    // ============================================================================

    @Test
    fun `stress - simple query uses only photon and nominatim`() = runBlocking {
        val photonResults = listOf(createGeocodingResult("P1", 40.71, -74.01, "Address 1"))
        coEvery { 
            photonService.searchMultiple("LIDL", null, null, 5) 
        } returns photonResults
        coEvery { 
            nominatimService.searchMultiple("LIDL", null, null, 5) 
        } returns emptyList()

        val results = compositeService.searchMultiple("LIDL", null, null, 5, false)

        assertTrue(results.isNotEmpty())
        coVerify { photonService.searchMultiple("LIDL", null, null, 5) }
        coVerify { nominatimService.searchMultiple("LIDL", null, null, 5) }
        coVerify(exactly = 0) { geoapifyService.searchMultiple(any(), any(), any(), any()) }
        coVerify(exactly = 0) { googlePlacesService.searchMultiple(any(), any(), any(), any()) }
    }

    @Test
    fun `stress - complex query uses all providers when google enabled`() = runBlocking {
        val photonResults = listOf(createGeocodingResult("P1", 40.71, -74.01, "Yes Monastiriou"))
        val geoapifyResults = listOf(createGeocodingResult("G1", 40.72, -74.02, "Yes Egnatia"))
        val googleResults = listOf(createGeocodingResult("GP1", 40.73, -74.03, "Yes Tsimiski"))
        val nominatimResults = listOf(createGeocodingResult("N1", 40.74, -74.04, "Yes Venizelou"))

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns photonResults
        coEvery { 
            geoapifyService.searchMultiple(any(), any(), any(), any()) 
        } returns geoapifyResults
        coEvery { 
            googlePlacesService.searchMultiple(any(), any(), any(), any()) 
        } returns googleResults
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns nominatimResults

        val results = compositeService.searchMultiple("YES Stores Monastiriou", null, null, 5, true)

        assertTrue(results.size >= 4)
        coVerify { geoapifyService.searchMultiple(any(), any(), any(), any()) }
        coVerify { googlePlacesService.searchMultiple(any(), any(), any(), any()) }
    }

    @Test
    fun `stress - complex query skips google when disabled`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            geoapifyService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple("YES Stores Monastiriou", null, null, 5, false)

        coVerify { geoapifyService.searchMultiple(any(), any(), any(), any()) }
        coVerify(exactly = 0) { googlePlacesService.searchMultiple(any(), any(), any(), any()) }
    }

    // ============================================================================
    // SECTION 3: RESULT MERGING
    // ============================================================================

    @Test
    fun `stress - merge results from multiple providers`() = runBlocking {
        val photonResults = listOf(
            createGeocodingResult("P1", 40.71, -74.01, "Starbucks 5th Ave", confidence = 0.9f),
            createGeocodingResult("P2", 40.72, -74.02, "Starbucks Broadway", confidence = 0.8f)
        )
        val nominatimResults = listOf(
            createGeocodingResult("N1", 40.73, -74.03, "Starbucks Wall St", confidence = 0.85f),
            createGeocodingResult("N2", 40.74, -74.04, "Starbucks Madison", confidence = 0.75f)
        )

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns photonResults
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns nominatimResults

        val results = compositeService.searchMultiple("Starbucks", null, null, 10, false)

        assertEquals(4, results.size)
    }

    @Test
    fun `stress - handle empty results from all providers`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val results = compositeService.searchMultiple("NonExistentPlace12345", null, null, 5, false)

        assertTrue(results.isEmpty())
    }

    // ============================================================================
    // SECTION 4: RESULT RANKING BY QUALIFIER MATCH
    // ============================================================================

    @Test
    fun `stress - rank results with qualifier matches higher`() = runBlocking {
        val results = listOf(
            createGeocodingResult("N1", 40.71, -74.01, "Starbucks Egnatia 123, Athens", confidence = 0.9f),
            createGeocodingResult("N2", 40.72, -74.02, "Starbucks Monastiriou 456, Athens", confidence = 0.7f),
            createGeocodingResult("N3", 40.73, -74.03, "Starbucks Tsimiski 789, Athens", confidence = 0.8f)
        )

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val ranked = compositeService.searchMultiple("Starbucks Monastiriou", null, null, 10, false)

        assertTrue("Qualifier match should rank higher", 
            ranked[0].displayAddress?.contains("Monastiriou") == true)
    }

    @Test
    fun `stress - handle accent-insensitive qualifier matching`() = runBlocking {
        val results = listOf(
            createGeocodingResult("N1", 40.71, -74.01, "Starbucks Μοναστηρίου, Athens", confidence = 0.8f),
            createGeocodingResult("N2", 40.72, -74.02, "Starbucks Εγνατία, Athens", confidence = 0.9f)
        )

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val ranked = compositeService.searchMultiple("Starbucks Monastiriou", null, null, 10, false)

        assertTrue("Should match without accent",
            ranked[0].displayAddress?.contains("Μοναστηρίου") == true)
    }

    // ============================================================================
    // SECTION 5: DEDUPLICATION
    // ============================================================================

    @Test
    fun `stress - deduplicate results within 50 meters`() = runBlocking {
        val photonResult = createGeocodingResult("P1", 40.712800, -74.006000, "Starbucks via Photon", confidence = 0.9f)
        val nominatimResult = createGeocodingResult("N1", 40.712805, -74.006005, "Starbucks via Nominatim", confidence = 0.8f)

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(photonResult)
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(nominatimResult)

        val deduped = compositeService.searchMultiple("Starbucks", null, null, 10, false)

        assertEquals("Should deduplicate close results", 1, deduped.size)
    }

    @Test
    fun `stress - keep distinct results beyond 50 meters`() = runBlocking {
        val photonResult = createGeocodingResult("P1", 40.7128, -74.0060, "Starbucks Manhattan", confidence = 0.9f)
        val nominatimResult = createGeocodingResult("N1", 40.7589, -73.9851, "Starbucks Midtown", confidence = 0.8f)

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(photonResult)
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(nominatimResult)

        val kept = compositeService.searchMultiple("Starbucks", null, null, 10, false)

        assertEquals("Should keep distinct results", 2, kept.size)
    }

    @Test
    fun `stress - deduplication keeps higher ranked result`() = runBlocking {
        val photonResult = createGeocodingResult("P1", 40.712800, -74.006000, "Starbucks", confidence = 0.95f)
        val nominatimResult = createGeocodingResult("N1", 40.712801, -74.006001, "Starbucks", confidence = 0.75f)

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(photonResult)
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(nominatimResult)

        val deduped = compositeService.searchMultiple("Starbucks", null, null, 10, false)

        assertEquals(1, deduped.size)
        assertEquals("Should keep higher confidence result", 0.95f, deduped[0].confidence, 0.01f)
    }

    // ============================================================================
    // SECTION 6: QUERY COMPLEXITY DETECTION
    // ============================================================================

    @Test
    fun `stress - single word query is not complex`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple("LIDL", null, null, 5, false)

        coVerify(exactly = 0) { geoapifyService.searchMultiple(any(), any(), any(), any()) }
    }

    @Test
    fun `stress - two word query is complex`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            geoapifyService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple("Starbucks NYC", null, null, 5, false)

        coVerify { geoapifyService.searchMultiple(any(), any(), any(), any()) }
    }

    // ============================================================================
    // SECTION 7: LIMIT HANDLING
    // ============================================================================

    @Test
    fun `stress - respect result limit`() = runBlocking {
        val manyResults = (1..50).map { i ->
            createGeocodingResult("N$i", 40.71 + i * 0.001, -74.01 + i * 0.001, "Result $i")
        }

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns manyResults
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val results = compositeService.searchMultiple("Test", null, null, 10, false)

        assertTrue("Should respect limit", results.size <= 10)
    }

    @Test
    fun `stress - minimum limit of 10 enforced`() = runBlocking {
        val manyResults = (1..50).map { i ->
            createGeocodingResult("N$i", 40.71 + i * 0.001, -74.01 + i * 0.001, "Result $i")
        }

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns manyResults
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val results = compositeService.searchMultiple("Test", null, null, 5, false)

        assertTrue("Should enforce minimum of 10", results.size >= 10)
    }

    // ============================================================================
    // SECTION 8: ERROR HANDLING
    // ============================================================================

    @Test
    fun `stress - handle provider exceptions gracefully`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } throws RuntimeException("Photon failed")
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns listOf(createGeocodingResult("N1", 40.71, -74.01, "Nominatim Result"))

        val results = compositeService.searchMultiple("Test", null, null, 5, false)

        assertEquals("Should continue with working providers", 1, results.size)
    }

    @Test
    fun `stress - handle all providers failing`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } throws RuntimeException("Photon failed")
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } throws RuntimeException("Nominatim failed")

        val results = compositeService.searchMultiple("Test", null, null, 5, false)

        assertTrue("Should return empty when all fail", results.isEmpty())
    }

    @Test
    fun `stress - handle cancellation exception propagation`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } throws kotlinx.coroutines.CancellationException("Cancelled")

        var exceptionThrown = false
        try {
            compositeService.searchMultiple("Test", null, null, 5, false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            exceptionThrown = true
        }

        assertTrue("Should propagate cancellation", exceptionThrown)
    }

    // ============================================================================
    // SECTION 9: BIAS LAT/LON HANDLING
    // ============================================================================

    @Test
    fun `stress - pass bias coordinates to providers`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple("Starbucks", 40.7128, -74.0060, 5, false)

        coVerify { photonService.searchMultiple("Starbucks", 40.7128, -74.0060, 5) }
        coVerify { nominatimService.searchMultiple("Starbucks", 40.7128, -74.0060, 5) }
    }

    @Test
    fun `stress - handle null bias coordinates`() = runBlocking {
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple("Starbucks", null, null, 5, false)

        coVerify { photonService.searchMultiple("Starbucks", null, null, 5) }
        coVerify { nominatimService.searchMultiple("Starbucks", null, null, 5) }
    }

    // ============================================================================
    // SECTION 10: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle empty query`() = runBlocking {
        coEvery { 
            photonService.searchMultiple("", any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple("", any(), any(), any()) 
        } returns emptyList()

        val results = compositeService.searchMultiple("", null, null, 5, false)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `stress - handle very long query`() = runBlocking {
        val longQuery = "A".repeat(1000)
        coEvery { 
            photonService.searchMultiple(longQuery, any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(longQuery, any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple(longQuery, null, null, 5, false)

        coVerify { photonService.searchMultiple(longQuery, null, null, 5) }
    }

    @Test
    fun `stress - handle query with special characters`() = runBlocking {
        val specialQuery = "Store @ 5th & Main! (Test)"
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple(specialQuery, null, null, 5, false)

        coVerify { photonService.searchMultiple(specialQuery, null, null, 5) }
    }

    @Test
    fun `stress - handle unicode characters in query`() = runBlocking {
        val unicodeQuery = "Μασούτης Μοναστηρίου Αθήνα"
        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        compositeService.searchMultiple(unicodeQuery, null, null, 5, false)

        coVerify { photonService.searchMultiple(unicodeQuery, null, null, 5) }
    }

    @Test
    fun `stress - handle results with null display address`() = runBlocking {
        val results = listOf(
            createGeocodingResult("N1", 40.71, -74.01, displayAddress = null, confidence = 0.9f),
            createGeocodingResult("N2", 40.72, -74.02, "Valid Address", confidence = 0.8f)
        )

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val ranked = compositeService.searchMultiple("Test", null, null, 10, false)

        assertEquals(2, ranked.size)
    }

    @Test
    fun `stress - handle negative coordinates`() = runBlocking {
        val result = createGeocodingResult("N1", -33.8688, 151.2093, "Sydney, Australia")

        coEvery { 
            nominatimService.search("Test", any(), any(), any(), any()) 
        } returns result

        val resolved = compositeService.search("Test", -33.8688, 151.2093)

        assertNotNull(resolved)
        assertEquals(-33.8688, resolved?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun `stress - handle extreme coordinates`() = runBlocking {
        val results = listOf(
            createGeocodingResult("N1", 89.9, 179.9, "Near North Pole"),
            createGeocodingResult("N2", -89.9, -179.9, "Near South Pole")
        )

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns emptyList()

        val merged = compositeService.searchMultiple("Test", null, null, 10, false)

        assertEquals(2, merged.size)
    }

    // ============================================================================
    // SECTION 11: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - parallel execution completes quickly`() = runBlocking {
        val results = (1..20).map { i ->
            createGeocodingResult("N$i", 40.71 + i * 0.001, -74.01 + i * 0.001, "Result $i")
        }

        coEvery { 
            photonService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            geoapifyService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            googlePlacesService.searchMultiple(any(), any(), any(), any()) 
        } returns results
        coEvery { 
            nominatimService.searchMultiple(any(), any(), any(), any()) 
        } returns results

        val startTime = System.nanoTime()
        compositeService.searchMultiple("YES Stores Monastiriou", null, null, 10, true)
        val duration = System.nanoTime() - startTime

        assertTrue("Parallel execution should be fast", duration < 1_000_000_000)
    }

    // Helper functions
    private fun createGeocodingResult(
        osmId: String,
        lat: Double,
        lon: Double,
        displayAddress: String?,
        confidence: Float = 0.8f,
        source: String = "nominatim"
    ): GeocodingResult {
        return GeocodingResult(
            latitude = lat,
            longitude = lon,
            osmId = osmId,
            name = "Test Name",
            displayAddress = displayAddress,
            confidence = confidence,
            source = source
        )
    }
}
