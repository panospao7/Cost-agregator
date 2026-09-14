// HP-22: default-parameter DI (`viewModel: Hp22ViewModel = Hp22ViewModel()`)
// — GR-14n regression pin.  The parameter's declared type must resolve
// despite the default-value assignment, so the edge through the receiver
// is exact and the guarded caller path proves the writer.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_HELPER.

package fixtures.helperproof

class Hp22ViewModel {
    val store = mutableMapOf<Int, Int>()

    fun writeRow(id: Int) {
        store.put(id, id)
    }
}

class Hp22DefaultParameterDi {
    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(
        viewModel: Hp22ViewModel = Hp22ViewModel(),
        id: Int = 1
    ) {
        withWriteBarrier {
            viewModel.writeRow(id)
        }
    }
}
