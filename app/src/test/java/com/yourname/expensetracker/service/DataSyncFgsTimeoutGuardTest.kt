package com.yourname.expensetracker.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DataSyncFgsTimeoutGuardTest {

    private var now = 10_000_000L

    private val prefs by lazy {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun guard() = DataSyncFgsTimeoutGuard(prefs) { now }

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `not blocked when no timeout was recorded`() {
        assertFalse(guard().isPromotionBlocked())
    }

    @Test
    fun `block window boundary - blocked immediately and just before 24 hours, unblocked at exactly 24 hours`() {
        // name, elapsed ms since the recorded timeout, expected promotion-blocked
        val rows = listOf(
            Triple("immediately after record", 0L, true),
            Triple("just before 24 hours", DataSyncFgsTimeoutGuard.BLOCK_WINDOW_MS - 1, true),
            Triple("exactly 24 hours", DataSyncFgsTimeoutGuard.BLOCK_WINDOW_MS, false)
        )
        assertTrue("size guard", rows.size == 3)
        for ((name, elapsed, expectedBlocked) in rows) {
            prefs.edit().clear().commit()
            now = 10_000_000L
            val recordedAt = now
            val guard = guard()
            guard.recordTimeout()
            assertEquals(
                "row '$name' stored elapsed timestamp",
                recordedAt,
                prefs.getLong(DataSyncFgsTimeoutGuard.KEY_TIMEOUT_ELAPSED_MS, -1L)
            )
            now += elapsed
            assertEquals("row '$name' isPromotionBlocked", expectedBlocked, guard.isPromotionBlocked())
        }
    }

    @Test
    fun `reboot unblocks because stored elapsed time is in the future`() {
        val guard = guard()
        guard.recordTimeout()
        // Elapsed-realtime clock restarts from a small value after a reboot.
        now = 5_000L
        assertFalse(guard.isPromotionBlocked())
    }

    @Test
    fun `stale timestamp re-blocks after reboot once new uptime reaches it and clear prevents that`() {
        val guard = guard()
        guard.recordTimeout()
        val stored = now
        // Reboot: clock restarts small, so the stored value is in the future (unblocked).
        now = 5_000L
        assertFalse(guard.isPromotionBlocked())
        // New boot's uptime later reaches the stale stored value: without clear() it re-blocks.
        now = stored + 1_000L
        assertTrue(guard.isPromotionBlocked())
        // BootReceiver calls clear() on BOOT_COMPLETED, so a fresh boot never sees the stale value.
        now = 5_000L
        guard.clear()
        now = stored + 1_000L
        assertFalse(guard.isPromotionBlocked())
        assertFalse(prefs.contains(DataSyncFgsTimeoutGuard.KEY_TIMEOUT_ELAPSED_MS))
    }

    @Test
    fun `clear removes a recorded timeout`() {
        val guard = guard()
        guard.recordTimeout()
        assertTrue(guard.isPromotionBlocked())
        guard.clear()
        assertFalse(guard.isPromotionBlocked())
    }

    @Test
    fun `block survives a new guard instance over the same preferences`() {
        guard().recordTimeout()
        now += 1_000L
        assertTrue(guard().isPromotionBlocked())
    }

    @Test
    fun `from context uses the dedicated preferences file`() {
        val context = RuntimeEnvironment.getApplication()
        DataSyncFgsTimeoutGuard.from(context).recordTimeout()
        assertTrue(prefs.contains(DataSyncFgsTimeoutGuard.KEY_TIMEOUT_ELAPSED_MS))
        assertTrue(DataSyncFgsTimeoutGuard.from(context).isPromotionBlocked())
    }
}
