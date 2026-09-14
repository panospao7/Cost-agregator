// WP-07: mutation happens before the guard scope is entered.
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

class Wp07MutationBeforeGuard : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        store.put(1, 1)
        guard.runGuardedWithContext { store.put(2, 2) }
        return Result.success()
    }
}
