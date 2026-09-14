// CB-17: interface dispatch with exactly ONE corpus implementor and no
// anonymous `object :` sites resolves EXACT (GR-14u24 exactness rule).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb17Port {
    fun push(value: Int)
}

class Cb17PortImpl : Cb17Port {
    override fun push(value: Int) {
        // no-op fixture body
    }
}

fun exerciseCb17(port: Cb17Port) {
    port.push(3)
}
