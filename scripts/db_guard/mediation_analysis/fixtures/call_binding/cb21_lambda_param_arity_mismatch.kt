// CB-21: GR-14u45 adversarial pin — ARITY MISMATCH.  The lambda declares
// TWO parameters but the carrier's function type has ONE component; the
// binding must not fire and `run.event(...)` keeps the name-matched
// UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb21Handle {
    fun event(stage: String)
}

interface Cb21Recorder {
    fun <T> runOperation(operationType: String, block: suspend (Cb21Handle) -> T): T
}

fun exerciseCb21(recorder: Cb21Recorder) {
    recorder.runOperation("X") { run, other ->
        run.event("SYNC_STARTED")
    }
}
