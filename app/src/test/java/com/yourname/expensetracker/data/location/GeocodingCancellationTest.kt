package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.executeCancellable
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeocodingCancellationTest {

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

        val deferred = async {
            factory.executeCancellable(request)
        }

        assertTrue(factory.awaitEnqueued())

        deferred.cancel()

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
                    Thread.sleep(POLL_INTERVAL_MS)
                }
                requestCancelled.countDown()
                throw IOException("Canceled")
            }
            .build()

        val service = PhotonGeocodingService(client)
        val deferred = async {
            service.searchMultiple(query = "coffee", biasLat = null, biasLon = null, limit = 5)
        }

        assertTrue(requestStarted.await(1, TimeUnit.SECONDS))

        deferred.cancel()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue(requestCancelled.await(1, TimeUnit.SECONDS))
    }

    private class RecordingCallFactory : Call.Factory {
        private val enqueued = CountDownLatch(1)
        private val cancelled = AtomicBoolean(false)

        override fun newCall(request: Request): Call {
            return RecordingCall(
                request = request,
                enqueued = enqueued,
                cancelled = cancelled
            )
        }

        fun awaitEnqueued(): Boolean = enqueued.await(1, TimeUnit.SECONDS)

        fun wasCancelled(): Boolean = cancelled.get()
    }

    private class RecordingCall(
        private val request: Request,
        private val enqueued: CountDownLatch,
        private val cancelled: AtomicBoolean
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
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = cancelled.get()

        override fun clone(): Call = RecordingCall(
            request = request,
            enqueued = enqueued,
            cancelled = cancelled
        )

        override fun timeout(): Timeout = Timeout.NONE
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10L
    }
}
