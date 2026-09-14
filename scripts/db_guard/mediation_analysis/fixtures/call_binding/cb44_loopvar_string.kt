// CB-44: reviewer LV-neg-string — iterating a String yields Chars; String
// is not in the collection-head set, so the loop variable stays untyped
// and the member call keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb44Target {
    fun purge(cutoff: Long)
}

fun exerciseCb44() {
    val label = "abc"
    for (c in label) {
        c.purge(0L)
    }
}
