// HP-21: named-argument lambda (dialog onConfirm shape) is NOT an
// assigned-literal escape (GR-14m regression pin).  The handler lambda is
// a call argument; it executes in-process with inherited context, and the
// guarded caller path proves the helper.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp21NamedArgumentLambda {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun dialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
        onDismiss()
    }

    fun exercise(id: Int) {
        withWriteBarrier {
            dialog(
                onConfirm = { value -> writeRow(value) },
                onDismiss = { }
            )
        }
    }
}
