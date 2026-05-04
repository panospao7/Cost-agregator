package com.yourname.expensetracker.domain.location

import android.util.Log
import com.yourname.expensetracker.domain.categorization.CanonicalResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
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

class LocationResolverStressTest {

    private lateinit var geocodingService: GeocodingService
    private lateinit var nearbyPoiService: NearbyPoiService
    private lateinit var locationProvider: ForegroundLocationProvider
    private lateinit var locationCachePort: LocationCachePort
    private lateinit var merchantClusterPort: MerchantClusterPort
    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var canonicalizer: MerchantCanonicalizer
    private lateinit var greeklishNormalizer: GreeklishNormalizer
    private lateinit var locationResolver: LocationResolver

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

        every { merchantCleaner.clean(any()) } answers { firstArg() }
        every { canonicalizer.canonicalize(any()) } answers { CanonicalResult(firstArg(), emptyList(), 0.0) }
        every { greeklishNormalizer.normalize(any()) } answers { firstArg() }

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

        locationResolver = LocationResolver(
            geocodingService = geocodingService,
            nearbyPoiService = nearbyPoiService,
            locationProvider = locationProvider,
            locationCachePort = locationCachePort,
            merchantClusterPort = merchantClusterPort,
            merchantCleaner = merchantCleaner,
            canonicalizer = canonicalizer,
            greeklishNormalizer = greeklishNormalizer,
            timeProvider = mockk(),
            privacyGate = mockk(),
        )
    }

    @Test
    fun `correction has highest priority`() = runBlocking {
        coEvery { locationCachePort.getCorrection(any(), any(), any()) } returns correction(40.7, -74.0)

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertTrue(resolved.source.contains("user", ignoreCase = true))
        assertEquals(40.7, resolved.latitude, 0.0001)
    }

    @Test
    fun `cache hit skips geocoding`() = runBlocking {
        coEvery { locationCachePort.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(exactly = 0) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `force refresh bypasses cache`() = runBlocking {
        coEvery { locationCachePort.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(41.0, -73.0))

        val result = locationResolver.resolve("Shop", System.currentTimeMillis(), forceRefresh = true)
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(atLeast = 1) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `recent transaction with device location uses gps bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { geocodingService.search(any(), 40.71, -74.01, any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(40.72, -74.02))

        val result = locationResolver.resolve("Shop", System.currentTimeMillis() - 60_000)
        assertTrue(result is LocationResolutionResult.Resolved)
    }

    @Test
    fun `device coordinates trigger second correction lookup before geocoding`() = runBlocking {
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

    @Test
    fun `gps biased geocode saves under derived non global area key`() = runBlocking {
        val rawMerchantName = "Shop"
        val merchantKey = MerchantKeyGenerator.generate(rawMerchantName)
        val expectedAreaKey = "$merchantKey|40.72|-74.02"

        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { geocodingService.search(any(), 40.71, -74.01, any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(40.72, -74.02))

        val result = locationResolver.resolve(rawMerchantName, System.currentTimeMillis() - 60_000)

        assertTrue(result is LocationResolutionResult.Resolved)
        verify(exactly = 1) { locationCachePort.getMostLikelyArea(merchantKey, 40.72, -74.02) }
        coVerify(exactly = 1) { locationCachePort.saveLocation(merchantKey, any(), expectedAreaKey) }
        coVerify(exactly = 0) { locationCachePort.saveLocation(merchantKey, any(), "global") }
    }

    @Test
    fun `old transaction does not use gps bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { geocodingService.search(any(), null, null, any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(40.72, -74.02))

        val result = locationResolver.resolve("Shop", 0L)
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(atLeast = 1) { geocodingService.search(any(), null, null, any(), any()) }
    }

    @Test
    fun `name only geocode saves under derived non global area key`() = runBlocking {
        val rawMerchantName = "Shop"
        val merchantKey = MerchantKeyGenerator.generate(rawMerchantName)
        val expectedAreaKey = "$merchantKey|41.0|-73.0"

        coEvery { geocodingService.search(any(), null, null, any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(41.0, -73.0))

        val result = locationResolver.resolve(rawMerchantName, 0L)

        assertTrue(result is LocationResolutionResult.Resolved)
        verify(exactly = 1) { locationCachePort.getMostLikelyArea(merchantKey, 41.0, -73.0) }
        coVerify(exactly = 1) { locationCachePort.saveLocation(merchantKey, any(), expectedAreaKey) }
        coVerify(exactly = 0) { locationCachePort.saveLocation(merchantKey, any(), "global") }
    }

    @Test
    fun `null island result is rejected`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(0.0, 0.0))

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Unresolved)
    }

    @Test
    fun `transient geocoder failure surfaces as retryable`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Failure(GeocodingError.RateLimited)

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())

        assertTrue(result is LocationResolutionResult.Retryable)
        assertEquals(GeocodingError.RateLimited, (result as LocationResolutionResult.Retryable).error)
        coVerify(exactly = 1) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `multiple nearby pois require user selection`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns
            NearbyPoiResult.Success(
                listOf(
                    poi("P1", 40.70, -74.00, "A"),
                    poi("P2", 40.71, -74.01, "B")
                )
            )

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.NeedsUserSelection)
    }

    @Test
    fun `provided merchant key is used for cache lookup`() = runBlocking {
        coEvery { locationCachePort.getCachedLocation("key123") } returns cached(40.70, -74.00, "cache")

        val result = locationResolver.resolve("Raw Name", System.currentTimeMillis(), merchantKey = "key123")
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(exactly = 1) { locationCachePort.getCachedLocation("key123") }
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