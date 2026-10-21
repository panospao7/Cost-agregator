package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
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
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test

class CompositeGeocodingServiceTest {

    private val photon = mockk<PhotonGeocodingService>(relaxed = true)
    private val geoapify = mockk<GeoapifyGeocodingService>(relaxed = true)
    private val google = mockk<GooglePlacesGeocodingService>(relaxed = true)
    private val nominatim = mockk<NominatimGeocodingService>(relaxed = true)
    private val privacyGate = mockk<PrivacyGate>(relaxed = true)
    private lateinit var service: CompositeGeocodingService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        // Default: allow geocoding
        coEvery { privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } returns PrivacyDecision.Allowed
        service = CompositeGeocodingService(photon, geoapify, google, nominatim, privacyGate)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `unexpected primary exception still cascades to fallback provider`() = runBlocking {
        coEvery { nominatim.search(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
        coEvery { photon.search(any(), any(), any(), any(), any()) } returns GeocodingLookupResult.Success(
            GeocodingResult(1.0, 2.0, "osm", "Shop", "Fallback", 0.9f, "photon")
        )

        val result = service.search("Shop", null, null, null, false)

        assertTrue(result is GeocodingLookupResult.Success)
        assertEquals("osm", (result as GeocodingLookupResult.Success).result?.osmId)
        coVerify(exactly = 1) { photon.search("Shop", null, null, null, false) }
    }

    @Test
    fun `single result search uses nominatim as primary provider`() = runBlocking {
        val expected = result("N1", 40.71, -74.01, "Addr")
        coEvery {
            nominatim.search("Shop", 40.0, -73.0, null, false)
        } returns GeocodingLookupResult.Success(expected)

        val resolved = service.search("Shop", 40.0, -73.0)

        assertNotNull(resolved)
        assertTrue(resolved is GeocodingLookupResult.Success)
        assertEquals("N1", (resolved as GeocodingLookupResult.Success).result?.osmId)
    }

    @Test
    fun `simple single word query uses free providers only`() = runBlocking {
        coEvery { photon.searchMultiple("LIDL", null, null, 5, any()) } returns
            GeocodingBatchResult.Success(listOf(result("P1", 40.71, -74.01, "LIDL")))
        coEvery { nominatim.searchMultiple("LIDL", null, null, 5, any()) } returns
            GeocodingBatchResult.Success(emptyList())

        val results = service.searchMultiple("LIDL", null, null, 5, useGoogle = false)

        assertTrue(results is GeocodingBatchResult.Success)
        assertTrue((results as GeocodingBatchResult.Success).results.isNotEmpty())
        coVerify(exactly = 1) { photon.searchMultiple("LIDL", null, null, 5, any()) }
        coVerify(exactly = 1) { nominatim.searchMultiple("LIDL", null, null, 5, any()) }
        coVerify(exactly = 0) { geoapify.searchMultiple(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { google.searchMultiple(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `complex query includes geoapify and google only when opted in`() = runBlocking {
        coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { nominatim.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { geoapify.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())
        coEvery { google.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        service.searchMultiple("Starbucks Athens", null, null, 5, useGoogle = false)
        coVerify(exactly = 1) { geoapify.searchMultiple(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { google.searchMultiple(any(), any(), any(), any(), any()) }

        service.searchMultiple("Starbucks Athens", null, null, 5, useGoogle = true)
        coVerify(exactly = 1) { google.searchMultiple(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `results within fifty meters are deduplicated keeping the higher ranked one`() = runBlocking {
        coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("P1", 40.712800, -74.006000, "Store A", confidence = 0.9f)))
        coEvery { nominatim.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("N1", 40.712805, -74.006005, "Store A dup", confidence = 0.8f)))

        val deduped = service.searchMultiple("Store", null, null, 20, useGoogle = false)

        assertTrue(deduped is GeocodingBatchResult.Success)
        val kept = (deduped as GeocodingBatchResult.Success).results
        assertEquals(1, kept.size)
        assertEquals("P1", kept.single().osmId)
    }

    @Test
    fun `discriminating qualifier match ranks above higher confidence result`() = runBlocking {
        val mixed = listOf(
            result("A", 40.71, -74.01, "Starbucks Egnatia 12", confidence = 0.95f),
            result("B", 40.72, -74.02, "Starbucks Monastiriou 10", confidence = 0.60f)
        )
        coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(mixed)
        coEvery { nominatim.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        val ranked = service.searchMultiple("Starbucks Monastiriou", null, null, 20, useGoogle = false)

        assertTrue(ranked is GeocodingBatchResult.Success)
        assertTrue((ranked as GeocodingBatchResult.Success).results.first().displayAddress?.contains("Monastiriou") == true)
    }

    @Test
    fun `result limit below ten is raised to a minimum window of ten`() = runBlocking {
        val many = (1..25).map { i -> result("R$i", 10.0 + i, 20.0 + i, "Result $i", confidence = 1.0f - i / 100f) }
        coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(many)
        coEvery { nominatim.searchMultiple(any(), any(), any(), any(), any()) } returns GeocodingBatchResult.Success(emptyList())

        val results = service.searchMultiple("Test", null, null, 5, useGoogle = false)

        assertTrue(results is GeocodingBatchResult.Success)
        assertEquals(10, (results as GeocodingBatchResult.Success).results.size)
    }

    @Test
    fun `provider exception does not abort merge of other providers`() = runBlocking {
        coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        coEvery { nominatim.searchMultiple(any(), any(), any(), any(), any()) } returns
            GeocodingBatchResult.Success(listOf(result("N1", 40.71, -74.01, "Fallback")))

        val results = service.searchMultiple("X", null, null, 5, useGoogle = false)

        assertTrue(results is GeocodingBatchResult.Success)
        assertEquals(1, (results as GeocodingBatchResult.Success).results.size)
    }

    @Test
    fun `provider cancellation is rethrown and not swallowed as a failure`() {
        var propagated: CancellationException? = null
        var returned: GeocodingBatchResult? = null
        runBlocking {
            coEvery { photon.searchMultiple(any(), any(), any(), any(), any()) } throws CancellationException("cancel")
            try {
                returned = service.searchMultiple("X", null, null, 5, useGoogle = false)
            } catch (e: CancellationException) {
                propagated = e
            }
        }

        if (returned != null) {
            fail("CancellationException was swallowed; searchMultiple returned $returned")
        }
        assertNotNull("CancellationException must propagate to the caller", propagated)
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
