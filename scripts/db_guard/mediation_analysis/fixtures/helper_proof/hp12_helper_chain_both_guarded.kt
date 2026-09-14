// HP-12: helper-to-helper chain where every call site is guarded.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp12HelperChain {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    private fun writeRowNormalized(id: Int) {
        writeRow(id)
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRowNormalized(id) }
    }
}
