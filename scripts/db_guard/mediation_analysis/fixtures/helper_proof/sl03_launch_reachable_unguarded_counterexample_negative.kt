// SL-03: the GR-14j strictness inversion pinned.  With the structured
// launch edge exact, a mutation with NO local canonical scope on a
// launch-reachable path from a public root is a DEFINITE counterexample —
// honestly stricter than the historical async-uncertain classification.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

package fixtures.helperproof

class Sl03LaunchReachableUnguarded {
    private val store = mutableMapOf<Int, Int>()
    private val viewModelScope = Scope()

    class Scope {
        fun launch(block: () -> Unit) {
            block()
        }
    }

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun unguardedWrite(id: Int) {
        viewModelScope.launch { writeRow(id) }
    }
}
