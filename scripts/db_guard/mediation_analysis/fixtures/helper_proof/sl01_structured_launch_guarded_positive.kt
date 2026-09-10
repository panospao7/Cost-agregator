// SL-01: mutation reached through a launch on a reviewed structured scope
// (GR-14j).  Structured launch edges are exact with context inherited, so
// the guarded caller path proves the helper.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Sl01StructuredLaunchGuarded {
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

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier {
            viewModelScope.launch { writeRow(id) }
        }
    }
}
