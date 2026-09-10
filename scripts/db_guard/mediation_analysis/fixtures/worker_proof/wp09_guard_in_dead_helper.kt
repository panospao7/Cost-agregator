// WP-09: guard exists only in a dead helper never called from doWork.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE.
// Expected root: WORKER_DO_WORK.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp09DeadGuardHelper : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    private fun deadGuardedHelper() {
        guard.runGuardedWithContext { store.put(1, 1) }
    }

    override fun doWork(): Result {
        store.put(2, 2)
        return Result.success()
    }
}
