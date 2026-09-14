// CB-40: GR-14u49 design fixture 10 — line-wrapped supertype list negative
// pin.  _ANON_OBJECT_RE captures the supertype list only to end-of-line,
// so a wrapped list under-counts today: the wrapped anonymous object is
// NOT counted for Cb40Target, the fan-out completeness holds over the
// named implementor alone, and the loop-var dispatch resolves via the
// single-implementor exactness rule (EXACT, but to ONE target — the
// wrapped anon member is invisible to the graph).  The anon object lives
// in a separate helper so the subject function's span contains only the
// dispatch call.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb40Target {
    val name: String
    fun purge(cutoff: Long)
}

class Cb40Impl : Cb40Target {
    override val name = "impl"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun cb40MakeWrapped(): Cb40Target = object :
    Cb40Target {
    override val name = "wrapped"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun exerciseCb40() {
    val ordered: List<Cb40Target> = listOf(Cb40Impl())
    for (target in ordered) {
        target.purge(0L)
    }
}
