// CB-25: GR-14u45 adversarial pin — receiver generic with NO explicit type
// argument (a raw/bare `Set` spelling).  Shape B requires exactly one
// explicit type argument; without it the binding must not fire and
// `it.initialize()` keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb25Initializer {
    fun initialize()
}

fun exerciseCb25(assets: Set<*>) {
    assets.forEach { it.initialize() }
}
