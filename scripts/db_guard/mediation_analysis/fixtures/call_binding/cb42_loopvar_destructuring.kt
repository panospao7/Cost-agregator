// CB-42: reviewer LV-neg-destructuring — a destructuring loop head
// (`for ((k, v) in m)`) never binds a loop-variable type (fail closed).
// The subject call inside the loop body keeps the name-matched
// UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb42Target {
    fun purge(cutoff: Long)
}

fun exerciseCb42() {
    val entries: Map<String, Cb42Target> = emptyMap()
    for ((k, v) in entries) {
        v.purge(0L)
    }
}
