package com.yourname.expensetracker.data.ai.provider.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CloudRetryPolicyTest {

    @Test
    fun `constants match shared retry defaults`() {
        assertEquals(3, CloudRetryPolicy.MAX_RETRY_ATTEMPTS)
        assertEquals(250L, CloudRetryPolicy.BASE_RETRY_BACKOFF_MS)
        assertEquals(1_500L, CloudRetryPolicy.MAX_RETRY_BACKOFF_MS)
        assertEquals(200L, CloudRetryPolicy.RETRY_JITTER_MS)
    }

    @Test
    fun `isRetryableHttpStatus returns true for 5xx, 429 and 408`() {
        assertTrue(CloudRetryPolicy.isRetryableHttpStatus(500))
        assertTrue(CloudRetryPolicy.isRetryableHttpStatus(503))
        assertTrue(CloudRetryPolicy.isRetryableHttpStatus(429))
        assertTrue(CloudRetryPolicy.isRetryableHttpStatus(408))
    }

    @Test
    fun `isRetryableHttpStatus returns false for non retryable statuses`() {
        assertFalse(CloudRetryPolicy.isRetryableHttpStatus(200))
        assertFalse(CloudRetryPolicy.isRetryableHttpStatus(400))
        assertFalse(CloudRetryPolicy.isRetryableHttpStatus(401))
        assertFalse(CloudRetryPolicy.isRetryableHttpStatus(404))
        assertFalse(CloudRetryPolicy.isRetryableHttpStatus(422))
    }

    @Test
    fun `isRetryableIoException returns true for timeout and connection reset`() {
        assertTrue(CloudRetryPolicy.isRetryableIoException(SocketTimeoutException("Read timed out")))
        assertTrue(CloudRetryPolicy.isRetryableIoException(IOException("Connection reset by peer")))
        assertTrue(CloudRetryPolicy.isRetryableIoException(IOException("socket timeout")))
    }

    @Test
    fun `isRetryableIoException inspects nested causes`() {
        val root = IOException("Connection reset by peer")
        val wrapped = IOException("request failed", root)

        assertTrue(CloudRetryPolicy.isRetryableIoException(wrapped))
    }

    @Test
    fun `isRetryableIoException returns false for non transient io failures`() {
        assertFalse(CloudRetryPolicy.isRetryableIoException(IOException("No route to host")))
    }

    @Test
    fun `backoffDelayMs returns bounded exponential delay with jitter`() {
        val attempt1 = CloudRetryPolicy.backoffDelayMs(attempt = 1)
        val attempt2 = CloudRetryPolicy.backoffDelayMs(attempt = 2)
        val attempt3 = CloudRetryPolicy.backoffDelayMs(attempt = 3)
        val attempt4 = CloudRetryPolicy.backoffDelayMs(attempt = 4)
        val attempt0 = CloudRetryPolicy.backoffDelayMs(attempt = 0)

        assertTrue(attempt1 in 250L..450L)
        assertTrue(attempt2 in 500L..700L)
        assertTrue(attempt3 in 1_000L..1_200L)
        assertTrue(attempt4 in 1_500L..1_700L)
        assertTrue(attempt0 in 250L..450L)
    }
}
