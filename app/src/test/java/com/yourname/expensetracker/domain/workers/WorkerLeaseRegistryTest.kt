package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerLeaseRegistryTest {

    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val dispatcher = StandardTestDispatcher()
    private val monotonicTimeProvider = object : MonotonicTimeProvider {
        override fun nowNanos(): Long = dispatcher.scheduler.currentTime * 1_000_000L
    }
    private lateinit var registry: WorkerLeaseRegistryImpl

    @Before
    fun setup() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { writeBarrier.checkWritesAllowed(any<DatabaseAccessOperation>()) } returns Unit
        registry = WorkerLeaseRegistryImpl(writeBarrier, monotonicTimeProvider)
    }

    // ── restore_waits_for_running_worker_to_stop ──────────────────

    @Test
    fun restore_waits_for_running_worker_to_stop() = runTest(dispatcher) {
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
    fun backup_waits_for_data_retention_worker_to_stop() = runTest(dispatcher) {
        val lease = registry.acquire("data_retention")
        assertFalse(registry.awaitNoActiveWorkers(100))
        lease.close()
        assertTrue(registry.awaitNoActiveWorkers(100))
    }

    // ── cancelled_worker_releases_lease ──────────────────────────

    @Test
    fun cancelled_worker_releases_lease() = runTest(dispatcher) {
        val lease = registry.acquire("receipt_matching")
        assertEquals(1, registry.activeLeaseCount())

        lease.close()
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun lease_close_is_idempotent() = runTest(dispatcher) {
        val lease = registry.acquire("receipt_matching")
        lease.close()
        lease.close() // second close must not throw or double-decrement
        assertEquals(0, registry.activeLeaseCount())
    }

    // ── worker_checkpoint_blocks_mutation_after_restore_starts ────

    @Test
    fun worker_checkpoint_throws_when_stop_requested() = runTest(dispatcher) {
        val lease = registry.acquire("location_backfill")
        registry.requestStopAll("restore started")

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("updateLocation") }
        }

        lease.close()
    }

    @Test
    fun worker_checkpoint_throws_when_write_barrier_blocks() = runTest(dispatcher) {
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
    fun worker_checkpoint_passes_in_normal_mode() = runTest(dispatcher) {
        val lease = registry.acquire("location_backfill")
        lease.checkpoint("updateLocation") // must not throw
        lease.close()
    }

    // ── requestStopAndAwaitDrain ──────────────────────────────────

    @Test
    fun requestStopAndAwaitDrain_returns_true_when_no_active_workers() = runTest(dispatcher) {
        val drained = registry.requestStopAndAwaitDrain("backup", timeoutMs = 200)
        assertTrue(drained)
    }

    @Test
    fun requestStopAndAwaitDrain_sets_stop_flag() = runTest(dispatcher) {
        registry.requestStopAndAwaitDrain("restore", timeoutMs = 50)
        assertTrue(registry.isStopRequested())
    }

    @Test
    fun resetStopFlag_clears_stop_request() = runTest(dispatcher) {
        registry.requestStopAll("test")
        assertTrue(registry.isStopRequested())
        registry.resetStopFlag()
        assertFalse(registry.isStopRequested())
    }

    // ── multiple workers ──────────────────────────────────────────

    @Test
    fun multiple_leases_all_must_release_before_drain() = runTest(dispatcher) {
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
    fun same_name_acquire_creates_distinct_leases() = runTest(dispatcher) {
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
    fun drain_sees_all_same_name_leases_held_in_sequence() = runTest(dispatcher) {
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
    fun `acquire after stop request is rejected`() = runTest(dispatcher) {
        registry.requestStopAll("backup starting")

        assertThrows(LeaseAcquisitionBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { registry.acquire("data_retention") }
        }
    }

    @Test
    fun `drain does not miss late acquire`() = runTest(dispatcher) {
        // Use a monotonic clock tied to runTest's virtual time.
        val scheduler = testScheduler
        val virtualTimeProvider = object : MonotonicTimeProvider {
            override fun nowNanos(): Long = scheduler.currentTime * 1_000_000L
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

    /**
     * NOT a concurrency test: StandardTestDispatcher runs the three jobs strictly
     * in launch order on one thread, so the outcome is deterministic. The real
     * multi-threaded contention tests are the `real threads` tests at the end of
     * this class.
     */
    @Test
    fun `sequential acquires before a stop request all succeed and release cleanly`() = runTest(dispatcher) {
        val leases = mutableListOf<WorkerLease>()
        val acquiredSuccessfully = mutableListOf<String>()
        val blocked = mutableListOf<String>()

        val job1 = launch {
            try {
                leases.add(registry.acquire("worker_a"))
                acquiredSuccessfully.add("worker_a")
            } catch (e: LeaseAcquisitionBlockedException) {
                blocked.add("worker_a")
            }
        }
        val job2 = launch {
            try {
                leases.add(registry.acquire("worker_b"))
                acquiredSuccessfully.add("worker_b")
            } catch (e: LeaseAcquisitionBlockedException) {
                blocked.add("worker_b")
            }
        }
        val job3 = launch {
            registry.requestStopAll("sequential test")
        }

        job1.join()
        job2.join()
        job3.join()

        // Launch order a, b, stop => both acquires precede the stop request.
        assertEquals(listOf("worker_a", "worker_b"), acquiredSuccessfully)
        assertTrue(blocked.isEmpty())
        assertEquals(2, registry.activeLeaseCount())
        assertTrue(registry.isStopRequested())

        // Pre-existing leases remain releasable after a stop request.
        leases.forEach { it.close() }
        assertEquals(0, registry.activeLeaseCount())
        assertTrue(registry.workerNameIndex.isEmpty())
    }

    @Test
    fun `release removes primary and secondary indexes`() = runTest(dispatcher) {
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
    fun `reset stop flag allows future acquire`() = runTest(dispatcher) {
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

    @Test
    fun `drain budget ignores forward and backward wall clock jumps`() = runTest(dispatcher) {
        for (jumpMs in listOf(-86_400_000L, 86_400_000L)) {
            val clock = object : TimeProvider, MonotonicTimeProvider {
                var wallTimeMs = 1_700_000_000_000L
                override fun now(): Long = wallTimeMs
                override fun nowNanos(): Long = testScheduler.currentTime * 1_000_000L
            }
            val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, clock)
            val lease = localRegistry.acquire("data_retention")
            val drain = async { localRegistry.awaitNoActiveWorkers(100) }
            runCurrent()

            clock.wallTimeMs += jumpMs
            assertEquals(1_700_000_000_000L + jumpMs, clock.now())
            advanceTimeBy(99)
            runCurrent()
            assertFalse("Wall-clock jump must not end the drain early", drain.isCompleted)

            advanceTimeBy(1)
            runCurrent()
            assertFalse("Drain must expire at its elapsed-time budget", drain.await())
            assertEquals(1, localRegistry.activeLeaseCount())
            lease.close()
        }
    }

    @Test
    fun `short drain budget is not rounded up to polling interval`() = runTest(dispatcher) {
        val lease = registry.acquire("data_retention")
        val startedAt = testScheduler.currentTime

        assertFalse(registry.awaitNoActiveWorkers(7))

        assertEquals(7L, testScheduler.currentTime - startedAt)
        assertEquals(1, registry.activeLeaseCount())
        lease.close()
    }

    @Test
    fun `release before deadline lets drain complete`() = runTest(dispatcher) {
        val lease = registry.acquire("data_retention")
        launch {
            delay(25)
            lease.close()
        }

        assertTrue(registry.awaitNoActiveWorkers(100))
        assertEquals(50L, testScheduler.currentTime)
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun `nonpositive budgets only succeed for an empty registry`() = runTest(dispatcher) {
        assertTrue(registry.awaitNoActiveWorkers(0))
        assertTrue(registry.awaitNoActiveWorkers(-1))
        val lease = registry.acquire("data_retention")

        assertFalse(registry.awaitNoActiveWorkers(0))
        assertFalse(registry.awaitNoActiveWorkers(-1))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, registry.activeLeaseCount())
        lease.close()
    }

    @Test
    fun `cancelling drain propagates without releasing worker lease`() = runTest(dispatcher) {
        val lease = registry.acquire("data_retention")
        var returnedNormally = false
        val drain = launch {
            registry.awaitNoActiveWorkers(1_000)
            returnedNormally = true
        }
        runCurrent()

        drain.cancelAndJoin()

        assertTrue(drain.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(1, registry.activeLeaseCount())
        lease.close()
    }

    @Test
    fun `elapsed drain budget tolerates signed monotonic counter wrap`() = runTest(dispatcher) {
        val origin = Long.MAX_VALUE - 25_000_000L
        val clock = object : MonotonicTimeProvider {
            override fun nowNanos(): Long = origin + testScheduler.currentTime * 1_000_000L
        }
        val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, clock)
        val lease = localRegistry.acquire("data_retention")

        assertFalse(localRegistry.awaitNoActiveWorkers(100))

        assertEquals(100L, testScheduler.currentTime)
        assertEquals(1, localRegistry.activeLeaseCount())
        lease.close()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Real multi-threaded contention tests (fixed thread pool, start latch,
    // every test bounded by withTimeout so a deadlock FAILS instead of hanging).
    //
    // Production invariant owners (WorkerLeaseRegistryImpl):
    //  - acquire(): `synchronized(lock)` registers the lease in BOTH activeLeases and
    //    workerNameIndex and re-checks stopRequested under the same lock.
    //  - requestStopAll(): sets stopRequested under the same lock.
    //  - WorkerLeaseImpl.close(): CAS on `released`, then removes from both maps under the lock.
    // ══════════════════════════════════════════════════════════════════════

    private val realClock = object : MonotonicTimeProvider {
        override fun nowNanos(): Long = System.nanoTime()
    }

    /**
     * All N workers acquire at the same instant (start latch). While every lease is held
     * we take a snapshot: each acquire must have produced a distinct lease, registered in
     * the primary AND the secondary index (no lost/duplicated registration). Then every
     * lease is closed concurrently (half of them twice, to race idempotent close) and the
     * registry must be exactly empty: acquisitions == releases, no lost release.
     *
     * Kills: acquire registering only one of the two indexes, a non-unique lease id,
     * close() that fails to remove from either index, a lost release. (Idempotent close is
     * only smoke-tested here via the double close; the double close is not claimed as a kill
     * for a non-idempotent close.)
     */
    @Test
    fun `real threads - simultaneous acquire then concurrent double close leaves registry exactly empty`() {
        val names = listOf("worker_a", "worker_b", "worker_c")
        val workers = 32
        repeat(20) { iteration ->
            val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, realClock)
            runOnRealThreads(threads = 8) { pool ->
                val ready = AtomicInteger(0)
                val held = AtomicInteger(0)
                val released = AtomicInteger(0)
                val go = CompletableDeferred<Unit>()
                val allHeld = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val leaseIds = ConcurrentLinkedQueue<String>()

                val jobs = (0 until workers).map { i ->
                    launch(pool) {
                        ready.incrementAndGet()
                        go.await()
                        val lease = localRegistry.acquire(names[i % names.size])
                        leaseIds.add(lease.leaseId)
                        if (held.incrementAndGet() == workers) allHeld.complete(Unit)
                        release.await()
                        lease.close()
                        if (i % 2 == 0) lease.close() // idempotent double close, racing the others
                        released.incrementAndGet()
                    }
                }

                while (ready.get() < workers) yield()
                go.complete(Unit)
                allHeld.await()

                // Snapshot while all leases are held (nothing is closing yet).
                assertEquals("iteration $iteration: one lease per acquire", workers, localRegistry.activeLeaseCount())
                assertEquals("iteration $iteration: lease ids unique", workers, leaseIds.toSet().size)
                assertEquals(leaseIds.toSet(), localRegistry.activeLeases.keys.toSet())
                val indexed = localRegistry.workerNameIndex.values.flatMap { it.toList() }
                assertEquals("iteration $iteration: secondary index holds every lease once", workers, indexed.size)
                assertEquals(leaseIds.toSet(), indexed.toSet())
                names.forEachIndexed { n, name ->
                    val expected = (0 until workers).count { it % names.size == n }
                    assertEquals(expected, localRegistry.workerNameIndex[name]?.size)
                }
                assertFalse("not drained while all held", localRegistry.awaitNoActiveWorkers(0))

                release.complete(Unit)
                jobs.joinAll()

                assertEquals(workers, released.get())
                assertEquals("iteration $iteration: no lost release", 0, localRegistry.activeLeaseCount())
                assertTrue("iteration $iteration: secondary index empty", localRegistry.workerNameIndex.isEmpty())
                assertTrue(localRegistry.awaitNoActiveWorkers(0))
            }
        }
    }

    /**
     * Acquire/close churn on ONE worker name from several threads. While a lease is held it
     * must always be visible in the secondary index. Without the registry lock this can fail:
     * acquire's `computeIfAbsent(name).add(id)` can add to a set that a concurrent close()
     * has just emptied and removed from the map (orphaned set), so the live lease is missing
     * from workerNameIndex. Fixed round counts, no sleeps.
     *
     * Determinism: correct code passes deterministically (every index mutation of acquire and
     * close happens under the same `synchronized(lock)`). Killing a mutant that removes
     * `synchronized(lock)` from acquire/close is PROBABILISTIC: it depends on core count and
     * machine load (the race window is tiny), and a missed interleaving makes the mutant pass.
     * No mutant run is recorded for this test.
     */
    @Test
    fun `real threads - churn on one worker name never loses a held lease from the secondary index`() {
        val workers = 8
        val rounds = 500
        repeat(5) { iteration ->
            val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, realClock)
            runOnRealThreads(threads = workers) { pool ->
                val ready = AtomicInteger(0)
                val go = CompletableDeferred<Unit>()
                val acquired = AtomicInteger(0)
                val closed = AtomicInteger(0)
                val violations = ConcurrentLinkedQueue<String>()

                val jobs = (0 until workers).map {
                    launch(pool) {
                        ready.incrementAndGet()
                        go.await()
                        repeat(rounds) {
                            val lease = localRegistry.acquire("receipt_matching")
                            acquired.incrementAndGet()
                            if (localRegistry.activeLeases[lease.leaseId] == null) {
                                violations.add("held lease missing from primary index")
                            }
                            if (localRegistry.workerNameIndex["receipt_matching"]?.contains(lease.leaseId) != true) {
                                violations.add("held lease missing from secondary index")
                            }
                            lease.close()
                            closed.incrementAndGet()
                        }
                    }
                }
                while (ready.get() < workers) yield()
                go.complete(Unit)
                jobs.joinAll()

                assertTrue("iteration $iteration: $violations", violations.isEmpty())
                assertEquals(workers * rounds, acquired.get())
                assertEquals("acquisitions == releases", acquired.get(), closed.get())
                assertEquals(0, localRegistry.activeLeaseCount())
                assertTrue(localRegistry.workerNameIndex.isEmpty())
            }
        }
    }

    /**
     * Workers acquire/close in a loop while another thread requests a stop and then drains.
     * Invariants: (1) no acquire that STARTS after requestStopAll has returned may succeed;
     * (2) every successful acquire is eventually released (acquisitions == releases);
     * (3) the drain completes (true) and the registry is empty afterwards, with no late lease.
     *
     * Kills: removing BOTH stopRequested checks from acquire() (the unlocked one and the
     * in-lock one: workers then never see the block, the loop never ends, and the bounded
     * timeout fails the test), requestStopAll not publishing the flag, a lease that is never
     * removed on close (drain would time out -> false). It does NOT kill removal of only one
     * of the two checks; the in-lock re-check is targeted by the restore-race stress test below.
     */
    @Test
    fun `real threads - acquire started after stop returned is rejected and drain sees every release`() {
        val workers = 8
        repeat(10) { iteration ->
            val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, realClock)
            runOnRealThreads(threads = workers + 1) { pool ->
                val ready = AtomicInteger(0)
                val go = CompletableDeferred<Unit>()
                val acquired = AtomicInteger(0)
                val closed = AtomicInteger(0)
                val blocked = AtomicInteger(0)
                val stopReturned = AtomicBoolean(false)
                val lateGrants = AtomicInteger(0)

                val jobs = (0 until workers).map { i ->
                    launch(pool) {
                        ready.incrementAndGet()
                        go.await()
                        while (true) {
                            val stopAlreadyReturned = stopReturned.get()
                            val lease = try {
                                localRegistry.acquire("worker_${i % 3}")
                            } catch (e: LeaseAcquisitionBlockedException) {
                                blocked.incrementAndGet()
                                break
                            }
                            acquired.incrementAndGet()
                            if (stopAlreadyReturned) lateGrants.incrementAndGet()
                            try {
                                yield()
                            } finally {
                                lease.close()
                                closed.incrementAndGet()
                            }
                        }
                    }
                }

                while (ready.get() < workers) yield()
                go.complete(Unit)
                // Let real contention build up before stopping (counter based, no sleep).
                while (acquired.get() < 50) yield()

                localRegistry.requestStopAll("maintenance")
                stopReturned.set(true)
                val drained = localRegistry.awaitNoActiveWorkers(10_000)
                jobs.joinAll()

                assertTrue("iteration $iteration: drain must succeed once workers exit", drained)
                assertEquals("iteration $iteration: no lease granted after stop returned", 0, lateGrants.get())
                assertEquals("acquisitions == releases", acquired.get(), closed.get())
                assertEquals(workers, blocked.get())
                assertEquals(0, localRegistry.activeLeaseCount())
                assertTrue(localRegistry.workerNameIndex.isEmpty())
                assertTrue(localRegistry.isStopRequested())
            }
        }
    }

    /**
     * Restore race: workers loop acquire/close in a TIGHT loop (no suspension between a close
     * and the next acquire, to maximise the time spent inside acquire) while the test thread
     * calls requestStopAll and then awaitNoActiveWorkers. The instant awaitNoActiveWorkers
     * returns true, `drainReturned` is set. Every worker checks that flag immediately after a
     * SUCCESSFUL acquire returns (before closing) and counts a violation if it is already set:
     * a lease granted after the drain declared the registry empty would let a worker mutate the
     * database during a restore.
     *
     * Targets: the in-lock re-check of `stopRequested` in acquire() (WorkerLeaseRegistryImpl
     * `acquire`, second check inside `synchronized(lock)`), and `synchronized(lock)` around the
     * flag write in requestStopAll(). Without the in-lock re-check a worker that passed the
     * unlocked check just before the flag was set can register its lease after the drain saw an
     * empty registry; without the lock in requestStopAll the flag can be set between the in-lock
     * check and the registration with the same effect.
     *
     * Determinism: CORRECT code passes deterministically. A lease is registered only inside
     * `synchronized(lock)` while the flag is still false, i.e. strictly before requestStopAll
     * takes the same lock. A worker checks `drainReturned` while it still HOLDS that lease, and
     * awaitNoActiveWorkers only returns true after observing activeLeases empty, which cannot
     * happen while that lease is held. Any acquire that starts its in-lock section after
     * requestStopAll returned sees the flag and throws. So `drainReturned` can never be set at
     * the moment a successful acquire returns. Killing the mutants above is PROBABILISTIC and
     * WEAK: only iterations whose first drain check sees an empty registry can expose these
     * mutants, and most iterations instead take the 50 ms polling path in awaitNoActiveWorkers,
     * so a mutant usually survives. Correct code passes deterministically. No mutant run is
     * recorded for this test.
     *
     * Bounded: every iteration runs under withTimeout (runOnRealThreads), workers poll
     * ensureActive() so a mutant that never blocks them is cancelled rather than hanging the
     * build, and the pool is shut down in a finally block.
     */
    @Test
    fun `real threads - restore race - no acquire completes after the drain returned true`() {
        val workers = 8
        val iterations = 20
        val totalAcquired = AtomicInteger(0)
        val totalLate = AtomicInteger(0)
        repeat(iterations) { iteration ->
            val localRegistry = WorkerLeaseRegistryImpl(writeBarrier, realClock)
            runOnRealThreads(threads = workers) { pool ->
                val ready = AtomicInteger(0)
                val go = CompletableDeferred<Unit>()
                val acquired = AtomicInteger(0)
                val closed = AtomicInteger(0)
                val blocked = AtomicInteger(0)
                val drainReturned = AtomicBoolean(false)
                val lateGrants = AtomicInteger(0)

                val jobs = (0 until workers).map { i ->
                    launch(pool) {
                        ready.incrementAndGet()
                        go.await()
                        while (true) {
                            ensureActive()
                            val lease = try {
                                localRegistry.acquire("worker_${i % 3}")
                            } catch (e: LeaseAcquisitionBlockedException) {
                                blocked.incrementAndGet()
                                break
                            }
                            // Checked while the lease is still held, before closing it.
                            if (drainReturned.get()) lateGrants.incrementAndGet()
                            acquired.incrementAndGet()
                            lease.close()
                            closed.incrementAndGet()
                        }
                    }
                }

                while (ready.get() < workers) yield()
                go.complete(Unit)
                // Let real contention build up before the restore starts (counter based, no sleep).
                while (acquired.get() < 200) yield()

                localRegistry.requestStopAll("restore")
                val drained = localRegistry.awaitNoActiveWorkers(10_000)
                if (drained) drainReturned.set(true)
                jobs.joinAll()

                assertTrue("iteration $iteration: drain must succeed once workers exit", drained)
                assertEquals(
                    "iteration $iteration: a lease was granted after the drain returned true",
                    0,
                    lateGrants.get()
                )
                assertEquals("iteration $iteration: acquisitions == releases", acquired.get(), closed.get())
                assertEquals("iteration $iteration: every worker was eventually blocked", workers, blocked.get())
                assertEquals(0, localRegistry.activeLeaseCount())
                assertTrue(localRegistry.workerNameIndex.isEmpty())
                assertTrue(localRegistry.isStopRequested())
                totalAcquired.addAndGet(acquired.get())
                totalLate.addAndGet(lateGrants.get())
            }
        }
        // Non-vacuity: real contention happened in every iteration (>= 200 grants before each stop).
        assertTrue("restore race ran with real contention", totalAcquired.get() >= iterations * 200)
        assertEquals("no acquire completed after a drain returned true over $iterations iterations", 0, totalLate.get())
    }

    private fun <T> runOnRealThreads(
        threads: Int,
        block: suspend CoroutineScope.(CoroutineDispatcher) -> T
    ): T {
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val pool = executor.asCoroutineDispatcher()
            return runBlocking { withTimeout(REAL_THREAD_TEST_TIMEOUT_MS) { block(this, pool) } }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val REAL_THREAD_TEST_TIMEOUT_MS = 60_000L
    }
}

/** Test helper — exposes active lease count without adding it to the public interface. */
private fun WorkerLeaseRegistryImpl.activeLeaseCount(): Int =
    this.activeLeases.size
