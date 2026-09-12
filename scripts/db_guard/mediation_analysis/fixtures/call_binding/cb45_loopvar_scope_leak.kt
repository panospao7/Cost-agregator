// CB-45: reviewer LV-neg-scope-leak — the loop-variable binding is
// offset-scoped to the loop body: a same-named receiver AFTER the loop
// stays untyped.  The after-loop call targets a DIFFERENT interface
// member (`drain`) so the subject call is single-state; it keeps the
// name-matched UNRESOLVED_TARGET edge (no binding leaks out of the loop).
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb45Target {
    fun purge(cutoff: Long)
    fun drain()
}

fun exerciseCb45() {
    val targets: List<Cb45Target> = emptyList()
    for (target in targets) {
        target.purge(0L)
    }
    // After the loop: the same name is NOT bound by the loop typing.
    target.drain()
}
