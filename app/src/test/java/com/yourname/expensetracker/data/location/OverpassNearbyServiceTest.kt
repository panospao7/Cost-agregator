package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.NearbyPoiResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OverpassNearbyServiceTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `findNearby returns RateLimited after final 429 retry`() = runBlocking {
        val attempts = AtomicInteger(0)
        val service = OverpassNearbyService(client = fixedResponseClient(attempts, code = 429, body = "{" +
            "\"remark\":\"rate limited\"}"))

        val result = service.findNearby(
            lat = 37.9838,
            lon = 23.7275,
            merchantName = "Coffee",
            radiusMetres = 250
        )

        assertEquals(3, attempts.get())
        assertTrue(result is NearbyPoiResult.Failure)
        val failure = result as NearbyPoiResult.Failure
        assertEquals(GeocodingError.RateLimited, failure.error)
        assertTrue(failure.error != GeocodingError.NetworkError)
    }

    @Test
    fun `findNearby ranks greek merchant names ahead of transliterated fallback`() = runBlocking {
        val service = OverpassNearbyService(client = fixedResponseClient(attempts = AtomicInteger(0), code = 200, body = greekRankingBody()))

        val result = service.findNearby(
            lat = 37.9838,
            lon = 23.7275,
            merchantName = "Σκλαβενίτης",
            radiusMetres = 500
        )

        assertTrue(result is NearbyPoiResult.Success)
        val pois = (result as NearbyPoiResult.Success).pois
        assertEquals("Σκλαβενίτης", pois.first().name)
        assertEquals("N1", pois.first().osmId)
        assertEquals("Sklavenitis Bakery", pois[1].name)
    }

    private fun fixedResponseClient(attempts: AtomicInteger, code: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun greekRankingBody(): String =
        """
        {
          "elements": [
            {
              "type": "node",
              "id": 1,
              "lat": 37.98381,
              "lon": 23.72754,
              "tags": {
                "name": "Σκλαβενίτης",
                "shop": "supermarket"
              }
            },
            {
              "type": "node",
              "id": 2,
              "lat": 37.9838005,
              "lon": 23.7275005,
              "tags": {
                "name": "Sklavenitis Bakery",
                "shop": "bakery"
              }
            }
          ]
        }
        """.trimIndent()
}
