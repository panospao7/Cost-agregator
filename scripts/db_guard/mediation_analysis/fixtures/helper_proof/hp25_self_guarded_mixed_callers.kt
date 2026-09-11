// HP-25: self-guarded helper with BOTH an exact guarded caller and an uncertain
// (async) caller.  The local guard decides the row on its own evidence; the
// uncertain caller must not block it (GR-14u27, step 5).  Before that batch this
// row was UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK while HP-01 (same helper reached
// only synchronously) was already PROVEN — the caller was the only difference.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp25SelfGuardedMixedCallers {
    private val store = mutableMapOf<Int, Int>()

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    private fun writeRow(id: Int) {
        withWriteBarrier {
            store.put(id, id)
        }
    }

    fun directCaller(id: Int) {
        withWriteBarrier { writeRow(id) }
    }

    fun asyncCaller(dispatcher: (block: () -> Unit) -> Unit, id: Int) {
        dispatcher { writeRow(id) }
    }
}
