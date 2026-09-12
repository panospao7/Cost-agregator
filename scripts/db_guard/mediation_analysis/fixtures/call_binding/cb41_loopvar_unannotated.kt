// CB-41: reviewer LV-neg-unannotated — a loop iterable bound to a local
// WITHOUT an explicit type annotation never types the loop variable
// (inferred/ctor-shape texts never pass the explicitness bar), so the
// member call keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb41Target {
    val name: String
    fun purge(cutoff: Long)
}

fun exerciseCb41() {
    val targets = listOf<Cb41Target>()
    for (target in targets) {
        target.purge(0L)
    }
}
