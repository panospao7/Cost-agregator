// CB-72: GR-14u56c async-inside-coroutineScope positive (the
// processBatch shape, ReceiptRepository L584-586): a bare `async { }`
// inside a `coroutineScope { }` block is an admitted transparent carrier
// (kotlinx.coroutines.async runs its block in the caller's context and
// the Deferred must be awaited before the coroutineScope block
// completes), so the corpus method called inside resolves EXACT.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb72Store {
    fun put(id: Int)
}

class Cb72StoreImpl : Cb72Store {
    override fun put(id: Int) {
        // no-op fixture body
    }
}

fun exerciseCb72(store: Cb72StoreImpl) {
    coroutineScope {
        async {
            store.put(1)
        }
    }
}
