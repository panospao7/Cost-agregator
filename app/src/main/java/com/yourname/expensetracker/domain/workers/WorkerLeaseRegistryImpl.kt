package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerLeaseRegistryImpl @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) : WorkerLeaseRegistry, WorkerDrainController {

    internal data class LeaseRecord(
        val leaseId: String,
        val workerName: String,
        val lease: WorkerLeaseImpl
    )

    // Primary store: leaseId → record
    internal val activeLeases = ConcurrentHashMap<String, LeaseRecord>()

    // Secondary index: workerName → set of leaseIds
    internal val workerNameIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val stopRequested = AtomicBoolean(false)
    private val lock = Any()

    // ── WorkerLeaseRegistry ───────────────────────────────────────

    override suspend fun acquire(workerName: String): WorkerLease {
        if (stopRequested.get()) {
            throw LeaseAcquisitionBlockedException(workerName, "stop requested")
        }
        synchronized(lock) {
            if (stopRequested.get()) {
                throw LeaseAcquisitionBlockedException(workerName, "stop requested")
            }
            val leaseId = UUID.randomUUID().toString()
            val lease = WorkerLeaseImpl(leaseId, workerName)
            activeLeases[leaseId] = LeaseRecord(leaseId, workerName, lease)
            workerNameIndex.computeIfAbsent(workerName) { ConcurrentHashMap.newKeySet() }.add(leaseId)
            Timber.d("WorkerLease acquired: $workerName leaseId=$leaseId (active=${activeLeases.size})")
            return lease
        }
    }

    override suspend fun requestStopAll(reason: String) {
        synchronized(lock) {
            stopRequested.set(true)
        }
        Timber.w("WorkerLeaseRegistry: stop requested for all workers — reason=$reason")
    }

    override suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean {
        val deadline = timeProvider.now() + timeoutMs
        while (activeLeases.isNotEmpty()) {
            if (timeProvider.now() >= deadline) {
                Timber.w("WorkerLeaseRegistry: drain timed out, ${activeLeases.size} worker(s) still active: ${activeLeases.values.map { it.workerName }}")
                return false
            }
            delay(50)
        }
        Timber.d("WorkerLeaseRegistry: all workers drained")
        return true
    }

    // ── WorkerDrainController ─────────────────────────────────────

    override suspend fun requestStopAndAwaitDrain(operationName: String, timeoutMs: Long): Boolean {
        requestStopAll(operationName)
        return awaitNoActiveWorkers(timeoutMs)
    }

    // ── Internal reset (called after maintenance exits) ───────────

    override fun resetStopFlag() {
        stopRequested.set(false)
    }

    override fun isStopRequested(): Boolean = stopRequested.get()

    // ── WorkerLeaseImpl ───────────────────────────────────────────

    inner class WorkerLeaseImpl(
        val leaseId: String,
        private val workerName: String
    ) : WorkerLease {
        private val released = AtomicBoolean(false)

        override suspend fun checkpoint(operation: String) {
            if (stopRequested.get()) {
                // Propagate as cancellation so the worker's coroutine exits cleanly
                throw kotlinx.coroutines.CancellationException(
                    "Worker $workerName cancelled at checkpoint '$operation' — maintenance stop requested"
                )
            }
            writeBarrier.checkWritesAllowed(operation)
            yield()
        }

        override fun close() {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    activeLeases.remove(leaseId)
                    workerNameIndex.computeIfPresent(workerName) { _, ids ->
                        ids.remove(leaseId)
                        if (ids.isEmpty()) null else ids
                    }
                }
                Timber.d("WorkerLease released: $workerName leaseId=$leaseId (active=${activeLeases.size})")
            }
        }
    }
}
