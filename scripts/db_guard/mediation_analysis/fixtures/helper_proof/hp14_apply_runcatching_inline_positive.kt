// HP-14: mutation inside nested inline error/scope carriers
// (`apply` inside `runCatching`, with `getOrElse` fallback).  All three
// carriers run the block before returning; none can escape.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp14ApplyRunCatchingInline {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        runCatching {
            store.apply {
                store.put(id, id)
            }
        }.getOrElse { emptyMap() }
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
