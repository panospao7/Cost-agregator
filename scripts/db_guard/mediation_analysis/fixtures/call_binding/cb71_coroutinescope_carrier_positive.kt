// CB-71: GR-14u56c coroutineScope positive — a bare (receiverless)
// `coroutineScope { }` block is an admitted transparent carrier
// (kotlinx.coroutines.coroutineScope is a top-level suspend scope
// function: suspend-inline, runs the block in the caller's context and
// returns before the caller proceeds), so the corpus method called inside
// resolves EXACT.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb71Store {
    fun put(id: Int)
}

class Cb71StoreImpl : Cb71Store {
    override fun put(id: Int) {
        // no-op fixture body
    }
}

fun exerciseCb71(store: Cb71StoreImpl) {
    coroutineScope {
        store.put(1)
    }
}
