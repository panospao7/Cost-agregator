// CB-39: GR-14u49 design fixture 9 - multi-supertype object.  An object
// listing TWO corpus interfaces models ONCE (the naming key is the SITE,
// not the supertype), but the synthetic owner records only the FIRST
// supertype - the second interface's dispatch stays INTERFACE_DISPATCH
// (fail-closed: scanned/modelled count mismatch withdraws admission).
// The anon object lives in a separate helper so the subject function's
// span contains only the dispatch call.

package fixtures.callbinding

interface Cb39A {
    fun runA()
}

interface Cb39B {
    fun runB()
}

class Cb39ImplA : Cb39A {
    override fun runA() {
        // no-op fixture body
    }
}

fun cb39MakeDual(): Cb39A = object : Cb39A, Cb39B {
    override fun runA() {
        Cb39ImplA().runA()
    }
    override fun runB() {
        // no-op fixture body
    }
}

fun exerciseCb39() {
    val duals: List<Cb39A> = listOf(cb39MakeDual())
    for (dual in duals) {
        dual.runA()
    }
}
