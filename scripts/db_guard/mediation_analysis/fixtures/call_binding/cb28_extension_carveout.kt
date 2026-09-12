// CB-28: GR-14u47 extension carve-out — an external-typed receiver whose
// call name matches a UNIQUE corpus extension on that type keeps the
// uncertain name-match edge (real Kotlin could dispatch into the corpus,
// so the receiver is not provably external).  The receiver type is a
// kotlin.* dotted spelling (external origin); the corpus declares exactly
// one extension `success` on it.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

fun kotlin.text.StringBuilder.persist(value: Int) {
    // no-op fixture body
}

fun exerciseCb28() {
    val thing = kotlin.text.StringBuilder()
    repeat(2) { n ->
        thing.persist(n)
    }
}
