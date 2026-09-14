// CB-16: a `dagger.Lazy<X>.get().member()` chain unwraps to receiver type X.
// Expected resolution: EXACT_SYNCHRONOUS (the chain is an exact member call on
// the wrapped corpus type, not an untracked expression chain).
// GR-14u22: DI wrapper receivers (dagger.Lazy / Provider) resolve through the
// wrapped type for receiver typing.

package fixtures.callbinding

class Cb16Store {
    fun put(value: Int): Int = value
}

class Cb16LazyClient(
    private val storeProvider: dagger.Lazy<fixtures.callbinding.Cb16Store>
) {
    fun exerciseCb16(): Int {
        return storeProvider.get().put(7)
    }
}
