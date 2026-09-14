// WP-14: worker-shaped class is not registered in the worker registry.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_NON_WORKER_ROOT.
// Expected root: WORKER_DO_WORK.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp14UnregisteredWorker : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        store.put(1, 1)
        return Result.success()
    }
}
