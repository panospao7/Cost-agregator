package com.yourname.expensetracker.domain.location

import android.util.Log
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.categorization.CanonicalResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        geocodingService = mockk(relaxed = true)
        nearbyPoiService = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        locationRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        merchantCleaner = mockk(relaxed = true)
        canonicalizer = mockk(relaxed = true)
        greeklishNormalizer = mockk(relaxed = true)

        every { merchantCleaner.clean(any()) } answers { firstArg() }
        every { canonicalizer.canonicalize(any()) } answers { CanonicalResult(firstArg(), emptyList(), 0.0) }
        every { greeklishNormalizer.normalize(any()) } answers { firstArg() }

        coEvery { locationProvider.getLastKnownLocation() } returns null
        coEvery { locationRepository.getCorrection(any(), any(), any()) } returns null
        coEvery { locationRepository.getCachedLocation(any()) } returns null
        coEvery { locationRepository.getCachedLocationForArea(any(), any()) } returns null
        coEvery { expenseRepository.getMerchantLocationClusters(any()) } returns emptyList()
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(null)
        coEvery { nearbyPoiService.findNearby(any(), any(), any(), any()) } returns
            NearbyPoiResult.Success(emptyList())

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

    @Test
    fun `correction has highest priority`() = runBlocking {
        coEvery { locationRepository.getCorrection(any(), any(), any()) } returns correction(40.7, -74.0)

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Resolved)
        val resolved = result as LocationResolutionResult.Resolved
        assertTrue(resolved.source.contains("user", ignoreCase = true))
        assertEquals(40.7, resolved.latitude, 0.0001)
    }

    @Test
    fun `cache hit skips geocoding`() = runBlocking {
        coEvery { locationRepository.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(exactly = 0) { geocodingService.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `force refresh bypasses cache`() = runBlocking {
        coEvery { locationRepository.getCachedLocation(any()) } returns cached(40.7, -74.0, "cache")
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
    fun `old transaction does not use gps bias`() = runBlocking {
        coEvery { locationProvider.getLastKnownLocation() } returns (40.71 to -74.01)
        coEvery { geocodingService.search(any(), null, null, any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(40.72, -74.02))

        val result = locationResolver.resolve("Shop", 0L)
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(atLeast = 1) { geocodingService.search(any(), null, null, any(), any()) }
    }

    @Test
    fun `null island result is rejected`() = runBlocking {
        coEvery { geocodingService.search(any(), any(), any(), any(), any()) } returns
            GeocodingLookupResult.Success(geocoded(0.0, 0.0))

        val result = locationResolver.resolve("Shop", System.currentTimeMillis())
        assertTrue(result is LocationResolutionResult.Unresolved)
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
        coEvery { locationRepository.getCachedLocation("key123") } returns cached(40.70, -74.00, "cache")

        val result = locationResolver.resolve("Raw Name", System.currentTimeMillis(), merchantKey = "key123")
        assertTrue(result is LocationResolutionResult.Resolved)
        coVerify(exactly = 1) { locationRepository.getCachedLocation("key123") }
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

    private fun cached(lat: Double, lon: Double, source: String) = MerchantLocation(
        id = 1,
        normalizedMerchantName = "shop",
        areaKey = "global",
        displayName = "Shop",
        latitude = lat,
        longitude = lon,
        source = source,
        osmId = "N1",
        displayAddress = "Cached",
        confidence = 0.8f,
        lastResolvedAt = System.currentTimeMillis(),
        hitCount = 1
    )

    private fun correction(lat: Double, lon: Double) = MerchantLocationCorrection(
        id = 1,
        normalizedMerchantName = "shop",
        correctedLatitude = lat,
        correctedLongitude = lon,
        areaLatitude = null,
        areaLongitude = null,
        areaKey = "shop|global",
        areaRadiusKm = 5.0f,
        osmId = "C1",
        displayAddress = "Corrected",
        createdAt = System.currentTimeMillis()
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
