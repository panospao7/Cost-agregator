// WP-15: two worker classes share the same FQCN across files.
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
        guard.runGuardedWithContext { store.put(1, 1) }
        return Result.success()
    }
}
