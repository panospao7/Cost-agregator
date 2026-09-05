// WP-15: second declaration with the same FQCN as wp15_duplicate_worker_a.
// Expected resolution: AMBIGUOUS_TARGET.
// Expected proof: UNPROVEN_AMBIGUOUS_CALL.
// Expected root: UNKNOWN_EXTERNAL.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class DuplicateWorker : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    override fun doWork(): Result {
        store.put(1, 1)
        return Result.success()
    }
}
