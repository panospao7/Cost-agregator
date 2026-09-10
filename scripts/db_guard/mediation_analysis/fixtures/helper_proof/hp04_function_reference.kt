// HP-04: helper appears only as a function reference, never a direct call.
// Expected resolution: FUNCTION_REFERENCE.
// Expected proof: UNPROVEN_AMBIGUOUS_CALL.

package fixtures.helperproof

class Hp04FunctionReference {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun reference(): (Int) -> Unit {
        return this::writeRow
    }

    fun exercise(): Int {
        return store.size
    }
}
