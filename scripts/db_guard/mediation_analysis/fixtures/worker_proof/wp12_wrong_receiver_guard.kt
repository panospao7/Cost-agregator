// WP-12: runGuarded invoked on a receiver that is not the worker guard.
// Expected resolution: UNRESOLVED_TARGET.
// Expected proof: UNPROVEN_AMBIGUOUS_CALL.
// Expected root: WORKER_DO_WORK.

package fixtures.workerproof

open class CoroutineWorker {
    abstract fun doWork(): Result
}

class WorkerExecutionGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class UnrelatedGuard {
    fun runGuardedWithContext(block: () -> Unit) = block()
}

class Wp12WrongReceiver : CoroutineWorker() {
    private val store = mutableMapOf<Int, Int>()
    private val other = UnrelatedGuard()

    override fun doWork(): Result {
        other.runGuardedWithContext { store.put(1, 1) }
        return Result.success()
    }
}
