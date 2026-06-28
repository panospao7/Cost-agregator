package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerLeaseRegistryTest {

    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private lateinit var registry: WorkerLeaseRegistryImpl

    @Before
    fun setup() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { writeBarrier.checkWritesAllowed(any<DatabaseAccessOperation>()) } returns Unit
        registry = WorkerLeaseRegistryImpl(writeBarrier)
    }

    // ── restore_waits_for_running_worker_to_stop ──────────────────

    @Test
    fun restore_waits_for_running_worker_to_stop() = runTest {
        val lease = registry.acquire("data_retention")

        // Drain should time out while lease is held
        val drained = registry.awaitNoActiveWorkers(timeoutMs = 100)
        assertFalse("Should not drain while lease is held", drained)

        lease.close()

        // Now drain should succeed immediately
        val drainedAfter = registry.awaitNoActiveWorkers(timeoutMs = 100)
        assertTrue("Should drain after lease released", drainedAfter)
    }

    // ── backup_waits_for_data_retention_worker_to_stop ────────────

    @Test
    fun backup_waits_for_data_retention_worker_to_stop() = runTest {
        val lease = registry.acquire("data_retention")
        assertFalse(registry.awaitNoActiveWorkers(100))
        lease.close()
        assertTrue(registry.awaitNoActiveWorkers(100))
    }

    // ── cancelled_worker_releases_lease ──────────────────────────

    @Test
    fun cancelled_worker_releases_lease() = runTest {
        val lease = registry.acquire("receipt_matching")
        assertEquals(1, registry.activeLeaseCount())

        lease.close()
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun lease_close_is_idempotent() = runTest {
        val lease = registry.acquire("receipt_matching")
        lease.close()
        lease.close() // second close must not throw or double-decrement
        assertEquals(0, registry.activeLeaseCount())
    }

    // ── worker_checkpoint_blocks_mutation_after_restore_starts ────

    @Test
    fun worker_checkpoint_throws_when_stop_requested() = runTest {
        val lease = registry.acquire("location_backfill")
        registry.requestStopAll("restore started")

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("updateLocation") }
        }

        lease.close()
    }

    @Test
    fun worker_checkpoint_throws_when_write_barrier_blocks() = runTest {
        val mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        val op = DatabaseAccessOperation("updateLocation")
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(DatabaseAccessType.WRITE, op, mode)

        val lease = registry.acquire("location_backfill")

        assertThrows(DatabaseAccessBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("updateLocation") }
        }

        lease.close()
    }

    @Test
    fun worker_checkpoint_passes_in_normal_mode() = runTest {
        val lease = registry.acquire("location_backfill")
        lease.checkpoint("updateLocation") // must not throw
        lease.close()
    }

    // ── requestStopAndAwaitDrain ──────────────────────────────────

    @Test
    fun requestStopAndAwaitDrain_returns_true_when_no_active_workers() = runTest {
        val drained = registry.requestStopAndAwaitDrain("backup", timeoutMs = 200)
        assertTrue(drained)
    }

    @Test
    fun requestStopAndAwaitDrain_sets_stop_flag() = runTest {
        registry.requestStopAndAwaitDrain("restore", timeoutMs = 50)
        assertTrue(registry.isStopRequested())
    }

    @Test
    fun resetStopFlag_clears_stop_request() = runTest {
        registry.requestStopAll("test")
        assertTrue(registry.isStopRequested())
        registry.resetStopFlag()
        assertFalse(registry.isStopRequested())
    }

    // ── multiple workers ──────────────────────────────────────────

    @Test
    fun multiple_leases_all_must_release_before_drain() = runTest {
        val lease1 = registry.acquire("data_retention")
        val lease2 = registry.acquire("receipt_matching")

        assertFalse(registry.awaitNoActiveWorkers(100))

        lease1.close()
        assertFalse(registry.awaitNoActiveWorkers(100)) // lease2 still held

        lease2.close()
        assertTrue(registry.awaitNoActiveWorkers(100))
    }

    // ── PR2: same-name acquisitions create distinct leases ─────────

    @Test
    fun same_name_acquire_creates_distinct_leases() = runTest {
        // PR2-FIX: concurrent same-name workers must each have their own lease,
        // so that close() of one does not accidentally remove the other's lease.
        val lease1 = registry.acquire("receipt_matching")
        val lease2 = registry.acquire("receipt_matching")

        // Two distinct leases, not collapsed into one
        assertEquals(2, registry.activeLeaseCount())

        lease1.close()
        assertEquals(1, registry.activeLeaseCount()) // lease2 still active

        lease2.close()
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun drain_sees_all_concurrent_same_name_workers() = runTest {
        val lease1 = registry.acquire("receipt_matching")
        val lease2 = registry.acquire("receipt_matching")

        // Drain should timeout while both leases are held
        assertFalse(registry.awaitNoActiveWorkers(100))

        lease1.close()
        // Still one lease active
        assertFalse(registry.awaitNoActiveWorkers(100))

        lease2.close()
        // Now fully drained
        assertTrue(registry.awaitNoActiveWorkers(100))
    }
}

/** Test helper — exposes active lease count without adding it to the public interface. */
private fun WorkerLeaseRegistryImpl.activeLeaseCount(): Int =
    this.activeLeases.size
