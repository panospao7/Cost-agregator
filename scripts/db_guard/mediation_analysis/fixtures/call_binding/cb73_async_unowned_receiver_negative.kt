// CB-73: GR-14u56c async-on-unowned-receiver NEGATIVE — the
// receiver-evidence gate.  `unownedScope.async { }` is NOT receiverless
// and NOT on the reviewed owned-receiver set, so the region keeps its
// default ASYNC carrier and the call inside stays ASYNC_DISPATCH
// (fail closed; mirrors SL-02 for the launch family).
// Expected resolution: ASYNC_DISPATCH.

package fixtures.callbinding

interface Cb73Store {
    fun put(id: Int)
}

class Cb73StoreImpl : Cb73Store {
    override fun put(id: Int) {
        // no-op fixture body
    }
}

class Cb73UnownedScope {
    fun async(block: () -> Unit) {
        // no-op fixture body — NOT an admitted transparent carrier
    }
}

fun exerciseCb73(store: Cb73StoreImpl, unownedScope: Cb73UnownedScope) {
    unownedScope.async {
        store.put(1)
    }
}
