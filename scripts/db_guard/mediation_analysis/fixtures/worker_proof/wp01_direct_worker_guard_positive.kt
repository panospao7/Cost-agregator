// WP-01: direct CoroutineWorker subclass mutates inside the canonical guard.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_WORKER_MEDIATED.
// Expected root: WORKER_DO_WORK.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp01DirectWorker : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        guard.runGuardedWithContext { store.put(1, 1) }
        return Result.success()
    }
}
