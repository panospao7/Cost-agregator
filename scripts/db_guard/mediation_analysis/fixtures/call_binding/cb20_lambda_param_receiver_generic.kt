// CB-20: GR-14u45 shape B — an arity-1 lambda parameter binds through the
// receiver's single explicit collection type argument.
// `assets.forEach { it.initialize() }` types `it` as Cb20Initializer
// (receiver `assets: Set<Cb20Initializer>`), so `it.initialize()` resolves
// EXACT to the interface's single implementor member.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb20Initializer {
    fun initialize()
}

class Cb20InitializerImpl : Cb20Initializer {
    override fun initialize() {
        // no-op fixture body
    }
}

fun exerciseCb20(assets: Set<Cb20Initializer>) {
    assets.forEach { it.initialize() }
}
