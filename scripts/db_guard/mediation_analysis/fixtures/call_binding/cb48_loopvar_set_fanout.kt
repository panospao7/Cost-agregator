// CB-48: GR-14u49 loop-var typing over a SET (Sequence-family collection
// head) — an explicitly-annotated `Set<Cb48Target>` iterable types the
// loop variable and the dispatch emits the full exact fan-out over the
// interface's enumerable implementor set (1 named + 1 anonymous).  The
// anon object lives in a separate helper so the subject function's span
// contains only the dispatch call.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb48Target {
    val name: String
    fun purge(cutoff: Long)
}

class Cb48Impl : Cb48Target {
    override val name = "impl"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun cb48MakeAnon(): Cb48Target = object : Cb48Target {
    override val name = "anon"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun exerciseCb48() {
    val named: Set<Cb48Target> = setOf(Cb48Impl())
    val all: Set<Cb48Target> = named + setOf(cb48MakeAnon())
    for (target in all) {
        target.purge(0L)
    }
}
