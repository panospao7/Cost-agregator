// HP-04: helper appears only as a BOUND function reference (`this::writeRow`).
// GR-14u26 makes a referenced member reachable: the reference binds exactly to
// `writeRow`, which mutates the store with no guard of its own — so under the
// strictness model this is now a DEFINITE unguarded call path, not an
// unprovable one.  (Before GR-14u26 the reference was uncertain, so the writer
// stayed unreachable and the row was merely UNPROVEN.  The fixture is updated
// to the new truth; it is no longer a pin on "references stay uncertain".)
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

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
