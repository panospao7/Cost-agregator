package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R27-S1: [DatabaseWriteBarrier.runWrite] counts in-flight writes so maintenance entry can
 * await them. Real barrier over a mocked [RestoreMaintenanceMode] whose mode is switchable.
 */
class DatabaseWriteBarrierInFlightTest {

    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private val mode = mockk<RestoreMaintenanceMode>(relaxed = true).also {
        every { it.currentMode() } answers { currentMode }
        every { it.isWritesAllowed() } answers { currentMode == RestoreMaintenanceMode.Mode.NORMAL }
    }
    private val barrier = DatabaseWriteBarrier(mode)
    private val op = DatabaseAccessOperation("test_write")

    @Test
    fun drain_waits_for_gated_write_and_returns_true_when_gate_opens() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val writer = launch {
            barrier.runWrite(op) {
                started.complete(Unit)
                gate.await()
            }
        }
        started.await()
        assertEquals(1, barrier.inFlightWriteCount())
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        var drainResult: Boolean? = null
        val drain = launch { drainResult = barrier.awaitNoInFlightWrites(10_000L) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("drain must still be waiting", null, drainResult)

        gate.complete(Unit)
        writer.join()
        drain.join()
        assertEquals(true, drainResult)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun writer_starting_after_maintenance_entered_is_blocked_and_count_returns_to_zero() = runTest {
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        var ran = false
        try {
            barrier.runWrite(op) { ran = true }
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertEquals(DatabaseAccessType.WRITE, e.accessType)
        }
        assertFalse("blocked writer must not run its block", ran)
        assertEquals(0, barrier.inFlightWriteCount())
        assertTrue(barrier.awaitNoInFlightWrites(10_000L))
    }

    @Test
    fun nested_runWrite_counts_once_and_does_not_deadlock() = runTest {
        var outerCount = -1
        var nestedCount = -1
        val result = barrier.runWrite(op) {
            outerCount = barrier.inFlightWriteCount()
            barrier.runWrite(DatabaseAccessOperation("nested_write")) {
                nestedCount = barrier.inFlightWriteCount()
                42
            }
        }
        assertEquals(42, result)
        assertEquals(1, outerCount)
        assertEquals(1, nestedCount)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun nested_runWrite_is_still_blocked_when_maintenance_entered_mid_write() = runTest {
        try {
            barrier.runWrite(op) {
                currentMode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
                barrier.runWrite(DatabaseAccessOperation("nested_write")) { }
            }
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertEquals("nested_write", e.operation.name)
        }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun drain_timeout_returns_false_after_virtual_ten_seconds() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val writer = launch {
            barrier.runWrite(op) {
                started.complete(Unit)
                gate.await()
            }
        }
        started.await()

        var drainResult: Boolean? = null
        val drain = launch {
            drainResult = barrier.awaitNoInFlightWrites(DatabaseWriteBarrier.WRITE_DRAIN_TIMEOUT_MS)
        }
        advanceTimeBy(DatabaseWriteBarrier.WRITE_DRAIN_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(null, drainResult)
        advanceTimeBy(2)
        runCurrent()
        drain.join()
        assertEquals(false, drainResult)
        assertEquals("timed-out write is still counted", 1, barrier.inFlightWriteCount())

        gate.complete(Unit)
        writer.join()
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun exception_inside_block_returns_count_to_zero() = runTest {
        try {
            barrier.runWrite(op) { throw IllegalArgumentException("boom") }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("boom", e.message)
        }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun cancellation_inside_block_returns_count_to_zero() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val writer = launch {
            try {
                barrier.runWrite(op) {
                    started.complete(Unit)
                    gate.await()
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        started.await()
        assertEquals(1, barrier.inFlightWriteCount())

        writer.cancel()
        writer.join()
        assertTrue(cancelled)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun awaitNoInFlightWrites_inside_runWrite_throws_reentrant() = runTest {
        try {
            barrier.runWrite(op) { barrier.awaitNoInFlightWrites(10_000L) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("MAINTENANCE_DRAIN_REENTRANT", e.message)
        }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun mode_switched_between_increment_and_check_rejects_writer_and_leaves_no_count() = runTest {
        // The mode flips to maintenance exactly when the barrier performs its check, i.e. after
        // the increment. The writer must have been counted at that point (so a drain that began
        // concurrently could not miss it) and must be uncounted again once rejected.
        var countAtCheck = -1
        every { mode.currentMode() } answers {
            countAtCheck = barrier.inFlightWriteCount()
            currentMode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
            currentMode
        }
        var ran = false
        val failure = try {
            barrier.runWrite(op) { ran = true }
            null
        } catch (e: DatabaseAccessBlockedException) {
            e
        }
        assertNotNull("writer must be rejected", failure)
        assertFalse(ran)
        assertEquals("writer must be counted before the mode check", 1, countAtCheck)
        assertEquals(0, barrier.inFlightWriteCount())
        assertTrue(barrier.awaitNoInFlightWrites(10_000L))
    }
}
