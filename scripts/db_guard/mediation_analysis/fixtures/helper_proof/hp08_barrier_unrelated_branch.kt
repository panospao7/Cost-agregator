// HP-08: barrier exists but the helper call sits in an unrelated branch.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

package fixtures.helperproof

class Hp08BarrierUnrelatedBranch {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int, useBarrier: Boolean) {
        if (useBarrier) {
            withWriteBarrier { store.put(id, id) }
        } else {
            writeRow(id)
        }
    }
}
