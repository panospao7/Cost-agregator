// CB-61: GR-14u54b named-implementor fan-out positive.  An interface with
// TWO named classes implementing one override member forms an
// enumerable-complete set; the dispatch site on an interface-typed
// receiver emits exact fan-out edges to BOTH implementors (the u54b gate
// extension — the anon-precondition no longer rejects named-only
// interfaces).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb61Sink {
    fun accept(value: Int)
}

class Cb61SinkA : Cb61Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

class Cb61SinkB : Cb61Sink {
    override fun accept(value: Int) {
        // no-op fixture body
    }
}

fun exerciseCb61(sink: Cb61Sink) {
    sink.accept(11)
}
