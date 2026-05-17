package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerLeaseRegistryImpl @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier
) : WorkerLeaseRegistry, WorkerDrainController {

    // workerName → lease (one active lease per worker at a time)
    internal val activeLeases = ConcurrentHashMap<String, WorkerLeaseImpl>()
    private val stopRequested = AtomicBoolean(false)

    // ── WorkerLeaseRegistry ───────────────────────────────────────

    override suspend fun acquire(workerName: String): WorkerLease {
        val lease = WorkerLeaseImpl(workerName)
        activeLeases[workerName] = lease
        Timber.d("WorkerLease acquired: $workerName (active=${activeLeases.size})")
        return lease
    }

    override suspend fun requestStopAll(reason: String) {
        stopRequested.set(true)
        Timber.w("WorkerLeaseRegistry: stop requested for all workers — reason=$reason")
    }

    override suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (activeLeases.isNotEmpty()) {
            if (System.currentTimeMillis() >= deadline) {
                Timber.w("WorkerLeaseRegistry: drain timed out, ${activeLeases.size} worker(s) still active: ${activeLeases.keys}")
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

    fun resetStopFlag() {
        stopRequested.set(false)
    }

    fun isStopRequested(): Boolean = stopRequested.get()

    // ── WorkerLeaseImpl ───────────────────────────────────────────

    inner class WorkerLeaseImpl(private val workerName: String) : WorkerLease {
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
                activeLeases.remove(workerName)
                Timber.d("WorkerLease released: $workerName (active=${activeLeases.size})")
            }
        }
    }
}
