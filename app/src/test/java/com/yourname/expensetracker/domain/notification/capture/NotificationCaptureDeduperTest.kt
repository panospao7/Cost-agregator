package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NotificationCaptureDeduper] window/TTL semantics on the injected
 * monotonic time source ([FakeMonotonicTimeProvider]).
 *
 * Return-value semantics (verified against source): [NotificationCaptureDeduper.tryStart]
 * returns **true when the key is SUPPRESSED (duplicate within window)** and
 * **false when the key is ADMITTED**.
 *
 * ## Clock-jump regression pin
 *
 * Every test drives the deduper exclusively through fake nanos values that are
 * wildly inconsistent with any real wall-clock epoch (~1.7e12 ms). With the old
 * wall-clock `TimeProvider`, `now` would be ~1.7e12 ms while stored `last`
 * values stay in the fake's tiny range, so `(now - last)` becomes hugely
 * positive (window void: everything admitted) or — after a real backward
 * wall-clock jump (NTP sync, user clock change) — hugely negative (everything
 * suppressed forever). Any test here asserting suppression therefore fails if
 * the deduper is ever reverted from [com.yourname.expensetracker.domain.util.MonotonicTimeProvider]
 * to wall time.
 */
class NotificationCaptureDeduperTest {

    private val fake = FakeMonotonicTimeProvider()
    private val deduper = NotificationCaptureDeduper(fake)

    @Test
    fun `tryStart admits first occurrence and suppresses immediate duplicate`() {
        // false = admitted
        assertFalse(deduper.tryStart("k", 5000L))
        // true = suppressed (duplicate within window)
        assertTrue(deduper.tryStart("k", 5000L))
        assertEquals(1, deduper.size)
    }

    @Test
    fun `window boundary is strict less-than - windowMs minus one suppressed, windowMs admitted`() {
        assertFalse(deduper.tryStart("k", 5000L)) // last = 0ms

        fake.advanceMillis(4999L) // now - last = 4999 < 5000
        assertTrue(deduper.tryStart("k", 5000L)) // still suppressed

        // Suppressed call must not refresh the stored timestamp, so last is
        // still 0ms; one more ms makes now - last == 5000, which is NOT < 5000.
        fake.advanceMillis(1L)
        assertFalse(deduper.tryStart("k", 5000L)) // admitted at the exact boundary
    }

    @Test
    fun `suppressed duplicate does not extend the window`() {
        assertFalse(deduper.tryStart("k", 5000L)) // last = 0ms
        fake.advanceMillis(4000L)
        assertTrue(deduper.tryStart("k", 5000L)) // suppressed; last unchanged
        fake.advanceMillis(1000L) // now - last = 5000 since original admission
        assertFalse(deduper.tryStart("k", 5000L)) // admitted; window did not slide
    }

    @Test
    fun `different keys are independent`() {
        assertFalse(deduper.tryStart("A", 5000L))
        assertTrue(deduper.tryStart("A", 5000L)) // A suppressed
        assertFalse(deduper.tryStart("B", 5000L)) // B unaffected by A
        assertTrue(deduper.tryStart("B", 5000L))
        assertEquals(2, deduper.size)
    }

    /**
     * Monotonic-source proof: the fake is pinned at 5s-since-origin
     * (5_000_000_000 ns — the deduper stores and compares nanos internally;
     * only the public window parameters stay in millis), a value wildly
     * inconsistent with the real wall clock (~1.7e12 ms). With the old
     * wall-clock TimeProvider, `now` would be
     * ~1.7e12 ms while `last` stays fake-derived, so `(now - last)` would be
     * hugely positive (window void) or hugely negative after a backward wall
     * jump (suppressed forever). This test fails if the deduper is reverted
     * to wall time. Cleanup is also driven by the same tiny fake values.
     */
    @Test
    fun `window and cleanup follow the monotonic fake, not the wall clock`() {
        fake.setNanos(5_000_000_000L) // 5 s since origin -> 5 ms

        assertFalse(deduper.tryStart("k", 5000L)) // admitted at fake now = 5ms
        fake.advanceMillis(4999L)
        assertTrue(deduper.tryStart("k", 5000L)) // suppressed within window
        fake.advanceMillis(1L)
        assertFalse(deduper.tryStart("k", 5000L)) // admitted at exact boundary

        // Cleanup follows the same fake source: the entry is now 10ms old,
        // exceeding maxAgeMs = 5.
        fake.advanceMillis(10L) // now = 15ms
        deduper.cleanupExpired(5L)
        assertEquals(0, deduper.size)
    }

    /**
     * A real monotonic source can never go backward; this test simulates an
     * impossible-by-contract backward read to pin fail-safe behavior: the
     * deduper must treat the resulting negative elapsed delta as "within
     * window" (suppress) rather than throwing or corrupting stored state,
     * and must admit again once time moves forward past the window.
     */
    @Test
    fun `backward monotonic read is fail-safe - suppresses without corrupting state`() {
        fake.setNanos(10_000_000_000L) // now = 10_000 ms
        assertFalse(deduper.tryStart("k", 5000L)) // last = 10_000

        fake.setNanos(5_000_000_000L) // impossible backward read -> now = 5_000
        assertTrue(deduper.tryStart("k", 5000L)) // (5_000 - 10_000) < 5_000 -> fail-safe suppress
        assertEquals(1, deduper.size) // suppressed call must not mutate state

        fake.setNanos(15_000_000_000L) // now = 15_000 -> (15_000 - 10_000) == window
        assertFalse(deduper.tryStart("k", 5000L)) // admitted again
    }

    @Test
    fun `cleanupExpired removes entries older than maxAge and keeps the rest`() {
        assertFalse(deduper.tryStart("old", 60_000L)) // last = 0ms
        assertFalse(deduper.tryStart("fresh", 60_000L)) // last = 0ms
        fake.advanceMillis(1000L)
        assertFalse(deduper.tryStart("mid", 60_000L)) // last = 1000ms

        deduper.cleanupExpired(5000L) // now = 1000ms: nothing exceeds maxAge
        assertEquals(3, deduper.size)

        fake.advanceMillis(5000L) // now = 6000ms
        deduper.cleanupExpired(5000L)
        // old/fresh: 6000 - 0 = 6000 > 5000 -> removed.
        // mid: 6000 - 1000 = 5000, NOT > 5000 -> kept (strict > boundary pin).
        assertEquals(1, deduper.size)

        fake.advanceMillis(1L) // now = 6001ms: mid is now 5001 > 5000
        deduper.cleanupExpired(5000L)
        assertEquals(0, deduper.size)
    }

    @Test
    fun `remove allows the key to be admitted again and unknown key is a safe no-op`() {
        assertFalse(deduper.tryStart("k", 5000L))
        assertTrue(deduper.tryStart("k", 5000L))

        deduper.remove("k")
        assertFalse(deduper.tryStart("k", 5000L)) // admitted again after remove

        deduper.remove("does-not-exist") // must be a safe no-op
        assertEquals(1, deduper.size)
    }

    @Test
    fun `evicts least-recently-used entry beyond MAX_ENTRIES of 1000`() {
        // MAX_ENTRIES = 1000 (private const in NotificationCaptureDeduper).
        for (i in 0 until 1000) {
            assertFalse(deduper.tryStart("k$i", 60_000L))
        }
        assertEquals(1000, deduper.size)

        // One more admit pushes size over MAX_ENTRIES; the access-order map
        // evicts "k0" (least recently used).
        assertFalse(deduper.tryStart("k1000", 60_000L))
        assertEquals(1000, deduper.size)

        // "k0" was evicted -> admitted again.
        assertFalse(deduper.tryStart("k0", 60_000L))
        // A still-cached recent key remains suppressed.
        assertTrue(deduper.tryStart("k1000", 60_000L))
    }
}
