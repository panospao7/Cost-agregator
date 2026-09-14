// HP-10: GR-14 candidate inserts directly with only a local barrier.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

package fixtures.helperproof

class Hp10LocalBarrierDirectInsert {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun exercise(id: Int) {
        val barrier = "local"
        check(barrier.isNotEmpty())
        store.put(id, id)
        writeRow(id)
    }
}
