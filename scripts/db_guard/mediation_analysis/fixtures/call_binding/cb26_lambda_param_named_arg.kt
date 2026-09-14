// CB-26: GR-14u45 adversarial pin — the lambda passed as a NAMED argument
// in non-trailing position.  The block parameter is NOT the callee's last
// declared parameter in the binding position the trailing-lambda convention
// requires; `run.event(...)` keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb26Handle {
    fun event(stage: String)
}

interface Cb26Recorder {
    fun <T> runOperation(operationType: String, block: suspend (Cb26Handle) -> T): T
}

fun exerciseCb26(recorder: Cb26Recorder) {
    val handle = Cb26HandleImpl()
    recorder.runOperation(operationType = "X", block = { run ->
        run.event("SYNC_STARTED")
    })
}

class Cb26HandleImpl : Cb26Handle {
    override fun event(stage: String) {
        // no-op fixture body
    }
}
