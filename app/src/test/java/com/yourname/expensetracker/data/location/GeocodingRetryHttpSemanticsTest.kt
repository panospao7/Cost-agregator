package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
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

class GeocodingRetryHttpSemanticsTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `photon returns RateLimited after three 429 responses`() = runBlocking {
        val attempts = AtomicInteger(0)
        val service = PhotonGeocodingService(client = rateLimitedClient(attempts), privacyGate = mockk<PrivacyGate>(relaxed = true))

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertRateLimitedAfterRetries(result, attempts)
    }

    @Test
    fun `nominatim returns RateLimited after three 429 responses`() = runBlocking {
        val attempts = AtomicInteger(0)
        val service = NominatimGeocodingService(client = rateLimitedClient(attempts), timeProvider = mockk<TimeProvider>(relaxed = true), privacyGate = mockk<PrivacyGate>(relaxed = true))

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertRateLimitedAfterRetries(result, attempts)
    }

    @Test
    fun `nominatim returns final 503 response after retries`() = runBlocking {
        val attempts = AtomicInteger(0)
        val service = NominatimGeocodingService(client = fixedResponseClient(attempts, code = 503, message = "Service Unavailable"), privacyGate = mockk<PrivacyGate>(relaxed = true), timeProvider = mockk<TimeProvider>(relaxed = true))

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertEquals(3, attempts.get())
        assertTrue(result is GeocodingBatchResult.Failure)
        val failure = result as GeocodingBatchResult.Failure
        assertEquals(GeocodingError.HttpError(503), failure.error)
        assertTrue(failure.error != GeocodingError.NetworkError)
    }

    @Test
    fun `geoapify returns RateLimited after three 429 responses`() = runBlocking {
        val attempts = AtomicInteger(0)
        val keyStorage = mockk<SecureKeyStorage>()
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEOAPIFY) } returns "test-geoapify-key"
        val service = GeoapifyGeocodingService(
            secureKeyStorage = keyStorage,
            client = rateLimitedClient(attempts),
            privacyGate = mockk<PrivacyGate>(relaxed = true)
        )

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertRateLimitedAfterRetries(result, attempts)
    }

    @Test
    fun `google places returns RateLimited after three 429 responses`() = runBlocking {
        val attempts = AtomicInteger(0)
        val keyStorage = mockk<SecureKeyStorage>()
        every { keyStorage.getKey(SecureKeyStorage.KEY_GOOGLE_PLACES) } returns "test-google-key"
        val service = GooglePlacesGeocodingService(
            secureKeyStorage = keyStorage,
            client = rateLimitedClient(attempts),
            privacyGate = mockk<PrivacyGate>(relaxed = true)
        )

        val result = service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)

        assertRateLimitedAfterRetries(result, attempts)
    }

    private fun assertRateLimitedAfterRetries(result: GeocodingBatchResult, attempts: AtomicInteger) {
        assertEquals(3, attempts.get())
        assertTrue(result is GeocodingBatchResult.Failure)
        val failure = result as GeocodingBatchResult.Failure
        assertEquals(GeocodingError.RateLimited, failure.error)
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