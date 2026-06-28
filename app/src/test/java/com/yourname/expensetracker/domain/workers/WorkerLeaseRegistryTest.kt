package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.TimeProvider
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
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = System.currentTimeMillis()
    }
    private lateinit var registry: WorkerLeaseRegistryImpl

    @Before
    fun setup() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { writeBarrier.checkWritesAllowed(any<DatabaseAccessOperation>()) } returns Unit
        registry = WorkerLeaseRegistryImpl(writeBarrier, timeProvider)
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

    // ── PR6A: New lease registry hardening tests ──────────────────

    @Test
    fun `acquire after stop request is rejected`() = runTest {
        registry.requestStopAll("backup starting")

        assertThrows(LeaseAcquisitionBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { registry.acquire("data_retention") }
        }
    }

    @Test
    fun `drain does not miss late acquire`() = runTest {
        // Use a virtual time provider so awaitNoActiveWorkers cooperates with runTest's virtual time
        val scheduler = testScheduler
        val virtualTimeProvider = object : TimeProvider {
            override fun now(): Long = scheduler.currentTime
        }
        val virtualRegistry = WorkerLeaseRegistryImpl(writeBarrier, virtualTimeProvider)

        // Start with an active lease so drain won't finish immediately
        val existingLease = virtualRegistry.acquire("data_retention")

        // Start a drain — it will spin in the background
        var drainResult: Boolean? = null
        val drainJob = launch {
            drainResult = virtualRegistry.requestStopAndAwaitDrain("backup", timeoutMs = 10_000)
        }

        // Advance time past the timeout so drain completes (it sees the existing lease and times out)
        advanceTimeBy(10_500)

        // Now drain has completed (because existing lease hasn't been released yet, it timed out)
        // But stop has been requested, so late acquire should be rejected
        val ex = assertThrows(LeaseAcquisitionBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { virtualRegistry.acquire("late_worker") }
        }
        assertTrue(ex.message!!.contains("stop requested"))

        existingLease.close()
    }

    @Test
    fun `concurrent acquire and request stop is safe`() = runTest {
        // Acquire on one "thread" and request stop on another should not corrupt state
        var lease1: WorkerLease? = null
        val acquiredSuccessfully = mutableListOf<String>()

        val job1 = launch {
            try {
                lease1 = registry.acquire("worker_a")
                acquiredSuccessfully.add("worker_a")
            } catch (e: LeaseAcquisitionBlockedException) {
                // Either outcome is acceptable — we just need no crash/corruption
            }
        }
        val job2 = launch {
            try {
                registry.acquire("worker_b")
                acquiredSuccessfully.add("worker_b")
            } catch (e: LeaseAcquisitionBlockedException) {
                // OK
            }
        }
        val job3 = launch {
            registry.requestStopAll("concurrent test")
        }

        job1.join()
        job2.join()
        job3.join()

        // Verify the registry is in a consistent state
        val activeCount = registry.activeLeaseCount()
        assertTrue("active leases should be 0, 1, or 2 (consistent state)", activeCount in 0..2)

        // If any leases were acquired, they should be releasable without error
        lease1?.close()
    }

    @Test
    fun `release removes primary and secondary indexes`() = runTest {
        val lease = registry.acquire("receipt_matching")

        // Primary index has the lease
        assertEquals(1, registry.activeLeaseCount())

        // Secondary index has the worker name entry
        assertTrue(
            "Secondary index should contain receipt_matching",
            registry.workerNameIndex.containsKey("receipt_matching")
        )
        val idsBefore = registry.workerNameIndex["receipt_matching"] ?: emptySet()
        assertEquals(1, idsBefore.size)

        lease.close()

        // Primary index empty
        assertEquals(0, registry.activeLeaseCount())

        // Secondary index should have removed the entry
        // (ids becomes empty → entry removed)
        assertFalse(
            "Secondary index should not contain receipt_matching after release",
            registry.workerNameIndex.containsKey("receipt_matching")
        )
    }

    @Test
    fun `reset stop flag allows future acquire`() = runTest {
        // Stop all
        registry.requestStopAll("maintenance")

        // Acquire should be blocked
        assertThrows(LeaseAcquisitionBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { registry.acquire("data_retention") }
        }

        // Reset
        registry.resetStopFlag()

        // Now acquire should succeed
        val lease = registry.acquire("data_retention")
        assertEquals(1, registry.activeLeaseCount())
        lease.close()
    }
}

/** Test helper — exposes active lease count without adding it to the public interface. */
private fun WorkerLeaseRegistryImpl.activeLeaseCount(): Int =
    this.activeLeases.size
