// CB-47: reviewer LV-pin-inferred-only — an iterable bound by INFERENCE
// alone (no `: T` annotation) never types the loop variable, even when the
// initializer is a direct constructor call whose shape names the element
// type.  The member call keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb47Target {
    fun purge(cutoff: Long)
}

fun exerciseCb47() {
    val targets = mutableListOf<Cb47Target>()
    for (target in targets) {
        target.purge(0L)
    }
}
