// CB-06: call goes through an interface-typed reference.
// GR-14u54b amendment: the bounded fan-out admission now covers
// NAMED-implementor interfaces — TWO corpus implementors, both overriding
// accept, form an enumerable-complete set, so the dispatch emits exact
// edges to BOTH implementors (was INTERFACE_DISPATCH before u54b; the old
// expectation pinned the pre-extension uncertainty).

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
