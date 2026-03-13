package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.dao.LocationCluster
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.categorization.CanonicalResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Stress tests for LocationResolver
 * 
 * Tests the complex location resolution pipeline including:
 * - User correction lookup
 * - Merchant cache hits
 * - GPS-biased Nominatim search
 * - Overpass nearby POI search
 * - Rate limiting
 * - Multiple name variants (cleaned, canonical, greeklish)
 */
class LocationResolverStressTest {

    private lateinit var geocodingService: GeocodingService
    private lateinit var nearbyPoiService: NearbyPoiService
    private lateinit var locationProvider: ForegroundLocationProvider
    private lateinit var locationRepository: MerchantLocationRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var canonicalizer: MerchantCanonicalizer
    private lateinit var greeklishNormalizer: GreeklishNormalizer
    private lateinit var locationResolver: LocationResolver

    @Before
    fun setup() {
        geocodingService = mockk(relaxed = true)
        nearbyPoiService = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        locationRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        merchantCleaner = mockk(relaxed = true)
        canonicalizer = mockk(relaxed = true)
        greeklishNormalizer = mockk(relaxed = true)
        
        // Default mock behaviors
        every { merchantCleaner.clean(any()) } answers { firstArg() }
        every { canonicalizer.canonicalize(any()) } answers { 
            CanonicalResult(firstArg<String>(), emptyList(), 0.0) 
        }
        every { greeklishNormalizer.normalize(any()) } answers { firstArg() }
        coEvery { locationProvider.getLastKnownLocation() } returns null
        coEvery { locationRepository.getCorrection(any(), any(), any()) } returns null
        coEvery { locationRepository.getCachedLocation(any()) } returns null
        coEvery { expenseRepository.getMerchantLocationClusters(any()) } returns emptyList()
        
        locationResolver = LocationResolver(
            geocodingService = geocodingService,
            nearbyPoiService = nearbyPoiService,
            locationProvider = locationProvider,
            locationRepository = locationRepository,
            expenseRepository = expenseRepository,
            merchantCleaner = merchantCleaner,
            canonicalizer = canonicalizer,
            greeklishNormalizer = greeklishNormalizer
        )
    }

    // ============================================================================
    // SECTION 1: USER CORRECTION PRIORITY
    // ============================================================================

    @Test
    fun `stress - user correction takes highest priority`() = runBlocking {
        val correction = createCorrection(40.7128, -74.0060, "User Corrected Address")
        coEvery { locationRepository.getCorrection(any(), any(), any()) } returns correction

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue("Should return Resolved", result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertEquals(40.7128, resolved.latitude, 0.0001)
        assertEquals("user_manual", resolved.source)
        assertEquals(1.0f, resolved.confidence, 0.01f)
    }

    @Test
    fun `stress - user correction with device location bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        val correction = createCorrection(40.7128, -74.0060, "Near User")
        coEvery { locationRepository.getCorrection(eq("starbucks"), eq(40.71), eq(-74.01)) } returns correction

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - no user correction falls through to cache`() = runBlocking {
        coEvery { locationRepository.getCorrection(any(), any(), any()) } returns null
        val cached = createCachedLocation(40.7128, -74.0060, "cached")
        coEvery { locationRepository.getCachedLocation("starbucks") } returns cached

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertEquals("cached", resolved.source)
    }

    // ============================================================================
    // SECTION 2: CACHE HITS
    // ============================================================================

    @Test
    fun `stress - cache hit returns immediately without geocoding`() = runBlocking {
        coEvery { locationRepository.getCachedLocation("masoutis") } returns createCachedLocation(40.7128, -74.0060, "nominatim")

        val result = locationResolver.resolve("Masoutis", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(exactly = 0) { geocodingService.search(any(), any(), any()) }
    }

    @Test
    fun `stress - forceRefresh bypasses cache`() = runBlocking {
        coEvery { locationRepository.getCachedLocation("masoutis") } returns createCachedLocation(40.7128, -74.0060, "nominatim")
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.72, -74.02)

        val result = locationResolver.resolve("Masoutis", System.currentTimeMillis(), forceRefresh = true)

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stress - area-scoped cache lookup`() = runBlocking {
        val cluster = LocationCluster(40.71, -74.01, count = 5)
        coEvery { expenseRepository.getMerchantLocationClusters("starbucks") } returns listOf(cluster)
        coEvery { locationRepository.getMostLikelyArea(any(), any(), any()) } returns "downtown"
        coEvery { locationRepository.getCachedLocationForArea("starbucks", "downtown") } returns createCachedLocation(40.7128, -74.0060, "area_cache")

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    // ============================================================================
    // SECTION 3: HISTORY-BIASED LOOKUP
    // ============================================================================

    @Test
    fun `stress - history-biased lookup with cluster data`() = runBlocking {
        val cluster = LocationCluster(40.71, -74.01, count = 3)
        coEvery { expenseRepository.getMerchantLocationClusters("masoutis") } returns listOf(cluster)
        coEvery { locationRepository.getMostLikelyArea(any(), any(), any()) } returns "kolonaki"
        coEvery { geocodingService.search(any(), eq(40.71), eq(-74.01), any(), eq(true)) } returns createGeocodingResult(40.7128, -74.0060)

        val result = locationResolver.resolve("Masoutis", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify { geocodingService.search(any(), eq(40.71), eq(-74.01), any(), eq(true)) }
    }

    @Test
    fun `stress - fallback from canonical to cleaned name on geocode failure`() = runBlocking {
        val cluster = LocationCluster(40.71, -74.01, count = 3)
        coEvery { expenseRepository.getMerchantLocationClusters(any()) } returns listOf(cluster)
        coEvery { locationRepository.getMostLikelyArea(any(), any(), any()) } returns "area1"
        
        // First call with latin name fails
        coEvery { geocodingService.search(eq("masoutis"), any(), any(), any(), eq(true)) } returns null
        // Second call with cleaned name succeeds
        coEvery { geocodingService.search(eq("Μασούτης"), any(), any(), any(), eq(true)) } returns createGeocodingResult(40.7128, -74.0060)

        every { canonicalizer.canonicalize("Μασούτης") } returns CanonicalResult("masoutis", emptyList(), 0.0)
        every { greeklishNormalizer.normalize("masoutis") } returns "masoutis"

        val result = locationResolver.resolve("Μασούτης", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - ignore clusters with insufficient count`() = runBlocking {
        val cluster = LocationCluster(40.71, -74.01, count = 1) // Only 1 expense
        coEvery { expenseRepository.getMerchantLocationClusters("masoutis") } returns listOf(cluster)
        coEvery { locationRepository.getCachedLocation("masoutis") } returns createCachedLocation(40.7128, -74.0060, "cache")

        val result = locationResolver.resolve("Masoutis", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
        // Should fall through to global cache, not use cluster
        coVerify { locationRepository.getCachedLocation("masoutis") }
    }

    // ============================================================================
    // SECTION 4: GPS-BIASED NOMINATIM SEARCH
    // ============================================================================

    @Test
    fun `stress - recent transaction with device location uses GPS bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        coEvery { geocodingService.search(any(), eq(40.71), eq(-74.01), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val recentTime = System.currentTimeMillis() - 30 * 60 * 1000 // 30 minutes ago
        val result = locationResolver.resolve("Starbucks", recentTime)

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify { geocodingService.search(any(), eq(40.71), eq(-74.01), any(), any()) }
    }

    @Test
    fun `stress - old transaction skips GPS bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val oldTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 1 day ago
        val result = locationResolver.resolve("Starbucks", oldTime)

        assertTrue(result is LocationResolutionResult.Resolved)
        // Should use name-only search without GPS bias
        coVerify(exactly = 0) { geocodingService.search(any(), eq(40.71), eq(-74.01), any(), any()) }
    }

    @Test
    fun `stress - no device location skips GPS bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns null
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val recentTime = System.currentTimeMillis() - 30 * 60 * 1000
        val result = locationResolver.resolve("Starbucks", recentTime)

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify { geocodingService.search(any(), null, null, null, any()) }
    }

    // ============================================================================
    // SECTION 5: NAME VARIANTS
    // ============================================================================

    @Test
    fun `stress - clean merchant name before processing`() = runBlocking {
        every { merchantCleaner.clean("  STARBUCKS #123  ") } returns "Starbucks"
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        locationResolver.resolve("  STARBUCKS #123  ", System.currentTimeMillis())

        verify { merchantCleaner.clean("  STARBUCKS #123  ") }
    }

    @Test
    fun `stress - canonicalize merchant name`() = runBlocking {
        every { canonicalizer.canonicalize("Starbucks") } returns CanonicalResult("Starbucks Coffee", emptyList(), 0.0)
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        locationResolver.resolve("Starbucks", System.currentTimeMillis())

        verify { canonicalizer.canonicalize("Starbucks") }
    }

    @Test
    fun `stress - convert to greeklish for search`() = runBlocking {
        every { greeklishNormalizer.normalize("masoutis") } returns "masoutis"
        coEvery { geocodingService.search(eq("masoutis"), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        locationResolver.resolve("Μασούτης", System.currentTimeMillis())

        verify { greeklishNormalizer.normalize("masoutis") }
    }

    // ============================================================================
    // SECTION 6: OVERPASS NEARBY POI
    // ============================================================================

    @Test
    fun `stress - single POI match auto-resolves`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        coEvery { nearbyPoiService.findNearby(eq(40.71), eq(-74.01), any(), any()) } returns listOf(createNearbyPoi(40.7128, -74.0060, "Starbucks"))

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertEquals(40.7128, resolved.latitude, 0.0001)
    }

    @Test
    fun `stress - multiple POI matches requires user selection`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        val pois = listOf(
            createNearbyPoi(40.7128, -74.0060, "Starbucks 5th Ave"),
            createNearbyPoi(40.7200, -74.0100, "Starbucks Broadway"),
            createNearbyPoi(40.7300, -74.0200, "Starbucks Wall St")
        )
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns pois

        val result = locationResolver.resolve("Starbucks", System.currentTimeMillis())

        assertTrue("Should require user selection", result is LocationResolutionResult.NeedsUserSelection)
        val selection = result as LocationResolutionResult.NeedsUserSelection
        assertEquals(3, selection.candidates.size)
    }

    @Test
    fun `stress - no POI matches results in unresolved`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns emptyList()

        val result = locationResolver.resolve("UnknownMerchant12345", System.currentTimeMillis())

        assertTrue("Should be unresolved", result is LocationResolutionResult.Unresolved)
    }

    @Test
    fun `stress - filter out null island coordinates`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(40.71, -74.01)
        val pois = listOf(
            createNearbyPoi(0.0, 0.0, "Invalid Location"), // Null island
            createNearbyPoi(40.7128, -74.0060, "Valid Location")
        )
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns pois

        val result = locationResolver.resolve("Test", System.currentTimeMillis())

        // Should filter out null island and use valid POI
        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertEquals(40.7128, resolved.latitude, 0.0001)
    }

    // ============================================================================
    // SECTION 7: NULL ISLAND REJECTION
    // ============================================================================

    @Test
    fun `stress - reject null island coordinates from geocoder`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(0.0, 0.0) // Null island

        val result = locationResolver.resolve("Test", System.currentTimeMillis())

        // Should reject null island and try next option
        assertTrue(result is LocationResolutionResult.Unresolved)
    }

    @Test
    fun `stress - reject near-null island coordinates`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(0.005, 0.005) // Very close to null island

        val result = locationResolver.resolve("Test", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Unresolved)
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - empty merchant name`() = runBlocking {
        every { merchantCleaner.clean("") } returns ""
        coEvery { geocodingService.search(eq(""), any(), any(), any(), any()) } returns null

        val result = locationResolver.resolve("", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Unresolved)
    }

    @Test
    fun `stress - very long merchant name`() = runBlocking {
        val longName = "A".repeat(1000)
        every { merchantCleaner.clean(longName) } returns longName
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val result = locationResolver.resolve(longName, System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - special characters in merchant name`() = runBlocking {
        val specialName = "Store @ 5th & Main! (Test)"
        every { merchantCleaner.clean(specialName) } returns specialName
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val result = locationResolver.resolve(specialName, System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - unicode characters in merchant name`() = runBlocking {
        val unicodeName = "Μασούτης Μοναστηρίου Αθήνα"
        every { merchantCleaner.clean(unicodeName) } returns unicodeName
        every { greeklishNormalizer.normalize("masoutis monastiriou athina") } returns "masoutis monastiriou athina"
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val result = locationResolver.resolve(unicodeName, System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - cluster query failure handled gracefully`() = runBlocking {
        coEvery { expenseRepository.getMerchantLocationClusters(any()) } throws RuntimeException("Database error")
        coEvery { locationRepository.getCachedLocation(any()) } returns createCachedLocation(40.7128, -74.0060, "cache")

        val result = locationResolver.resolve("Test", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - future timestamp`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val futureTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000
        val result = locationResolver.resolve("Test", futureTime)

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `stress - very old timestamp`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns createGeocodingResult(40.7128, -74.0060)

        val oldTime = 0L // Unix epoch
        val result = locationResolver.resolve("Test", oldTime)

        assertTrue(result is LocationResolutionResult.Resolved)
    }

    // ============================================================================
    // SECTION 9: PROVIDER MERCHANT KEY
    // ============================================================================

    @Test
    fun `stress - use provided merchant key for cache lookup`() = runBlocking {
        val cached = createCachedLocation(40.7128, -74.0060, "cache")
        coEvery { locationRepository.getCachedLocation("canonical_key") } returns cached

        val result = locationResolver.resolve(
            rawMerchantName = "Some Raw Name",
            transactionDateMs = System.currentTimeMillis(),
            merchantKey = "canonical_key"
        )

        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify { locationRepository.getCachedLocation("canonical_key") }
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - resolve completes quickly`() = runBlocking {
        coEvery { locationRepository.getCachedLocation(any()) } returns createCachedLocation(40.7128, -74.0060, "cache")

        val startTime = System.nanoTime()
        locationResolver.resolve("Test", System.currentTimeMillis())
        val duration = System.nanoTime() - startTime

        assertTrue("Should complete quickly with cache hit", duration < 100_000_000) // Under 100ms
    }

    // Helper functions
    private fun createGeocodingResult(lat: Double, lon: Double): GeocodingResult {
        return GeocodingResult(
            latitude = lat,
            longitude = lon,
            osmId = "N123",
            name = "Test",
            displayAddress = "Test Address",
            confidence = 0.8f,
            source = "nominatim"
        )
    }

    private fun createCachedLocation(lat: Double, lon: Double, source: String): MerchantLocation {
        return MerchantLocation(
            id = 1,
            normalizedMerchantName = "test",
            areaKey = "global",
            displayName = "Test",
            latitude = lat,
            longitude = lon,
            source = source,
            osmId = "N123",
            displayAddress = "Cached Address",
            confidence = 0.8f,
            lastResolvedAt = System.currentTimeMillis(),
            hitCount = 1
        )
    }

    private fun createCorrection(lat: Double, lon: Double, address: String): MerchantLocationCorrection {
        return MerchantLocationCorrection(
            id = 1,
            normalizedMerchantName = "test",
            correctedLatitude = lat,
            correctedLongitude = lon,
            areaLatitude = null,
            areaLongitude = null,
            areaKey = "test|global",
            areaRadiusKm = 5.0f,
            osmId = "N456",
            displayAddress = address,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createNearbyPoi(lat: Double, lon: Double, name: String): NearbyPoi {
        return NearbyPoi(
            osmId = "W123",
            name = name,
            latitude = lat,
            longitude = lon,
            distanceMetres = 100.0,
            category = "shop",
            displayAddress = "$name Address"
        )
    }
}
