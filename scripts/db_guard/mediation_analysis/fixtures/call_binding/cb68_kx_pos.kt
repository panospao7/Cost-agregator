// CB-68: GR-14u55 KX-pos — kotlinx as a known external root.  A file with
// `import kotlinx.coroutines.flow.*` (the only star import) and a local val
// explicitly typed `MutableStateFlow<Int>`: the receiver resolves
// external (kotlinx.coroutines.flow.MutableStateFlow) and the member call
// on it is an exact-external edge.  This is the u53 disclosed-limitation
// completion (the one-star kotlinx candidate previously failed closed).
// Expected resolution: EXACT_SYNCHRONOUS (external).

package fixtures.callbinding

import kotlinx.coroutines.flow.*

interface Cb68Sink {
    fun drain()
}

class Cb68Holder {
    fun exercise() {
        val flow: MutableStateFlow<Int> = MutableStateFlow(1)
        flow.update { it + 1 }
        Cb68SinkImpl().drain()
    }
}

class Cb68SinkImpl : Cb68Sink {
    override fun drain() {}
}
