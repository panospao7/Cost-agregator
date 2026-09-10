// HP-03: private helper with zero call sites in this file.
// Expected resolution: EXTERNAL_ENTRY.
// Expected proof: UNPROVEN_EXTERNAL_ENTRY.

package fixtures.helperproof

class Hp03ZeroCallSites {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun exercise(): Int {
        return store.size
    }
}
