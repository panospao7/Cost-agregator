// WP-17: read-only-backup waiver; checkpoint() is not a scope.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE.
// Expected root: WORKER_DO_WORK.
// Waiver: scope has allowDuringBackupExport=true and requiresDatabaseWrite=false with mode!=NORMAL, so a DB write inside is NOT proven. checkpoint() is only a check.
// GR-13-WAIVER: allowDuringBackupExport=true requiresDatabaseWrite=false (mode!=NORMAL: the guard body runs without the write-barrier check)
package fixtures.workerproof
open class CoroutineWorker { open fun doWork(): Int = 0 }
class WorkerExecutionGuard {
  fun runGuardedWithContext(block: () -> Unit) { block() }
  fun checkpoint() {}
}
class Wp17CheckpointNotScope : CoroutineWorker() {
  private val guard = WorkerExecutionGuard()
  private var writes = 0
  override fun doWork(): Int {
    guard.checkpoint()
    guard.runGuardedWithContext {
      writes = 1
    }
    return writes
  }
}
