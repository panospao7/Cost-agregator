// CB-23: GR-14u45 adversarial pin — TWO DISTINCT candidate signatures.
// The carrier `runOperation` is an interface OVERLOAD PAIR whose lambda
// parameters have DIFFERENT component types (Cb23HandleA vs Cb23HandleB);
// the dispatch edge carries both overloads as targets, the candidate
// signature set has two distinct tuples, and the binding must stay
// unbound — `run.event(...)` keeps the name-matched UNRESOLVED_TARGET
// edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb23HandleA {
    fun event(stage: String)
}

interface Cb23HandleB {
    fun event(stage: String)
}

interface Cb23Recorder {
    fun <T> runOperation(operationType: String, block: suspend (Cb23HandleA) -> T): T
    fun <T> runOperation(operationId: Int, block: suspend (Cb23HandleB) -> T): T
}

class Cb23RecorderImpl : Cb23Recorder {
    override fun <T> runOperation(operationType: String, block: suspend (Cb23HandleA) -> T): T {
        // no-op fixture body
        @Suppress("UNCHECKED_CAST")
        return block(Cb23HandleAImpl()) as T
    }

    override fun <T> runOperation(operationId: Int, block: suspend (Cb23HandleB) -> T): T {
        // no-op fixture body
        @Suppress("UNCHECKED_CAST")
        return block(Cb23HandleBImpl()) as T
    }
}

class Cb23HandleAImpl : Cb23HandleA {
    override fun event(stage: String) {
        // no-op fixture body
    }
}

class Cb23HandleBImpl : Cb23HandleB {
    override fun event(stage: String) {
        // no-op fixture body
    }
}

fun exerciseCb23(recorder: Cb23Recorder) {
    recorder.runOperation("X") { run ->
        run.event("SYNC_STARTED")
    }
}
