package com.yourname.expensetracker.domain.receipt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * RP-13 13a (P3-003) — OCR timeout retry and per-item isolation.
 *
 * [TimeoutCancellationException] is a [CancellationException]: the retry loop
 * must NOT exit on the first timeout, must NOT swallow genuine caller
 * cancellation, and must convert exhausted timeouts into the typed,
 * non-cancellation [OcrTimeoutException] so an individual item/page can be
 * marked failed while sibling items continue.
 *
 * Exercises the companion-scope [ReceiptOcrService.runWithRetry] and
 * [ReceiptOcrService.runOcrItemIsolated] directly (no Android/ML Kit needed);
 * virtual time via `runTest` keeps the backoff delays instant.
 */
class ReceiptOcrRetryIsolationTest {

    /** A real TimeoutCancellationException produced by an actual withTimeout. */
    private val timeoutException: TimeoutCancellationException = runBlocking {
        try {
            withTimeout(1) { awaitCancellation() }
            throw AssertionError("expected timeout")
        } catch (e: TimeoutCancellationException) {
            e
        }
    }

    @Test
    fun `timeout twice then success retries and succeeds`() = runTest {
        var attempts = 0
        val result = ReceiptOcrService.runWithRetry(
            maxAttempts = 3,
            initialDelayMs = 1,
            maxDelayMs = 2
        ) {
            attempts++
            if (attempts < 3) throw timeoutException
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `exhausted timeouts convert to typed non cancellation failure`() = runTest {
        var attempts = 0
        try {
            ReceiptOcrService.runWithRetry<String>(
                maxAttempts = 3,
                initialDelayMs = 1,
                maxDelayMs = 2
            ) {
                attempts++
                throw timeoutException
            }
            fail("expected OcrTimeoutException")
        } catch (e: OcrTimeoutException) {
            assertEquals(3, attempts)
            assertEquals(3, e.attempts)
            // The typed failure must NOT be a cancellation — it must not cancel
            // the importing scope.
            assertFalse(e is CancellationException)
        }
    }

    @Test
    fun `non timeout failure retries then rethrows original exception`() = runTest {
        var attempts = 0
        val failure = IOException("RECOGNIZER_DOWN")
        try {
            ReceiptOcrService.runWithRetry<String>(
                maxAttempts = 3,
                initialDelayMs = 1,
                maxDelayMs = 2
            ) {
                attempts++
                throw failure
            }
            fail("expected the original failure")
        } catch (e: IOException) {
            assertSame(failure, e)
            assertEquals(3, attempts)
        }
    }

    @Test
    fun `caller cancellation propagates immediately without retry`() = runTest {
        var attempts = 0
        try {
            ReceiptOcrService.runWithRetry<String>(maxAttempts = 3) {
                attempts++
                throw CancellationException("caller cancelled")
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("caller cancelled", e.message)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `timeout on already cancelled parent propagates cancellation instead of typed failure`() = runTest {
        var caught: Throwable? = null
        val parent = Job()
        async(parent) {
            try {
                ReceiptOcrService.runWithRetry<String>(
                    maxAttempts = 2,
                    initialDelayMs = 1,
                    maxDelayMs = 2
                ) {
                    parent.cancel() // parent dies while the attempt is in flight
                    throw timeoutException
                }
            } catch (t: Throwable) {
                caught = t
            }
        }
        advanceUntilIdle()
        assertTrue("expected cancellation to propagate", caught is CancellationException)
        assertFalse(
            "timeout on a dead parent must not become the typed failure",
            caught is OcrTimeoutException
        )
    }

    @Test
    fun `one failed item among three isolates failure and sibling items continue`() = runTest {
        val outcomes = listOf("page1", "page2", "page3").mapIndexed { index, page ->
            ReceiptOcrService.runOcrItemIsolated {
                if (index == 1) throw timeoutException
                page
            }
        }
        assertTrue(outcomes[0] is IsolatedOcrResult.Ok)
        assertEquals("page1", (outcomes[0] as IsolatedOcrResult.Ok).value)

        assertTrue(outcomes[1] is IsolatedOcrResult.Failed)
        assertEquals(
            "OCR_PAGE_TIMEOUT",
            (outcomes[1] as IsolatedOcrResult.Failed).reasonCode
        )

        assertTrue(outcomes[2] is IsolatedOcrResult.Ok)
        assertEquals("page3", (outcomes[2] as IsolatedOcrResult.Ok).value)
    }

    @Test
    fun `non timeout item failure isolates with controlled reason code`() = runTest {
        val outcome = ReceiptOcrService.runOcrItemIsolated<String> {
            throw IOException("RECOGNIZER_DOWN")
        }
        assertTrue(outcome is IsolatedOcrResult.Failed)
        assertEquals("OCR_PAGE_FAILED", (outcome as IsolatedOcrResult.Failed).reasonCode)
    }

    @Test
    fun `caller cancellation is not swallowed by item isolation`() = runTest {
        try {
            ReceiptOcrService.runOcrItemIsolated<String> {
                throw CancellationException("caller cancelled")
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("caller cancelled", e.message)
        }
    }
}
