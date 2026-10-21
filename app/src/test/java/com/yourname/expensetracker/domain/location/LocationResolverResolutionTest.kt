package com.yourname.expensetracker.domain.location

import android.util.Log
import com.yourname.expensetracker.domain.categorization.CanonicalResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Resolution-order and outcome behaviour of [LocationResolver]
 * (correction, cache, device bias, geocoding, POI selection).
 */
class LocationResolverResolutionTest {

    private lateinit var geocodingService: GeocodingService
    private lateinit var nearbyPoiService: NearbyPoiService
    private lateinit var locationProvider: ForegroundLocationProvider
    private lateinit var locationCachePort: LocationCachePort
    private lateinit var merchantClusterPort: MerchantClusterPort
    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var canonicalizer: MerchantCanonicalizer
    private lateinit var greeklishNormalizer: GreeklishNormalizer
    private lateinit var timeProvider: TimeProvider
    private lateinit var locationResolver: LocationResolver
    private lateinit var privacyGate: com.yourname.expensetracker.domain.privacy.PrivacyGate

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        geocodingService = mockk(relaxed = true)
        nearbyPoiService = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        locationCachePort = mockk(relaxed = true)
        merchantClusterPort = mockk(relaxed = true)
        merchantCleaner = mockk(relaxed = true)
        canonicalizer = mockk(relaxed = true)
        greeklishNormalizer = mockk(relaxed = true)
        timeProvider = mockk()
        privacyGate = mockk()

        // All privacy checks allowed by default
        coEvery { privacyGate.check(PrivacyCapability.DEVICE_GPS_LOCATION, any()) } returns PrivacyDecision.Allowed
        coEvery { privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } returns PrivacyDecision.Allowed
        coEvery { privacyGate.check(PrivacyCapability.OVERPASS_API, any()) } returns PrivacyDecision.Allowed

        every { merchantCleaner.clean(any()) } answers { firstArg() }
        every { canonicalizer.canonicalize(any()) } answers { CanonicalResult(firstArg(), emptyList(), 0.0) }
        every { greeklishNormalizer.normalize(any()) } answers { firstArg() }
        every { timeProvider.now() } returns 1_000_000_000_000L

        stubPortDefaults()

        locationResolver = LocationResolver(
            geocodingService = geocodingService,
            nearbyPoiService = nearbyPoiService,
            locationProvider = locationProvider,
            locationCachePort = locationCachePort,
            merchantClusterPort = merchantClusterPort,
            merchantCleaner = merchantCleaner,
            canonicalizer = canonicalizer,
            greeklishNormalizer = greeklishNormalizer,
            timeProvider = timeProvider,
            privacyGate = privacyGate,
        )
    }

    private fun stubPortDefaults() {
        coEvery { locationProvider.getLastKnownLocation() } returns null
        coEvery { locationCachePort.getCorrection(any(), any(), any()) } returns null
        coEvery { locationCachePort.getCachedLocation(any()) } returns null
        coEvery { locationCachePort.getCachedLocationForArea(any(), any()) } returns null
        coEvery { merchantClusterPort.getMerchantLocationClusters(any()) } returns emptyList()
        every { locationCachePort.getMostLikelyArea(any(), any(), any()) } answers {
            val merchantName = firstArg<String>()
            val lat = secondArg<Double?>()
            val lon = thirdArg<Double?>()
            if (lat != null && lon != null) "$merchantName|$lat|$lon" else "global"
        }
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(null)
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns
            NearbyPoiResult.Success(emptyList())
    }

    /** Resets recorded calls and stubs of the ports so each table row starts from the default state. */
    private fun resetPorts() {
        clearMocks(geocodingService, nearbyPoiService, locationProvider, locationCachePort, merchantClusterPort)
        stubPortDefaults()
    }

    @Test
    fun `correction has highest priority`() = runBlocking<Unit> {
        coEvery { locationCachePort.getCorrection(any(), any(), any()) } returns correction(40.7, -74.0)

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertTrue(resolved.source.contains("user", ignoreCase = true))
        assertEquals(40.7, resolved.latitude, 0.0001)
    }

    @Test
    fun `cache hit resolves from cache using the provided merchant key`() = runBlocking<Unit> {
        // row 1: no merchantKey -> any cache key, geocoding skipped
        coEvery { locationCachePort.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")
        val noKey = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue("no merchantKey row: $noKey", noKey is LocationResolutionResult.Resolved)
        coVerify(exactly = 0) { geocodingService.search(any(), any(), any(), any(), any()) }

        // row 2: provided merchantKey is the cache lookup key
        resetPorts()
        coEvery { locationCachePort.getCachedLocation("key123") } returns cached(40.70, -74.00, "cache")
        val withKey = locationResolver.resolve("Raw Name", System.currentTimeMillis(), merchantKey = "key123")
        assertTrue("provided merchantKey row: $withKey", withKey is LocationResolutionResult.Resolved)
        coVerify(exactly = 1) { locationCachePort.getCachedLocation("key123") }
    }

    @Test
    fun `force refresh bypasses cache`() = runBlocking<Unit> {
        coEvery { locationCachePort.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(41.0, -73.0))

        val result = locationResolver.resolve("Shop", System.currentTimeMillis(), forceRefresh = true)
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(atLeast = 1) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `device coordinates trigger second correction lookup before geocoding`() = runBlocking<Unit> {
        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { locationCachePort.getCorrection("Shop", null, null) } returns null
        coEvery { locationCachePort.getCorrection("Shop", 40.71, -74.01) } returns correction(40.72, -74.02)

        val result = locationResolver.resolve("Shop", System.currentTimeMillis() - 60_000, merchantKey = "Shop")

        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertEquals(40.72, resolved.latitude, 0.0001)
        coVerifyOrder {
            locationCachePort.getCorrection("Shop", null, null)
            locationProvider.getLastKnownLocation()
            locationCachePort.getCorrection("Shop", 40.71, -74.01)
        }
        coVerify(exactly = 0) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    private class GeocodeBiasRow(
        val label: String,
        val device: Pair<Double, Double>?,
        val transactionMs: () -> Long,
        val searchLat: Double?,
        val searchLon: Double?,
        val geocodedLat: Double,
        val geocodedLon: Double,
        // null = saved-key assertions are not part of this row
        val expectedAreaCoords: Pair<Double, Double>?
    )

    @Test
    fun `geocode bias follows transaction age and saves under derived non global area key`() = runBlocking<Unit> {
        val rawMerchantName = "Shop"
        val merchantKey = MerchantKeyGenerator.generate(rawMerchantName)
        val rows = listOf(
            GeocodeBiasRow(
                "recent transaction with device location uses gps bias",
                device = 40.71 to -74.01,
                transactionMs = { System.currentTimeMillis() - 60_000 },
                searchLat = 40.71, searchLon = -74.01,
                geocodedLat = 40.72, geocodedLon = -74.02,
                expectedAreaCoords = 40.72 to -74.02
            ),
            GeocodeBiasRow(
                "old transaction does not use gps bias",
                device = 40.71 to -74.01,
                transactionMs = { 0L },
                searchLat = null, searchLon = null,
                geocodedLat = 40.72, geocodedLon = -74.02,
                expectedAreaCoords = null
            ),
            GeocodeBiasRow(
                "name only geocode without device location",
                device = null,
                transactionMs = { 0L },
                searchLat = null, searchLon = null,
                geocodedLat = 41.0, geocodedLon = -73.0,
                expectedAreaCoords = 41.0 to -73.0
            )
        )
        for (row in rows) {
            resetPorts()
            coEvery { locationProvider.getLastKnownLocation() } returns row.device
            coEvery { geocodingService.search(any(), row.searchLat, row.searchLon, any(), any()) } returns
                GeocodingLookupResult.Success(geocoded(row.geocodedLat, row.geocodedLon))

            val result = locationResolver.resolve(rawMerchantName, row.transactionMs())

            assertTrue("Resolved for row: ${row.label} (was $result)", result is LocationResolutionResult.Resolved)
            coVerify(atLeast = 1) {
                geocodingService.search(any(), row.searchLat, row.searchLon, any(), any())
            }
            val coords = row.expectedAreaCoords
            if (coords != null) {
                val expectedAreaKey = "$merchantKey|${coords.first}|${coords.second}"
                verify(exactly = 1) {
                    locationCachePort.getMostLikelyArea(merchantKey, coords.first, coords.second)
                }
                coVerify(exactly = 1) { locationCachePort.saveLocation(merchantKey, any(), expectedAreaKey) }
                coVerify(exactly = 0) { locationCachePort.saveLocation(merchantKey, any(), "global") }
            }
        }
    }

    private class OutcomeRow(
        val label: String,
        val arrange: () -> Unit,
        val assertOutcome: (LocationResolutionResult) -> Unit
    )

    @Test
    fun `unusable geocode results map to their resolution outcomes`() = runBlocking<Unit> {
        val rows = listOf(
            OutcomeRow(
                "null island result is rejected",
                arrange = {
                    coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
                        GeocodingLookupResult.Success(geocoded(0.0, 0.0))
                },
                assertOutcome = { result ->
                    assertTrue("null island row: $result", result is LocationResolutionResult.Unresolved)
                }
            ),
            OutcomeRow(
                "transient geocoder failure surfaces as retryable",
                arrange = {
                    coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
                        GeocodingLookupResult.Failure(GeocodingError.RateLimited)
                },
                assertOutcome = { result ->
                    assertTrue("rate-limited row: $result", result is LocationResolutionResult.Retryable)
                    assertEquals(
                        "rate-limited row error",
                        GeocodingError.RateLimited,
                        (result as LocationResolutionResult.Retryable).error
                    )
                    coVerify(exactly = 1) { geocodingService.search(any(), any(), any(), any(), any()) }
                }
            ),
            OutcomeRow(
                "multiple nearby pois require user selection",
                arrange = {
                    coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
                    coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns
                        NearbyPoiResult.Success(
                            listOf(
                                poi("P1", 40.70, -74.00, "A"),
                                poi("P2", 40.71, -74.01, "B")
                            )
                        )
                },
                assertOutcome = { result ->
                    assertTrue("multiple pois row: $result", result is LocationResolutionResult.NeedsUserSelection)
                }
            )
        )
        for (row in rows) {
            resetPorts()
            row.arrange()
            row.assertOutcome(locationResolver.resolve("Shop", System.currentTimeMillis()))
        }
    }

    private fun geocoded(lat: Double, lon: Double) = GeocodingResult(
        latitude = lat,
        longitude = lon,
        osmId = "N1",
        name = "Name",
        displayAddress = "Address",
        confidence = 0.8f,
        source = "nominatim"
    )

    private fun cached(lat: Double, lon: Double, source: String) = CachedMerchantLocation(
        latitude = lat,
        longitude = lon,
        source = source,
        osmId = "N1",
        displayAddress = "Cached",
        confidence = 0.8f
    )

    private fun correction(lat: Double, lon: Double) = LocationCorrection(
        correctedLatitude = lat,
        correctedLongitude = lon,
        osmId = "C1",
        displayAddress = "Corrected"
    )

    private fun poi(id: String, lat: Double, lon: Double, name: String) = NearbyPoi(
        osmId = id,
        name = name,
        latitude = lat,
        longitude = lon,
        distanceMetres = 100.0,
        category = "shop",
        displayAddress = name
    )
}
