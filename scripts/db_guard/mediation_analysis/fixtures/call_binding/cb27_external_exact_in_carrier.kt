// CB-27: GR-14u47 preservation-rule exemption — an external-typed
// receiver member call with NO corpus extension for that type, inside an
// admitted carrier, stays EXACT_SYNCHRONOUS external.  The preservation
// rule no longer converts provably-external edges into name-match async
// noise (the carve-out in _resolve_member_call guarantees external exact
// means no corpus dispatch possibility).  The receiver type is a
// kotlin.* dotted spelling (external origin, outside the corpus).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

fun exerciseCb27() {
    val thing = kotlin.text.StringBuilder()
    repeat(2) { n ->
        thing.success(n)
    }
}
