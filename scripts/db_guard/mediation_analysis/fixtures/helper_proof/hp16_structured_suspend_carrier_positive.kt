// HP-16: mutation inside structured suspend carriers
// (`Mutex.withLock` inside `withTimeout`).  Both are suspend-inline
// wrappers that run the block before returning and cannot escape.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp16StructuredSuspendCarrier {
    private val store = mutableMapOf<Int, Int>()
    private val mutex = Any()

    private suspend fun writeRow(id: Int) {
        withTimeout(id.toLong()) {
            mutex.withLock {
                store.put(id, id)
            }
        }
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
