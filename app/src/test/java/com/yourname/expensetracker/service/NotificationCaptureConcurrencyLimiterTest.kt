package com.yourname.expensetracker.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P-01-3 (S3): [NotificationCaptureConcurrencyLimiter] bounds concurrent capture
 * pipelines without ever dropping a caller, and always returns its permit.
 *
 * Every ordering is driven by [CompletableDeferred] gates on the test scheduler,
 * never by delays or wall-clock timing. All data is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCaptureConcurrencyLimiterTest {

    private class SyntheticFailure(message: String) : RuntimeException(message)

    // -- constructor contract -----------------------------------------------------

    @Test
    fun `default limiter exposes DEFAULT_PERMITS free permits`() {
        val limiter = NotificationCaptureConcurrencyLimiter()

        assertTrue("default permits must be positive", NotificationCaptureConcurrencyLimiter.DEFAULT_PERMITS > 0)
        assertEquals(NotificationCaptureConcurrencyLimiter.DEFAULT_PERMITS, limiter.availablePermits)
    }

    @Test
    fun `zero permits is rejected`() {
        try {
            NotificationCaptureConcurrencyLimiter(0)
            fail("zero permits must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `negative permits is rejected`() {
        try {
            NotificationCaptureConcurrencyLimiter(-1)
            fail("negative permits must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // -- peak concurrency is bounded and nothing is dropped ---------------------------

    @Test
    fun `peak concurrency never exceeds permits and queued callers still run`() = runTest {
        val permits = 4
        val callers = 6
        val limiter = NotificationCaptureConcurrencyLimiter(permits)
        val gates = List(callers) { CompletableDeferred<Unit>() }
        var inFlight = 0
        var peak = 0
        var started = 0
        var completed = 0

        repeat(callers) { index ->
            launch {
                limiter.withPermit {
                    started++
                    inFlight++
                    if (inFlight > peak) peak = inFlight
                    gates[index].await()
                    inFlight--
                    completed++
                }
            }
        }
        runCurrent()

        // Exactly the permitted callers are inside; the rest are queued, not dropped.
        assertEquals(permits, started)
        assertEquals(permits, inFlight)
        assertEquals(0, limiter.availablePermits)

        // Freeing one holder lets exactly one queued caller (the 5th) in and run.
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(permits + 1, started)
        assertEquals(1, completed)
        assertEquals(permits, inFlight)
        assertEquals(0, limiter.availablePermits)

        gates.forEach { it.complete(Unit) }
        advanceUntilIdle()

        assertEquals("every caller must eventually run", callers, started)
        assertEquals("every caller must complete", callers, completed)
        assertEquals("concurrency must never exceed the permits", permits, peak)
        assertEquals(0, inFlight)
        assertEquals(permits, limiter.availablePermits)
    }

    @Test
    fun `single permit serialises callers`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        val gates = List(3) { CompletableDeferred<Unit>() }
        var inFlight = 0
        var peak = 0
        var completed = 0

        repeat(3) { index ->
            launch {
                limiter.withPermit {
                    inFlight++
                    if (inFlight > peak) peak = inFlight
                    gates[index].await()
                    inFlight--
                    completed++
                }
            }
        }
        runCurrent()
        assertEquals(1, inFlight)

        gates.forEach { it.complete(Unit) }
        advanceUntilIdle()

        assertEquals(3, completed)
        assertEquals(1, peak)
        assertEquals(1, limiter.availablePermits)
    }

    @Test
    fun `withPermit returns the block value and releases the permit`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)

        val result = limiter.withPermit { 42 }

        assertEquals(42, result)
        assertEquals(1, limiter.availablePermits)
    }

    // -- cancellation ---------------------------------------------------------------

    @Test
    fun `cancelling a queued waiter takes no permit and leaves availablePermits correct`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        val holderGate = CompletableDeferred<Unit>()
        var waiterRan = false
        var laterRan = false

        val holder = launch { limiter.withPermit { holderGate.await() } }
        runCurrent()
        assertEquals(0, limiter.availablePermits)
        val waiter = launch { limiter.withPermit { waiterRan = true } }
        runCurrent()
        assertFalse("waiter must be queued behind the holder", waiterRan)

        waiter.cancel()
        runCurrent()

        assertFalse("a cancelled waiter must never run its block", waiterRan)
        assertEquals("cancelling a waiter must not hand out or return a permit", 0, limiter.availablePermits)

        holderGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(holder.isCompleted)
        assertEquals("the holder's permit comes back exactly once", 1, limiter.availablePermits)

        // The cancelled waiter must not have left a ghost claim on the permit.
        launch { limiter.withPermit { laterRan = true } }
        advanceUntilIdle()
        assertTrue("a later caller must still obtain the permit", laterRan)
        assertEquals(1, limiter.availablePermits)
    }

    @Test
    fun `cancelling a holder releases its permit so the queued waiter runs`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        val holderGate = CompletableDeferred<Unit>()
        var waiterRan = false

        val holder = launch { limiter.withPermit { holderGate.await() } }
        runCurrent()
        val waiter = launch { limiter.withPermit { waiterRan = true } }
        runCurrent()
        assertFalse(waiterRan)
        assertEquals(0, limiter.availablePermits)

        holder.cancel()
        advanceUntilIdle()

        assertTrue("the holder was cancelled", holder.isCancelled)
        assertTrue("the queued waiter must run once the cancelled holder releases", waiterRan)
        assertTrue(waiter.isCompleted)
        assertEquals(1, limiter.availablePermits)
    }

    // -- failure and early return ------------------------------------------------------

    @Test
    fun `exception in the block releases the permit and propagates`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        val failure = SyntheticFailure("synthetic")

        try {
            limiter.withPermit<Unit> { throw failure }
            fail("the block exception must propagate")
        } catch (e: SyntheticFailure) {
            assertSame("the original exception must propagate unchanged", failure, e)
        }

        assertEquals("permit must be released after a failing block", 1, limiter.availablePermits)
        assertEquals(7, limiter.withPermit { 7 })
        assertEquals(1, limiter.availablePermits)
    }

    @Test
    fun `early return out of the inline withPermit releases the permit`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        var reachedAfterReturn = false
        val bail = listOf(true).first()

        val job = launch {
            limiter.withPermit {
                if (bail) return@launch
                reachedAfterReturn = true
            }
            reachedAfterReturn = true
        }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse("return@launch must leave the coroutine immediately", reachedAfterReturn)
        assertEquals("the early return must release the permit", 1, limiter.availablePermits)
    }

    @Test
    fun `control - without an early return the block continues and the permit is released`() = runTest {
        val limiter = NotificationCaptureConcurrencyLimiter(1)
        var reachedAfterBlock = false
        val bail = listOf(false).first()

        val job = launch {
            limiter.withPermit {
                if (bail) return@launch
            }
            reachedAfterBlock = true
        }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(reachedAfterBlock)
        assertEquals(1, limiter.availablePermits)
    }
}
