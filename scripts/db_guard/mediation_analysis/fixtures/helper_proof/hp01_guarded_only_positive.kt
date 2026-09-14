// HP-01: helper invoked only from inside the write barrier scope.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp01GuardedOnly {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
