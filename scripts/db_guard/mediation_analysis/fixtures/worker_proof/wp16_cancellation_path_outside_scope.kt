// WP-16: mutation in the cancellation/result path outside the guard scope.
// Parser-safe: no exception types are referenced.
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

class Wp16CancellationPath : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        return try {
            guard.runGuardedWithContext { store.put(1, 1) }
            store.put(2, 2)
            Result.success()
        } catch (error: Throwable) {
            store.put(3, 3)
            Result.failure()
        }
    }
}
