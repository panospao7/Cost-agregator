// HP-18: assigned lambda literal still escapes (GR-14f fail-closed pin).
// Admitting inline carriers must NOT admit `val f = { ... }` literals.
// Expected resolution: ESCAPING_LAMBDA.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.

package fixtures.helperproof

class Hp18AssignedLambdaEscape {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        val deferred = {
            store.put(id, id)
        }
        deferred()
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
