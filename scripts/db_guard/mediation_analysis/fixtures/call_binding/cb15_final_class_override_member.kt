// CB-15: call to an `override` member on a FINAL class receiver.
// Kotlin finality guarantees exactly one dispatched implementation —
// GR-14r resolution (the open-method contrast is CB-07, which stays
// VIRTUAL_DISPATCH).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb15Sink {
    fun accept(value: Int)
}

class Cb15FinalSink : Cb15Sink {
    override fun accept(value: Int) {
        // final class: this override is the only dispatched implementation
    }
}

fun exerciseCb15(sink: Cb15FinalSink) {
    sink.accept(15)
}
