// CB-06: call goes through an interface-typed reference.
// Expected resolution: INTERFACE_DISPATCH.

package fixtures.callbinding

interface Cb06Sink {
    fun accept(value: Int)
}

class Cb06ConsoleSink : Cb06Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

fun exerciseCb06(sink: Cb06Sink) {
    sink.accept(11)
}
