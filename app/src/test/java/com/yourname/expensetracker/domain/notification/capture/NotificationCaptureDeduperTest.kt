package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-10 10c (P1-006): the dedupe window must run on the MONOTONIC clock.
 *
 * Wall-clock dependence (the old behavior) meant a backward wall-clock jump
 * (NTP sync, user clock change) suppressed identical re-posts for the full
 * window, and a forward jump re-admitted duplicates instantly. The deduper
 * now reads [com.yourname.expensetracker.domain.util.MonotonicTimeProvider]
 * (nanoseconds internally; the public window parameters stay in millis).
 *
 * A monotonic source cannot jump backward by definition, so the backward-jump
 * regression is pinned behaviorally: with the fake time unchanged, repeated
 * tryStart calls are still deduped — the deduper's decision depends ONLY on
 * the injected monotonic time, never on the wall clock.
 */
class NotificationCaptureDeduperTest {

    private val fake = FakeMonotonicTimeProvider()
    private val deduper = NotificationCaptureDeduper(fake)

    // 1. duplicate within window suppressed
    @Test
    fun `duplicate within window is suppressed`() {
        assertTrue(deduper.tryStart("k", 5_000L))
        assertFalse("same key within window must be a duplicate", deduper.tryStart("k", 5_000L))
    }

    // 2. window expiry allows re-capture
    @Test
    fun `window expiry allows re-capture`() {
        assertTrue(deduper.tryStart("k", 5_000L))
        fake.advanceMillis(5_001L)
        assertTrue("beyond windowMs the same key must be capturable again", deduper.tryStart("k", 5_000L))
    }

    // 3. backward wall-clock jump immunity (behavioral pin — see class KDoc)
    @Test
    fun `unchanged monotonic time still dedupes`() {
        assertTrue(deduper.tryStart("k", 5_000L))
        // No time advance at all: a wall-clock implementation could observe a
        // backward jump here; the monotonic clock simply has not moved, so the
        // entry must still be within the window.
        assertFalse(deduper.tryStart("k", 5_000L))
        fake.advanceNanos(1L)
        assertFalse(deduper.tryStart("k", 5_000L))
    }

    // 4. forward jump → re-capture allowed
    @Test
    fun `forward time jump past window allows re-capture`() {
        assertTrue(deduper.tryStart("k", 5_000L))
        fake.advanceMillis(60_000L)
        assertTrue(deduper.tryStart("k", 5_000L))
    }

    // 5. cleanupExpired removes expired entries and keeps live ones
    @Test
    fun `cleanupExpired removes only expired entries`() {
        assertTrue(deduper.tryStart("old", 5_000L))
        fake.advanceMillis(6_000L)
        assertTrue(deduper.tryStart("live", 5_000L))

        deduper.cleanupExpired(5_000L)
        assertEquals(1, deduper.size) // only "live" remains

        // Behavioral: "old" was purged, but re-capture succeeds either way;
        // the surviving "live" entry must still suppress within its window.
        assertFalse(deduper.tryStart("live", 5_000L))
    }

    // 7. distinct keys are independent
    @Test
    fun `distinct keys are deduped independently`() {
        assertTrue(deduper.tryStart("A", 5_000L))
        assertFalse(deduper.tryStart("A", 5_000L))
        assertTrue("key B must be unaffected by key A's dedupe", deduper.tryStart("B", 5_000L))
        // remove() clears only the targeted key
        deduper.remove("A")
        assertTrue(deduper.tryStart("A", 5_000L))
        assertFalse(deduper.tryStart("B", 5_000L))
    }
}
