// CB-35: GR-14u49 design fixture 5 / reviewer LV-pos — bounded fan-out
// positive with an explicit-annotation loop variable.  The interface has
// TWO named implementors plus ONE anonymous object (fully enumerable);
// the loop iterable carries an EXPLICIT `: List<Cb35Target>` annotation,
// so the loop variable types as Cb35Target and the dispatch emits THREE
// exact fan-out edges.  The anonymous object is built in a SEPARATE
// helper so the subject function's span contains only the dispatch call
// (override declarations elsewhere would add same-name declaration-noise
// call records — a pre-existing extraction shape).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb35Target {
    val name: String
    fun purge(cutoff: Long)
}

class Cb35ImplA : Cb35Target {
    override val name = "a"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

class Cb35ImplB : Cb35Target {
    override val name = "b"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun cb35MakeAnon(): Cb35Target = object : Cb35Target {
    override val name = "anon"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun exerciseCb35() {
    val targets: List<Cb35Target> = listOf(Cb35ImplA(), Cb35ImplB())
    val ordered: List<Cb35Target> = (targets + listOf(cb35MakeAnon())).sortedBy { it.name }
    for (target in ordered) {
        target.purge(0L)
    }
}
