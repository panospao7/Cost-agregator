// SL-02: launch on an UNREVIEWED receiver stays async-uncertain
// (GR-14j fail-closed pin).  Only the closed receiver set is admitted;
// GlobalScope-style and injected dispatchers keep their uncertainty.
// Expected resolution: ASYNC_DISPATCH.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.

package fixtures.helperproof

class Sl02UnknownScopeStaysAsync {
    private val store = mutableMapOf<Int, Int>()
    private val injectedScope = Scope()

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
            injectedScope.launch { writeRow(id) }
        }
    }
}
