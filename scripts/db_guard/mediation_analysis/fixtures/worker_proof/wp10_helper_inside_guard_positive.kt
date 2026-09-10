// WP-10: helper invoked inside the guard from the worker root.
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

class Wp10HelperInsideGuard : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    override fun doWork(): Result {
        guard.runGuardedWithContext { writeRow(3) }
        return Result.success()
    }
}
