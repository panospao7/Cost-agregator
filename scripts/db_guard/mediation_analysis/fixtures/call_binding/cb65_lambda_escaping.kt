// CB-65: GR-14u55b NL-neg-escaping — the outer lambda is an ASYNC region
// (a launch-style carrier, not an admitted transparent carrier): lambda
// params of async regions never bind, so the inner call keeps the
// name-matched UNRESOLVED_TARGET edge (the admitted-regions-only guard,
// preserved per level in the u55b walk).
// Expected resolution: ASYNC_DISPATCH (the async region state preempts receiver resolution
// before the u55b walk runs - a region-preemption pin, not a walk pin)..

package fixtures.callbinding

interface Cb65Handle {
    fun success()
}

class Cb65Scope {
    fun launchWorker(block: suspend (Cb65Handle) -> Unit) {
        // no-op fixture body — NOT an admitted transparent carrier
    }
}

fun exerciseCb65(scope: Cb65Scope) {
    scope.launchWorker { run ->
        run.success()
    }
}
