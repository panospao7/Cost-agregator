// HP-07: two helpers calling each other recursively, no barrier entry.
// Expected resolution: RECURSIVE_UNSUPPORTED.
// Expected proof: UNPROVEN_RECURSION.

package fixtures.helperproof

class Hp07RecursivePair {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRowA(id: Int, depth: Int) {
        if (depth > 0) {
            writeRowB(id, depth - 1)
        } else {
            store.put(id, id)
        }
    }

    private fun writeRowB(id: Int, depth: Int) {
        writeRowA(id, depth - 1)
    }

    fun exercise(id: Int) {
        writeRowA(id, 3)
    }
}
