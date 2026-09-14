// CB-38: GR-14u49 design fixture 8 — all-or-nothing fan-out fallback
// (callgraph-level pin, honest mapping).  The PROOF-layer all-or-nothing
// behavior (one unguarded implementor body fails the whole set back to
// uncertainty) lives in the proof layer and is exercised there; at the
// callgraph level the honest pin is that the fan-out stays EXACT and
// complete regardless of member-body guard content — the callgraph cannot
// see guards.  The anon object lives in a separate helper so the subject
// function's span contains only the dispatch call.
// Expected resolution: EXACT_SYNCHRONOUS (full fan-out; the proof-level
// fallback is documented in the manifest as a proof-layer behavior).

package fixtures.callbinding

interface Cb38Target {
    val name: String
    fun purge(cutoff: Long)
}

class Cb38ImplA : Cb38Target {
    override val name = "a"
    override fun purge(cutoff: Long) {
        // no-op fixture body (unguarded)
    }
}

class Cb38ImplB : Cb38Target {
    override val name = "b"
    override fun purge(cutoff: Long) {
        // no-op fixture body (unguarded)
    }
}

fun cb38MakeAnon(): Cb38Target = object : Cb38Target {
    override val name = "anon"
    override fun purge(cutoff: Long) {
        // no-op fixture body (unguarded)
    }
}

fun exerciseCb38() {
    val targets: List<Cb38Target> = listOf(Cb38ImplA(), Cb38ImplB())
    val ordered: List<Cb38Target> = (targets + listOf(cb38MakeAnon())).sortedBy { it.name }
    for (target in ordered) {
        target.purge(0L)
    }
}
