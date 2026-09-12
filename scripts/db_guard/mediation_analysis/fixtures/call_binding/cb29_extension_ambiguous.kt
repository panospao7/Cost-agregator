// CB-29: GR-14u47 extension carve-out, ambiguous form — an external-typed
// receiver with TWO same-name corpus extensions on that type stays
// uncertain (ambiguity never resolves exact; the carve-out only requires
// EXISTENCE of a candidate to block the external classification).
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

fun kotlin.text.StringBuilder.persist(value: Int) {
    // no-op fixture body
}

fun kotlin.text.StringBuilder.persist(text: String) {
    // no-op fixture body
}

fun exerciseCb29() {
    val thing = kotlin.text.StringBuilder()
    repeat(2) { n ->
        thing.persist(n)
    }
}
