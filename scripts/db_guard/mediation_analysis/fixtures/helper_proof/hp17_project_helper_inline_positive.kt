// HP-17: mutation inside the project's inline `runOperation` recorder
// wrapper (OperationRunRecorder pattern: invokes the block exactly once,
// inline, before returning).
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp17ProjectHelperInline {
    private val store = mutableMapOf<Int, Int>()

    fun runOperation(operationType: String, block: () -> Unit) {
        block()
    }

    private fun writeRow(id: Int) {
        runOperation("fixture.write") {
            store.put(id, id)
        }
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
