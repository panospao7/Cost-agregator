// CB-33: GR-14u49 design fixture 3 — two anonymous objects of the same
// supertype in one file get DISTINCT deterministic synthetic keys (per-file
// ordinal).  Both members call a top-level function (receiverless —
// resolves via package scope), and the subject call in EACH member must
// resolve to the same state (the runner's unique-state requirement holds
// across both synthetic members).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb33Target {
    val name: String
}

fun cb33Record(value: String) {
    // no-op fixture body
}

fun exerciseCb33() {
    val a = object : Cb33Target {
        override val name = "a"
        fun emit() {
            cb33Record("a")
        }
    }
    val b = object : Cb33Target {
        override val name = "b"
        fun emit() {
            cb33Record("b")
        }
    }
}
