// CB-36: GR-14u49 design fixture 6 — fan-out completeness negative.  The
// Cb36Other interface has an ENUMERABLE-but-empty implementor set (zero
// named, zero anonymous sites), so the fan-out admission (which requires
// at least one anonymous site to model) does not fire and its dispatch
// stays the uncertain INTERFACE_DISPATCH edge; the Cb36Target dispatch
// (1 named + 1 modelled anon) resolves exact — the contrast pins the
// gate.  Member names are split (purge/drain) so each subject call is
// single-state.
// Expected resolution: INTERFACE_DISPATCH (subject callee = drain).

package fixtures.callbinding

interface Cb36Target {
    val name: String
    fun purge(cutoff: Long)
}

interface Cb36Other {
    fun drain()
}

class Cb36Impl : Cb36Target {
    override val name = "impl"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun cb36MakeAnon(): Cb36Target = object : Cb36Target {
    override val name = "anon"
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

fun exerciseCb36() {
    val ordered: List<Cb36Target> = listOf(Cb36Impl(), cb36MakeAnon())
    for (target in ordered) {
        target.purge(0L)
    }
    val others: List<Cb36Other> = emptyList()
    for (other in others) {
        other.drain()
    }
}
