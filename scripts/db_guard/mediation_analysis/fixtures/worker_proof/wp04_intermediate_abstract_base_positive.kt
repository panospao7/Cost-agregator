// WP-04: worker extends an intermediate abstract base before CoroutineWorker.
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

abstract class Wp04MiddleWorker : CoroutineWorker() {
    protected val guard = WorkerExecutionGuard()
}

class Wp04LeafWorker : Wp04MiddleWorker() {
    private val store = mutableMapOf<Int, Int>()

    override fun doWork(): Result {
        guard.runGuardedWithContext { store.put(1, 1) }
        return Result.success()
    }
}
