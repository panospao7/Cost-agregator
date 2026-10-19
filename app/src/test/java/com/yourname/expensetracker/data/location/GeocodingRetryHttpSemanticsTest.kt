package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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

/**
 * Retry/backoff semantics for the geocoding services. All services use plain `delay(...)` for
 * backoff (no withContext/Dispatchers), so `runTest` virtual time makes the real backoff
 * observable via `currentTime` without paying wall-clock waits.
 *
 * Expected virtual time (3 attempts, 2 backoff gaps):
 * - Photon / Geoapify / Google Places: initial 300 ms, doubling -> 300 + 600 = 900 ms.
 * - Nominatim: each retry gap is coerced up to NOMINATIM_MIN_INTERVAL_MS (1100 ms), and the first
 *   request is also gated by the 1100 ms rate limiter when the clock reports 0 elapsed -> 3 x 1100 ms.
 */
class GeocodingRetryHttpSemanticsTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    private class RateLimitCase(
        val name: String,
        val expectedBackoffMs: Long,
        val create: (OkHttpClient) -> GeocodingService
    )

    @Test
    fun `every geocoding provider returns RateLimited after three 429 responses with exact backoff`() = runTest {
        val cases = listOf(
            RateLimitCase("photon", expectedBackoffMs = 300L + 600L) { client ->
                PhotonGeocodingService(client = client, privacyGate = mockk<PrivacyGate>(relaxed = true))
            },
            RateLimitCase("nominatim", expectedBackoffMs = 3 * 1_100L) { client ->
                NominatimGeocodingService(
                    client = client,
                    timeProvider = zeroClock(),
                    privacyGate = mockk<PrivacyGate>(relaxed = true)
                )
            },
            RateLimitCase("geoapify", expectedBackoffMs = 300L + 600L) { client ->
                val keyStorage = mockk<SecureKeyStorage>()
                every { keyStorage.getKey(SecureKeyStorage.KEY_GEOAPIFY) } returns "test-geoapify-key"
                GeoapifyGeocodingService(
                    secureKeyStorage = keyStorage,
                    client = client,
                    privacyGate = mockk<PrivacyGate>(relaxed = true)
                )
            },
            RateLimitCase("google places", expectedBackoffMs = 300L + 600L) { client ->
                val keyStorage = mockk<SecureKeyStorage>()
                every { keyStorage.getKey(SecureKeyStorage.KEY_GOOGLE_PLACES) } returns "test-google-key"
                GooglePlacesGeocodingService(
                    secureKeyStorage = keyStorage,
                    client = client,
                    privacyGate = mockk<PrivacyGate>(relaxed = true)
                )
            }
        )

        for (case in cases) {
            val attempts = AtomicInteger(0)
            val service = case.create(rateLimitedClient(attempts))
            val startedAt = currentTime

            val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

            assertRateLimitedAfterRetries(case.name, result, attempts)
            assertEquals("${case.name}: virtual backoff time", case.expectedBackoffMs, currentTime - startedAt)
        }
    }

    @Test
    fun `nominatim returns final 503 response after retries`() = runTest {
        val attempts = AtomicInteger(0)
        val service = NominatimGeocodingService(
            client = fixedResponseClient(attempts, code = 503, message = "Service Unavailable"),
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            timeProvider = zeroClock()
        )

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertEquals(3, attempts.get())
        assertTrue(result is GeocodingBatchResult.Failure)
        val failure = result as GeocodingBatchResult.Failure
        assertEquals(GeocodingError.HttpError(503), failure.error)
        assertTrue(failure.error != GeocodingError.NetworkError)
        assertEquals(3 * 1_100L, currentTime)
    }

    private fun zeroClock(): TimeProvider {
        val clock = mockk<TimeProvider>(relaxed = true)
        every { clock.now() } returns 0L
        return clock
    }

    private fun assertRateLimitedAfterRetries(label: String, result: GeocodingBatchResult, attempts: AtomicInteger) {
        assertEquals("$label: attempts", 3, attempts.get())
        assertTrue("$label: expected Failure", result is GeocodingBatchResult.Failure)
        val failure = result as GeocodingBatchResult.Failure
        assertEquals("$label: error", GeocodingError.RateLimited, failure.error)
        assertTrue(failure.error != GeocodingError.NetworkError)
    }

    private fun rateLimitedClient(attempts: AtomicInteger): OkHttpClient {
        return fixedResponseClient(attempts, code = 429, message = "Too Many Requests")
    }

    private fun fixedResponseClient(attempts: AtomicInteger, code: Int, message: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body("{\"error\":\"test\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
