package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@Ignore("Stress test: may hang in CI, run manually")
class CompositeGeocodingServiceStressTest {

    private lateinit var photonService: PhotonGeocodingService
    private lateinit var geoapifyService: GeoapifyGeocodingService
    private lateinit var googlePlacesService: GooglePlacesGeocodingService
    private lateinit var nominatimService: NominatimGeocodingService
    private lateinit var privacyGate: PrivacyGate
    private lateinit var compositeService: CompositeGeocodingService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        photonService = mockk(relaxed = true)
        geoapifyService = mockk(relaxed = true)
        googlePlacesService = mockk(relaxed = true)
        nominatimService = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        coEvery { privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } returns PrivacyDecision.Allowed
        compositeService = CompositeGeocodingService(
            photon = photonService,
            geoapify = geoapifyService,
            googlePlaces = googlePlacesService,
            nominatim = nominatimService,
            privacyGate = privacyGate
        )
    }

    @Test
    fun `search delegates to nominatim`() = runBlocking {
        val expected = result("N1", 40.71, -74.01, "Addr")
        coEvery {
            nominatimService.search("Shop", 40.0, -73.0, null, false)
        } returns GeocodingLookupResult.Success(expected)

        val resolved = compositeService.search("Shop", 40.0, -73.0)

        assertNotNull(resolved)
        assertTrue(resolved is GeocodingLookupResult.Success)
        assertEquals("N1", (resolved as GeocodingLookupResult.Success).result?.osmId)
    }

    @Test
    fun `simple query uses free providers only`() = runBlocking {
        coEvery { photonService.searchMultiple("LIDL", null, null, 5, any()) } returns
            GeocodingBatchResult.Success(listOf(result("P1", 40.71, -74.01, "LIDL")))
        coEvery { nominatimService.searchMultiple("LIDL", null, null, 5, any()) } returns
            GeocodingBatchResult.Success(emptyList())

        val results = compositeService.searchMultiple("LIDL", null, null, 5, useGoogle = false)

        assertTrue(results is GeocodingBatchResult.Success)
        assertTrue((results as GeocodingBatchResult.Success).results.isNotEmpty())
        coVerify(exactly = 1) { photonService.searchMultiple("LIDL", null, null, 5, any()) }
        coVerify(exactly = 1) { nominatimService.searchMultiple("LIDL", null, null, 5, any()) }
        coVerify(exactly = 0) { geoapifyService.searchMultiple(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { googlePlacesService.searchMultiple(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `complex query includes geoapify and optional google`() = runBlocking {
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { nominatimService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { geoapifyService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { googlePlacesService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        compositeService.searchMultiple("Starbucks Athens", null, null, 5, useGoogle = false)
        coVerify(exactly = 1) { geoapifyService.searchMultiple(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { googlePlacesService.searchMultiple(any(), any(), any(), any(), any()) }

        compositeService.searchMultiple("Starbucks Athens", null, null, 5, useGoogle = true)
        coVerify(exactly = 1) { googlePlacesService.searchMultiple(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deduplicates near-identical coordinates`() = runBlocking {
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("P1", 40.712800, -74.006000, "Store A", confidence = 0.9f)))
        coEvery { nominatimService.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("N1", 40.712805, -74.006005, "Store A dup", confidence = 0.8f)))

        val deduped = compositeService.searchMultiple("Store", null, null, 20, useGoogle = false)
        assertTrue(deduped is GeocodingBatchResult.Success)
        assertEquals(1, (deduped as GeocodingBatchResult.Success).results.size)
    }

    @Test
    fun `qualifier ranking boosts matching address`() = runBlocking {
        val mixed = listOf(
            result("A", 40.71, -74.01, "Starbucks Egnatia 12", confidence = 0.95f),
            result("B", 40.72, -74.02, "Starbucks Monastiriou 10", confidence = 0.60f)
        )
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(mixed)
        coEvery { nominatimService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        val ranked = compositeService.searchMultiple("Starbucks Monastiriou", null, null, 20, useGoogle = false)
        assertTrue(ranked is GeocodingBatchResult.Success)
        assertTrue((ranked as GeocodingBatchResult.Success).results.first().displayAddress?.contains("Monastiriou") == true)
    }

    @Test
    fun `enforces minimum result window of ten`() = runBlocking {
        val many = (1..25).map { i -> result("R$i", 10.0 + i, 20.0 + i, "Result $i", confidence = 1.0f - i / 100f) }
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(many)
        coEvery { nominatimService.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        val results = compositeService.searchMultiple("Test", null, null, 5, useGoogle = false)
        assertTrue(results is GeocodingBatchResult.Success)
        assertEquals(10, (results as GeocodingBatchResult.Success).results.size)
    }

    @Test
    fun `provider errors do not abort merge`() = runBlocking {
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        coEvery { nominatimService.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("N1", 40.71, -74.01, "Fallback")))

        val results = compositeService.searchMultiple("X", null, null, 5, useGoogle = false)
        assertTrue(results is GeocodingBatchResult.Success)
        assertEquals(1, (results as GeocodingBatchResult.Success).results.size)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates`() {
        runBlocking {
        coEvery { photonService.searchMultiple(any(), any(), any(), any(), any()) } throws CancellationException("cancel")
            compositeService.searchMultiple("X", null, null, 5, useGoogle = false)
        }
    }

    private fun result(
        osmId: String,
        lat: Double,
        lon: Double,
        display: String,
        confidence: Float = 0.8f
    ) = GeocodingResult(
        latitude = lat,
        longitude = lon,
        osmId = osmId,
        name = "Name",
        displayAddress = display,
        confidence = confidence,
        source = "test"
    )
}
