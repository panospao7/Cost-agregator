// CB-19: GR-14u45 shape A — a lambda parameter binds through the carrier
// call's callee signature.  `recorder.runOperation("X") { run -> ... }`
// types `run` as Cb19Handle (the LAST declared parameter of runOperation
// is `block: suspend (Cb19Handle) -> T`), so `run.event(...)` resolves
// EXACT to the interface's single implementor member.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb19Handle {
    fun event(stage: String)
}

class Cb19HandleImpl : Cb19Handle {
    override fun event(stage: String) {
        // no-op fixture body
    }
}

interface Cb19Recorder {
    fun <T> runOperation(operationType: String, block: suspend (Cb19Handle) -> T): T
}

class Cb19RecorderImpl : Cb19Recorder {
    override fun <T> runOperation(operationType: String, block: suspend (Cb19Handle) -> T): T {
        // no-op fixture body
        @Suppress("UNCHECKED_CAST")
        return block(Cb19HandleImpl()) as T
    }
}

fun exerciseCb19(recorder: Cb19Recorder) {
    recorder.runOperation("X") { run ->
        run.event("SYNC_STARTED")
    }
}
