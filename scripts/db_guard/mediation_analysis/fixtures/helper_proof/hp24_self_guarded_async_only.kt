// HP-24: self-guarded helper reached ONLY from an uncertain (async) caller.
//
// The mutation sits inside the canonical direct scope in the helper's OWN body,
// so the canonical barrier is evaluated at the mutation site and the caller's
// uncertainty cannot change the outcome (GR-14u27).  Before that batch this row
// was UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK purely because of the async caller.
// The uncertain inbound edge itself is still recorded (reachability preserved);
// only the proof outcome changes.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp24SelfGuardedAsyncOnly {
    private val store = mutableMapOf<Int, Int>()

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    private fun writeRow(id: Int) {
        withWriteBarrier {
            store.put(id, id)
        }
    }

    fun exercise(dispatcher: (block: () -> Unit) -> Unit, id: Int) {
        dispatcher { writeRow(id) }
    }
}
