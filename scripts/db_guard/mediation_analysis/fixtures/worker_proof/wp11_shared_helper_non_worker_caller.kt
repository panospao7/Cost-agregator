// WP-11: same guarded helper also called from a non-worker public fun.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_NON_WORKER_ROOT.
// Expected root: PUBLIC_OR_PROTECTED_EXTERNAL.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp11SharedHelper {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun publicCaller(id: Int) {
        writeRow(id)
    }

    fun guardedWorkerCall(id: Int) {
        guard.runGuardedWithContext { writeRow(id) }
    }
}
