// WP-13: mutation escapes doWork via a coroutine launch callback.
// Expected resolution: ASYNC_DISPATCH.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.
// Expected root: WORKER_DO_WORK.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp13LaunchEscape : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val guard = WorkerExecutionGuard()

    fun launch(block: () -> Unit) {
        block()
    }

    override fun doWork(): Result {
        guard.runGuardedWithContext {
            launch { store.put(1, 1) }
        }
        return Result.success()
    }
}
