// CB-43: reviewer LV-neg-map — iterating a Map directly iterates entries,
// not a collection of the element type; Map is not in the collection-head
// set, so the loop variable stays untyped and the member call keeps the
// name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb43Target {
    fun purge(cutoff: Long)
}

fun exerciseCb43() {
    val map: Map<String, Cb43Target> = emptyMap()
    for (x in map) {
        x.purge(0L)
    }
}
