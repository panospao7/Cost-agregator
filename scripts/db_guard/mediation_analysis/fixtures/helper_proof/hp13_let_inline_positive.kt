// HP-13: mutation inside a stdlib `let` scope lambda (GR-14f inline carrier).
// The lambda is inline/eager and cannot escape; the caller guard context
// must flow through it to the mutation.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp13LetInline {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        val key = id
        key.let { value ->
            store.put(value, value)
        }
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
