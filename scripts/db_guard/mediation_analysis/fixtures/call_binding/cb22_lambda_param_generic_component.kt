// CB-22: GR-14u45 adversarial pin — GENERIC component type.  The carrier's
// function type is `suspend (T) -> T`; a generic component must NEVER bind
// (no concrete receiver type is derivable), so `run.event(...)` keeps the
// name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb22Handle {
    fun event(stage: String)
}

interface Cb22Recorder {
    fun <T> runOperation(operationType: String, block: suspend (T) -> T): T
}

fun exerciseCb22(recorder: Cb22Recorder) {
    recorder.runOperation("X") { run ->
        run.event("SYNC_STARTED")
    }
}
