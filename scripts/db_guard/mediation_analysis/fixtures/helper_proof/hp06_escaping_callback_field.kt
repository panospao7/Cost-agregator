// HP-06: helper reference stored in a field, escaping the enclosing scope.
// Expected resolution: ESCAPING_LAMBDA.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.

package fixtures.helperproof

class Hp06EscapingCallback {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    var storedCallback: (() -> Unit)? = null

    fun exercise(id: Int) {
        storedCallback = { writeRow(id) }
    }
}
