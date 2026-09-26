package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.executeCancellable
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeocodingCancellationTest {

    private var httpClient: OkHttpClient? = null

    @After
    fun tearDown() {
        httpClient?.let { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        unmockkStatic(Log::class)
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `executeCancellable cancels underlying call when coroutine is cancelled`() = runBlocking {
        val factory = RecordingCallFactory()
        val request = Request.Builder().url("https://example.com/search").build()

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            factory.executeCancellable(request)
        }

        assertTrue(factory.awaitEnqueued())

        deferred.cancelAndJoin()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue(factory.wasCancelled())
    }

    @Test
    fun `photon cancellation rethrows CancellationException and cancels underlying call`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val requestCancelled = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.countDown()
                while (!chain.call().isCanceled()) {
                    Thread.yield()
                }
                requestCancelled.countDown()
                throw IOException("Canceled")
            }
            .build().also { httpClient = it }

        val privacyGate = mockk<PrivacyGate>()
        coEvery {
            privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING, any())
        } returns PrivacyDecision.Allowed
        val service = PhotonGeocodingService(client, privacyGate = privacyGate)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)
        }

        assertTrue(requestStarted.await(1, TimeUnit.SECONDS))

        deferred.cancelAndJoin()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue(requestCancelled.await(5, TimeUnit.SECONDS))
    }

    private class RecordingCallFactory : Call.Factory {
        private val enqueued = CountDownLatch(1)
        private val cancellationRequested = CountDownLatch(1)
        private val cancelled = AtomicBoolean(false)

        override fun newCall(request: Request): Call {
            return RecordingCall(
                request = request,
                enqueued = enqueued,
                cancelled = cancelled,
                cancellationRequested = cancellationRequested
            )
        }

        fun awaitEnqueued(): Boolean = enqueued.await(1, TimeUnit.SECONDS)

        fun awaitCancelled(): Boolean = cancellationRequested.await(1, TimeUnit.SECONDS)

        fun wasCancelled(): Boolean = cancelled.get()
    }

    private class RecordingCall(
        private val request: Request,
        private val enqueued: CountDownLatch,
        private val cancelled: AtomicBoolean,
        private val cancellationRequested: CountDownLatch
    ) : Call {
        private val executed = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            throw UnsupportedOperationException("Synchronous execute should not be used")
        }

        override fun enqueue(responseCallback: Callback) {
            executed.set(true)
            enqueued.countDown()
        }

        override fun cancel() {
            cancelled.set(true)
            cancellationRequested.countDown()
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = cancelled.get()

        override fun clone(): Call = RecordingCall(
            request = request,
            enqueued = enqueued,
            cancelled = cancelled,
            cancellationRequested = cancellationRequested
        )

        override fun timeout(): Timeout = Timeout.NONE
    }
}
