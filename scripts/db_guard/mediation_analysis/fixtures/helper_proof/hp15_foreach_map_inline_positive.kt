// HP-15: mutations inside eager collection lambdas (`map` then `forEach`).
// Both run to completion before return; neither can escape.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp15ForEachMapInline {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRows(ids: List<Int>) {
        val rows = ids.map { it to it }
        rows.forEach { pair ->
            store.put(pair.first, pair.second)
        }
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRows(listOf(id)) }
    }
}
