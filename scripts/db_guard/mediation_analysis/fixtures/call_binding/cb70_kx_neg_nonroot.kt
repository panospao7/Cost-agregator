// CB-70: GR-14u55 KX-neg-nonroot — the roots list did not become a wildcard.
// A file star-importing a NON-root package (`fixtures.starfix53.*`, the
// cb57 shape) with an unresolvable simple name: the star candidate still
// fails closed (UNRESOLVED_TARGET) — adding kotlinx to
// _KNOWN_EXTERNAL_ROOTS changed nothing for non-root packages.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

import fixtures.starfix53.*

interface Cb70Sink {
    fun drain()
}

class Cb70Holder {
    fun exercise() {
        val flow: MutableStateFlow<Int> = MutableStateFlow(1)
        flow.emitNow(2)
        Cb70SinkImpl().drain()
    }
}

class Cb70SinkImpl : Cb70Sink {
    override fun drain() {}
}
