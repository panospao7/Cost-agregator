// CB-06: call goes through an interface-typed reference.
// Expected resolution: INTERFACE_DISPATCH — TWO corpus implementors exist, so
// the GR-14u24 single-implementor exactness rule does not apply.

package fixtures.callbinding

interface Cb06Sink {
    fun accept(value: Int)
}

class Cb06ConsoleSink : Cb06Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

class Cb06FileSink : Cb06Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

fun exerciseCb06(sink: Cb06Sink) {
    sink.accept(11)
}
