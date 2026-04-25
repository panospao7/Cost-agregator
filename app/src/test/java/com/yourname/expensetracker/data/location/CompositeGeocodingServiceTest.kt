package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
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

class CompositeGeocodingServiceTest {

    private val photon = mockk<PhotonGeocodingService>(relaxed = true)
    private val geoapify = mockk<GeoapifyGeocodingService>(relaxed = true)
    private val google = mockk<GooglePlacesGeocodingService>(relaxed = true)
    private val nominatim = mockk<NominatimGeocodingService>(relaxed = true)
    private lateinit var service: CompositeGeocodingService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        service = CompositeGeocodingService(photon, geoapify, google, nominatim)
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
}
