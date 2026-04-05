package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NominatimGeocodingServiceLocaleTest {

    @Test
    fun `reverse geocode uses dot decimals under Greek locale`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("el", "GR"))

            var capturedUrl: String? = null
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedUrl = chain.request().url.toString()
                    val body = """
                        {"lat":"37.9838100","lon":"23.7275390","display_name":"Athens, Greece","name":"Athens"}
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

            val service = NominatimGeocodingService(client)
            val result = service.reverseGeocode(lat = 37.98381, lon = 23.727539)

            assertTrue(result is GeocodingLookupResult.Success)
            val url = requireNotNull(capturedUrl) { "Expected reverse geocoding URL to be captured" }
            assertTrue(url.contains("lat=37.9838100"))
            assertTrue(url.contains("lon=23.7275390"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
