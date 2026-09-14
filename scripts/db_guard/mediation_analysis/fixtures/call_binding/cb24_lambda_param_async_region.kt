// CB-24: GR-14u45 adversarial pin — ASYNC-region lambda parameter.  The
// lambda whose parameter would bind has an ASYNC carrier (a structured
// launch-style call, not an admitted transparent carrier); lambda params
// of async regions never bind and `worker.event(...)` keeps the
// ASYNC_DISPATCH edge.
// Expected resolution: ASYNC_DISPATCH.

package fixtures.callbinding

interface Cb24Handle {
    fun event(stage: String)
}

class Cb24FixtureScope {
    fun launchWorker(block: suspend (Cb24Handle) -> Unit) {
        // no-op fixture body — NOT an admitted transparent carrier
    }
}

fun exerciseCb24(scope: Cb24FixtureScope) {
    scope.launchWorker { worker ->
        worker.event("SYNC_STARTED")
    }
}
