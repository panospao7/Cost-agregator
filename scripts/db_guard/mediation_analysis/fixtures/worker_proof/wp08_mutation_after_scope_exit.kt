// WP-08: mutation happens after the guard scope has exited.
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

class Wp08MutationAfterScopeExit : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        guard.runGuardedWithContext { store.put(1, 1) }
        store.put(2, 2)
        return Result.success()
    }
}
