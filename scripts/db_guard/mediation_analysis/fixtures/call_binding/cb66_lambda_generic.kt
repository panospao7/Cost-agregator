// CB-66: GR-14u55b NL-neg-generic — the OUTER binding's component is the
// callee's own generic parameter T (`fun <T> runOp(block: suspend (T) -> T)`):
// a generic component never binds, so the inner call keeps the
// name-matched UNRESOLVED_TARGET edge (the generic-T gate, preserved
// per level in the u55b walk).
// Expected resolution: ASYNC_DISPATCH (the async region state preempts receiver resolution
// before the u55b walk runs - a region-preemption pin, not a walk pin)..

package fixtures.callbinding

interface Cb66Handle {
    fun success()
}

interface Cb66Runner {
    fun <T> runOp(block: suspend (T) -> T): T
}

fun exerciseCb66(runner: Cb66Runner) {
    runner.runOp { run ->
        run.success()
    }
}
