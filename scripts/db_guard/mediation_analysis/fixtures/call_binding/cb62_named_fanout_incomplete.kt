// CB-62: GR-14u54b completeness negative.  TWO named implementors where
// ONE does not override the interface's member (it inherits the DEFAULT
// implementation), so `_members_named` finds ZERO members of the name on
// that implementor — the completeness/override gate withdraws the whole
// admission and the dispatch stays the uncertain INTERFACE_DISPATCH edge
// (fail closed).
// Expected resolution: INTERFACE_DISPATCH.

package fixtures.callbinding

interface Cb62Sink {
    fun accept(value: Int) {
        // default implementation (the inheriting implementor overrides nothing)
    }
}

class Cb62SinkA : Cb62Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

class Cb62SinkB : Cb62Sink {
    // Inherits the default accept — no override of its own.
    fun other() {
        // no-op fixture body
    }
}

fun exerciseCb62(sink: Cb62Sink) {
    sink.accept(11)
}
