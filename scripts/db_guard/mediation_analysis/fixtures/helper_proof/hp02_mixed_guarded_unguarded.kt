// HP-02: helper invoked both inside and outside the write barrier.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

package fixtures.helperproof

class Hp02MixedGuard {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exerciseGuarded(id: Int) {
        withWriteBarrier { writeRow(id) }
    }

    fun exerciseUnguarded(id: Int) {
        writeRow(id)
    }
}
